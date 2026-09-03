package com.eh.digitalpathology.dicomreceiver.service;

import com.eh.digitalpathology.dicomreceiver.api.DicomObjectSink;
import com.eh.digitalpathology.dicomreceiver.config.KafkaTopicConfig;
import com.eh.digitalpathology.dicomreceiver.constants.StorageObjectRef;
import com.eh.digitalpathology.dicomreceiver.model.DicomRequestDBObject;
import com.eh.digitalpathology.dicomreceiver.model.ReqGeneratorNotificationMsg;
import com.eh.digitalpathology.dicomreceiver.model.SlideScanProgressEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import static com.eh.digitalpathology.dicomreceiver.constants.SlideScanStatusConstant.SLIDE_SCAN_ENRICH_IN_PROGRESS;

@Service
public class FileProcessingService {
    private final DicomExtractorService dicomExtractorService;
    private final DatabaseService databaseService;
    private final EventNotificationService eventNotificationService;
    private final KafkaTopicConfig kafkaTopicConfig;
    public static final String DICOM_RECEIVER = "dicom-receiver";
    private final DicomObjectSink sink;

    private static final Logger log = LoggerFactory.getLogger( FileProcessingService.class.getName( ) );
    private ObjectMapper objectMapper = new ObjectMapper( );

    public FileProcessingService ( DicomExtractorService dicomExtractorService, DatabaseService databaseService, EventNotificationService eventNotificationService, KafkaTopicConfig kafkaTopicConfig, DicomObjectSink sink ) {
        this.dicomExtractorService = dicomExtractorService;
        this.databaseService = databaseService;
        this.eventNotificationService = eventNotificationService;
        this.kafkaTopicConfig = kafkaTopicConfig;
        this.sink = sink;
    }

    public void processFile ( StorageObjectRef ref ) {
        log.info( "processFile :: ===============================>Process File thread Started:{}", Thread.currentThread( ).getName( ) );
        if ( ref.objectName( ).endsWith( ".svs" ) ) {
            sink.move( ref, "svs");
            return;
        }
        try {
            DicomRequestDBObject dicomRequestDBObject = dicomExtractorService.extract( ref );
            if ( dicomRequestDBObject == null ) {
                log.error( "processFile :: Dicom extracted for qa slide or research or failed for file: {}", ref.objectName( ) );
                return;
            }
            String status = databaseService.insertDicomData( dicomRequestDBObject, DICOM_RECEIVER );
            log.info( "processFile :: response from insertDicomData(): {}", status );
            if ( "success".equalsIgnoreCase( status ) ) {
                log.info( "processFile :: Data insertion successful !!!\n sending kafka notification.." );
                ReqGeneratorNotificationMsg msg = new ReqGeneratorNotificationMsg( dicomRequestDBObject.getBarcode( ), dicomRequestDBObject.getSopInstanceUid( ), dicomRequestDBObject.getSeriesInstanceUid( ), dicomRequestDBObject.getDeviceSerialNumber( ) );
                notifyKafka( kafkaTopicConfig.getDcm( ),  dicomRequestDBObject.getBarcode( ), msg );
                SlideScanProgressEvent slideScanProgressEvent = new SlideScanProgressEvent( dicomRequestDBObject.getBarcode( ), dicomRequestDBObject.getSeriesInstanceUid( ), dicomRequestDBObject.getDeviceSerialNumber( ), SLIDE_SCAN_ENRICH_IN_PROGRESS );
                notifyKafka( kafkaTopicConfig.getScanProgress( ), slideScanProgressEvent.slideBarcode( ), slideScanProgressEvent );
            } else {
                log.info( "processFile :: KAFKA NOT NOTIFIED!!!.. " );
            }
        } catch ( Exception ex ) {
            log.error( "processFile :: Exception occurred while processing file: {} error: {}", ref.objectName( ), ex.getMessage( ) );
            sink.delete( ref );
        }
    }

    public void notifyKafka ( String topic, String key, Object value ) {
        try {
            eventNotificationService.sendEvent( topic, key, objectMapper.writeValueAsString( value ) );
        } catch ( JsonProcessingException e ) {
            log.error( "notifyKafka: JSON serialization failed | topic={} key={} error={}", topic, key, e.getMessage( ), e );
        }
    }

}