package com.eh.digitalpathology.dicomreceiver.service.stowrs;

import com.eh.digitalpathology.dicomreceiver.api.DicomObjectSink;
import com.eh.digitalpathology.dicomreceiver.service.MetadataDispatcher;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.io.DicomInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping( "dicomweb" )
public class StowRsController {

    private static final Logger log = LoggerFactory.getLogger( StowRsController.class );

    private final DicomObjectSink sink;
    private final MetadataDispatcher metadataDispatcher;

    public StowRsController ( DicomObjectSink sink, MetadataDispatcher metadataDispatcher ) {
        this.sink = sink;
        this.metadataDispatcher = metadataDispatcher;
    }

    /**
     * Single DICOM instance:
     * <p>
     * Content-Type: application/dicom
     */
    @PostMapping( value = "studies", consumes = "application/dicom" )
    public Mono< ResponseEntity< Void > > stowDicom ( ServerHttpRequest request ) {
        log.info( "stowDicom :: contentType={}", request.getHeaders( ).getContentType( ) );
        return storeFlux( request.getBody( ) ).thenReturn( ResponseEntity.ok( ).build( ) );
    }

    /**
     * Multipart STOW-RS:
     * <p>
     * Content-Type: multipart/related
     */
    @PostMapping( value = "studies", consumes = {"multipart/related", MediaType.MULTIPART_FORM_DATA_VALUE} )
    public Mono< ResponseEntity< Void > > stowMultipart ( @RequestBody Flux< Part > parts ) {
        log.info( "stowMultipart :: request received" );
        return parts.filter( this::isDicomPart ).flatMap( part -> storeFlux( extractContent( part ) ), 4 ).then( Mono.just( ResponseEntity.ok( ).build( ) ) );
    }

    private Flux< DataBuffer > extractContent ( Part part ) {

        if ( part instanceof FilePart filePart ) {
            return filePart.content( );
        }
        return part.content( );
    }

    private boolean isDicomPart ( Part part ) {

        MediaType mediaType = part.headers( ).getContentType( );
        return mediaType != null && mediaType.toString( ).contains( "application/dicom" );
    }

    private Mono< Void > storeFlux ( Flux< DataBuffer > flux ) {
        return Mono.defer( ( ) -> {
            String tempObjectName = UUID.randomUUID( ) + ".dcm";
            log.info( "storeFlux :: tempObjectName={}", tempObjectName );
            return Mono.using(
                    ( ) -> sink.openStream( tempObjectName ),
                    gcsOut -> DataBufferUtils.write( flux, gcsOut ).then( ),
                    outputStream -> {
                        try {
                            outputStream.flush( );
                            outputStream.close( );
                            log.info( "storeFlux :: GCS stream closed {}", tempObjectName );
                        } catch ( IOException ex ) {
                            log.error( "storeFlux :: Failed closing stream", ex );
                        }
                    } ).then( Mono.fromRunnable( ( ) -> {
                try {
                    String sopUid = null;
                    try ( InputStream in = sink.openInputStream( sink.objectRef( tempObjectName ) );
                          DicomInputStream dis = new DicomInputStream( in ) ) {
                        Attributes attrs = dis.readDatasetUntilPixelData();
                        sopUid = attrs.getString( Tag.SOPInstanceUID );
                    }
                    String finalName = sopUid != null ? sopUid + ".dcm" : tempObjectName;
                    log.info( "storeFlux :: SOP UID={} finalName={}", sopUid, finalName );
                    if ( !finalName.equals( tempObjectName ) ) {
                        sink.move( sink.objectRef( tempObjectName ), finalName );
                        log.info( "storeFlux :: moved {} -> {}", tempObjectName, finalName );
                    }
                    metadataDispatcher.submit( sink.objectRef( finalName ) );
                } catch ( Exception ex ) {
                    log.error( "storeFlux :: post-upload processing failed", ex );
                    throw new RuntimeException( ex );
                }
            } ) );

        } ).subscribeOn( Schedulers.newBoundedElastic(20, Integer.MAX_VALUE, "stow-rs" ) ).then( );
    }
}
