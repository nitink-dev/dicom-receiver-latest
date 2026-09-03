package com.eh.digitalpathology.dicomreceiver.service;

import com.eh.digitalpathology.dicomreceiver.api.DicomObjectSink;
import com.eh.digitalpathology.dicomreceiver.config.ServerConfig;
import com.eh.digitalpathology.dicomreceiver.constants.StorageObjectRef;
import com.eh.digitalpathology.dicomreceiver.enums.StorageType;
import com.google.cloud.ReadChannel;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.channels.Channels;
import java.nio.file.Paths;

@Component
@ConditionalOnProperty( name = "server.storage.type", havingValue = "gcs" )
public class GcsDicomSink implements DicomObjectSink {

    private final Storage storage;
    private final String bucketName;

    public GcsDicomSink ( Storage storage, ServerConfig serverConfig ) {
        this.storage = storage;
        this.bucketName = serverConfig.getPath( );
    }

    @Override
    public OutputStream openStream ( String objectName ) throws IOException {
        BlobInfo blobInfo = BlobInfo.newBuilder( bucketName, objectName ).setContentType( "application/dicom" ).build( );
        return Channels.newOutputStream( storage.writer( blobInfo ) );
    }

    @Override
    public StorageObjectRef objectRef ( String objectName ) {
        return new StorageObjectRef( StorageType.GCS, bucketName, objectName );
    }


    @Override
    public InputStream openInputStream ( StorageObjectRef ref ) throws IOException {
        Blob blob = storage.get( ref.rootPath( ), ref.objectName( ) );
        if ( blob == null ) {
            throw new FileNotFoundException( "GCS object not found: " + ref.uri( ) );
        }
        ReadChannel reader = blob.reader( );
        return new BufferedInputStream( Channels.newInputStream( reader ), 16 * 1024 * 1024 );
    }


    @Override
    public void delete ( StorageObjectRef ref ) {
        storage.delete( ref.rootPath( ), ref.objectName( ) );
    }


    @Override
    public StorageObjectRef move ( StorageObjectRef ref, String target ) {
        String targetName;
        if (target.endsWith( ".dcm" )){
            targetName = target;
        } else {
            targetName = target + "/" + Paths.get( ref.objectName( ) ).getFileName( );
        }
        BlobId source = BlobId.of( ref.rootPath( ), ref.objectName( ) );
        BlobId destination = BlobId.of( ref.rootPath( ), targetName );

        storage.copy( Storage.CopyRequest.of( source, destination ) );
        storage.delete( source );

        return new StorageObjectRef( StorageType.GCS, ref.rootPath( ), targetName );
    }

}
