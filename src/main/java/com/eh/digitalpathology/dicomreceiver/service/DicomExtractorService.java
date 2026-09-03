package com.eh.digitalpathology.dicomreceiver.service;

import com.eh.digitalpathology.dicomreceiver.api.DicomObjectSink;
import com.eh.digitalpathology.dicomreceiver.config.GcpConfig;
import com.eh.digitalpathology.dicomreceiver.config.KafkaTopicConfig;
import com.eh.digitalpathology.dicomreceiver.constants.StorageObjectRef;
import com.eh.digitalpathology.dicomreceiver.constants.WatchDirectoryConstant;
import com.eh.digitalpathology.dicomreceiver.exceptions.DicomAttributesException;
import com.eh.digitalpathology.dicomreceiver.model.DicomDirDocument;
import com.eh.digitalpathology.dicomreceiver.model.DicomRequestDBObject;
import com.eh.digitalpathology.dicomreceiver.model.SlideScanProgressEvent;
import com.eh.digitalpathology.dicomreceiver.model.SlideScanner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;


@Service
@RefreshScope
public class DicomExtractorService {
    private static final Logger log = LoggerFactory.getLogger( DicomExtractorService.class.getName( ) );

    @Value( "${dicom.barcode.generation.enable}" )
    private boolean enableBarcodeGeneration;

    private static final String PENDING_DICOMDIR_PREFIX = "pending:dicodir:";

    private final EventNotificationService eventNotificationService;
    private final DatabaseService dbService;
    private final DicomHealthcareApiService dicomHealthcareApiClient;
    private final DicomDirService dicomDirService;
    private final Cache< String, String > studyBarcodeCache;
    private final BarcodeUploadTrackerService barcodeUploadTrackerService;
    private final KafkaTopicConfig kafkaTopicConfig;
    private final GcpConfig gcpConfig;
    private final DicomObjectSink dicomObjectSink;
    private final SeriesUploadTrackerService seriesUploadTrackerService;
    private final ObjectMapper objectMapper = new ObjectMapper( );
    private final RedisTemplate< String, Object > redisTemplate;

    public DicomExtractorService ( EventNotificationService eventNotificationService, DatabaseService dbService, DicomHealthcareApiService dicomHealthcareApiClient, DicomDirService dicomDirService, Cache< String, String > studyBarcodeCache, BarcodeUploadTrackerService barcodeUploadTrackerService,
                                   KafkaTopicConfig kafkaTopicConfig, GcpConfig gcpConfig, DicomObjectSink dicomObjectSink, SeriesUploadTrackerService seriesUploadTrackerService, RedisTemplate< String, Object > redisTemplate ) {
        this.eventNotificationService = eventNotificationService;
        this.dbService = dbService;
        this.dicomHealthcareApiClient = dicomHealthcareApiClient;
        this.dicomDirService = dicomDirService;
        this.studyBarcodeCache = studyBarcodeCache;
        this.barcodeUploadTrackerService = barcodeUploadTrackerService;
        this.kafkaTopicConfig = kafkaTopicConfig;
        this.gcpConfig = gcpConfig;
        this.dicomObjectSink = dicomObjectSink;
        this.seriesUploadTrackerService = seriesUploadTrackerService;
        this.redisTemplate = redisTemplate;
    }

    public DicomRequestDBObject extract ( StorageObjectRef ref ) throws DicomAttributesException {
        log.info( "DicomExtractorService :: Processing {}", ref.uri( ) );
        try ( InputStream in = dicomObjectSink.openInputStream( ref ); DicomInputStream dis = new DicomInputStream( in ) ) {

            final Attributes attributes = dis.readDataset( );
            final Attributes fmi = dis.readFileMetaInformation( );

            final String sopInstanceUID = require( attributes, Tag.SOPInstanceUID, "SOPInstanceUID" );
            final String seriesInstanceUID = require( attributes, Tag.SeriesInstanceUID, "SeriesInstanceUID" );
            final String studyInstanceUID = require( attributes, Tag.StudyInstanceUID, "StudyInstanceUID" );
            final String deviceSerialNumber = attributes.getString( Tag.DeviceSerialNumber );

            final SlideScanner slideScanner = dbService.fetchScannerByDeviceSerialNumber( deviceSerialNumber );
            log.info( "extract:: slide scanner ::: {}", slideScanner );

            if ( slideScanner == null || !slideScanner.connected( ) ) {
                log.warn( "extract :: Scanner not connected (device={}), moving object", deviceSerialNumber );
                dicomObjectSink.move( ref, deviceSerialNumber + "/" + studyInstanceUID + "/" + seriesInstanceUID );
                return null;
            }
            log.info( "extract :: slide scanner research:: {}", slideScanner.research( ) );

            if ( slideScanner.research( ) ) {
                log.info( "extract :: Research scanner detected (device={}), forwarding to research DICOM store", deviceSerialNumber );
                dicomHealthcareApiClient.storeDicomInstances( fmi, attributes, gcpConfig.getResearchStoreUrl( ) );
                seriesUploadTrackerService.recordUpload( studyInstanceUID, seriesInstanceUID );
                eventNotificationService.sendEvent( kafkaTopicConfig.getScanProgress( ), null, objectMapper.writeValueAsString( new SlideScanProgressEvent( "research_event" ) ) );
                // ✅ research store is authoritative — cleanup temp storage
                dicomObjectSink.delete( ref );
                return null;
            }
            if ( attributes.getString( Tag.BarcodeValue ) == null && enableBarcodeGeneration ) {
                String generated = generateShortBarcode( studyInstanceUID, seriesInstanceUID );
                attributes.setString( Tag.BarcodeValue, VR.LO, generated );
                log.info( "extract :: Generated barcode {} for study {}", generated, studyInstanceUID );
            }
            String barcode = require( attributes, Tag.BarcodeValue, WatchDirectoryConstant.BARCODE );

            if ( dbService.isBarcodeExists( barcode ) ) {
                dicomHealthcareApiClient.storeDicomInstances( fmi, attributes, gcpConfig.getPathqaStoreUrl( ) );
                barcodeUploadTrackerService.recordUpload( barcode, studyInstanceUID, seriesInstanceUID, deviceSerialNumber );
                dicomObjectSink.delete( ref );
                return null;
            }

            DicomRequestDBObject dbo = new DicomRequestDBObject( );
            dbo.setBarcode( barcode );
            dbo.setSopInstanceUid( sopInstanceUID );
            dbo.setSeriesInstanceUid( seriesInstanceUID );
            dbo.setOriginalStudyInstanceUid( studyInstanceUID );
            dbo.setDeviceSerialNumber( deviceSerialNumber );
            dbo.setIntermediateStoragePath( ref.uri( ) );
            dbo.setDicomInstanceReceivedTimestamp( new Date( ) );

            studyBarcodeCache.put( studyInstanceUID, barcode );
            log.info( "extract :: Metadata extracted successfully for SOP={}", sopInstanceUID );
            processPendingDicomDirIfPresent( studyInstanceUID, barcode );

            return dbo;
        } catch ( DicomAttributesException e ) {
            // ✅ Invalid DICOM → delete + rethrow
            log.error( "extract :: Invalid DICOM {} : {}", ref.uri( ), e.getMessage( ) );
            dicomObjectSink.delete( ref );
            throw e;

        } catch ( Exception e ) {
            // ✅ Defensive cleanup on unexpected failure
            log.error( "extract :: Unexpected failure processing {}", ref.uri( ), e );
            dicomObjectSink.delete( ref );
            throw new DicomAttributesException( "PROCESSING_ERROR", e.getMessage( ) );
        }
    }

    private String require ( Attributes attrs, int tag, String name ) {
        String value = attrs.getString( tag );
        if ( value == null || value.isBlank( ) ) {
            if ( tag == Tag.BarcodeValue ) {
                eventNotificationService.sendEvent( kafkaTopicConfig.getEmail( ), "MISSING_BARCODE", null );
            }
            throw new DicomAttributesException( "INVALID_ATTRIBUTE", name + " is missing" );
        }
        return value;
    }


    public void handleDicomDir ( InputStream dicomDirStream, String relativeFilePath ) {
        log.info( "handleDicomDir :: Handling DICOMDIR directly: {}", dicomDirStream );
        try {
            byte[] dicomDirBytes = dicomDirStream.readAllBytes( );
            try ( DicomInputStream dis = new DicomInputStream( new ByteArrayInputStream( dicomDirBytes ) ) ) {
                Attributes attrs = dis.readDataset( );
                processDicomDirWithDelay( dicomDirBytes, attrs, relativeFilePath );
            }
        } catch ( Exception e ) {
            log.error( "handleDicomDir:: Failed to handle DICOMDIR {}", relativeFilePath, e );
        }
    }


    private void processDicomDirWithDelay ( byte[] dicomDirBytes, Attributes attributes, String relativeFilePath ) {
        try {
            DicomDirDocument dicomDirDocument = dicomDirService.fetchMetaData( dicomDirBytes, attributes );
            if ( dicomDirDocument == null ) {
                log.warn( "processDicomDirWithDelay :: DicomDirDocument is null for path: {}", relativeFilePath );
                return;
            }
            String studyId = dicomDirDocument.studyId( );
            String barcode = studyBarcodeCache.getIfPresent( studyId );
            if ( barcode != null ) {
                if ( dbService.isBarcodeExists( barcode ) ) {
                    log.info( "processDicomDirWithDelay :: Skipping DICOMDIR - barcode already exists in DB: {}", barcode );
                    return;
                }
                log.info( "processDicomDirWithDelay :: Saving DICOMDIR immediately for studyUID={}", studyId );
                dicomDirService.fetchAndStoreMetaData( dicomDirDocument );
                return;
            }
            String redisKey = PENDING_DICOMDIR_PREFIX + studyId;
            redisTemplate.opsForValue( ).set( redisKey, dicomDirDocument, 2, TimeUnit.MINUTES );
            log.info( "processDicomDirWithDelay :: Stored pending DICOMDIR in Redis for studyUID={}", studyId );
            CompletableFuture.delayedExecutor( 2, TimeUnit.MINUTES ).execute( ( ) -> processPendingDicomDirAfterTimeout( studyId ) );
        } catch ( Exception e ) {
            log.error( "processDicomDirWithDelay :: Error initiating DICOMDIR processing", e );
        }
    }

    private void processPendingDicomDirIfPresent ( String studyId, String barcode ) {

        String lockKey = "lock:dicomdir:" + studyId;
        String redisKey = PENDING_DICOMDIR_PREFIX + studyId;

        Boolean acquired = redisTemplate.opsForValue( ).setIfAbsent( lockKey, "LOCKED", 30, TimeUnit.SECONDS );
        if ( !Boolean.TRUE.equals( acquired ) ) {
            log.info( "processPendingDicomDirIfPresent :: Another thread/pod is already processing studyUID={}", studyId );
            return;
        }
        try {
            DicomDirDocument dicomDirDocument = (DicomDirDocument) redisTemplate.opsForValue( ).get( redisKey );
            if ( dicomDirDocument == null ) {
                log.debug( "processPendingDicomDirIfPresent :: No pending DICOMDIR found for studyUID={}", studyId );
                return;
            }
            if ( dbService.isBarcodeExists( barcode ) ) {
                log.info( "processPendingDicomDirIfPresent :: Barcode already exists in DB for studyUID={}", studyId );
                redisTemplate.delete( redisKey );
                return;
            }
            log.info( "processPendingDicomDirIfPresent :: Processing pending DICOMDIR for studyUID={}", studyId );
            dicomDirService.fetchAndStoreMetaData( dicomDirDocument );
            redisTemplate.delete( redisKey );
            log.info( "processPendingDicomDirIfPresent :: Successfully processed DICOMDIR for studyUID={}", studyId );
        } catch ( Exception e ) {
            log.error( "processPendingDicomDirIfPresent :: Error processing DICOMDIR for studyUID={}", studyId, e );
        } finally {
            redisTemplate.delete( lockKey );
        }
    }

    private void processPendingDicomDirAfterTimeout ( String studyId ) {
        try {
            String redisKey = PENDING_DICOMDIR_PREFIX + studyId;
            DicomDirDocument dicomDirDocument = (DicomDirDocument) redisTemplate.opsForValue( ).get( redisKey );
            /*
             * Already processed earlier
             */
            if ( dicomDirDocument == null ) {
                return;
            }
            String barcode = studyBarcodeCache.getIfPresent( studyId );
            if ( barcode != null && dbService.isBarcodeExists( barcode ) ) {
                redisTemplate.delete( redisKey );
                return;
            }
            log.info( "processPendingDicomDirAfterTimeout :: Timeout reached for studyUID={}", studyId );
            dicomDirService.fetchAndStoreMetaData( dicomDirDocument );
            redisTemplate.delete( redisKey );
        } catch ( Exception e ) {
            log.error( "processPendingDicomDirAfterTimeout :: Error processing timeout DICOMDIR for studyUID={}", studyId, e );
        }
    }

    private String generateShortBarcode ( String studyUID, String seriesUID ) {
        try {
            String input = studyUID + seriesUID;
            MessageDigest md = MessageDigest.getInstance( "SHA-256" );
            byte[] hash = md.digest( input.getBytes( StandardCharsets.UTF_8 ) );
            // Convert to positive BigInteger and then to Base36
            String base36 = new BigInteger( 1, hash ).toString( 36 ).toUpperCase( );
            // Take the first 4 characters and prefix with "BC"
            return "BC-" + base36.substring( 0, 4 );
        } catch ( NoSuchAlgorithmException e ) {
            throw new DicomAttributesException( "SHA-256 algorithm not found", e.getMessage( ) );
        }
    }

}