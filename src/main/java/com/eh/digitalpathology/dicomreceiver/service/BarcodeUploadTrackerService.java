package com.eh.digitalpathology.dicomreceiver.service;

import com.eh.digitalpathology.dicomreceiver.config.KafkaTopicConfig;
import com.eh.digitalpathology.dicomreceiver.model.PathQa;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class BarcodeUploadTrackerService {

    private static final Logger log = LoggerFactory.getLogger( BarcodeUploadTrackerService.class );
    private static final String BARCODE_PREFIX = "barcode:data:";
    private static final String LOCK_PREFIX = "barcode:lock:";
    /**
     * Send notification after 5 minutes of inactivity.
     */
    private static final long QUIET_PERIOD_MILLIS = TimeUnit.MINUTES.toMillis( 5 );
    /**
     * Automatic cleanup TTL.
     */
    private static final Duration RECORD_TTL = Duration.ofMinutes( 15 );
    /**
     * Distributed lock TTL.
     */
    private static final Duration LOCK_TTL = Duration.ofMinutes( 2 );

    private final ObjectMapper objectMapper = new ObjectMapper( );

    private final ScheduledExecutorService scheduler;
    private final RedisTemplate< String, String > redisTemplate;
    private final EventNotificationService eventNotificationService;
    private final KafkaTopicConfig kafkaTopicConfig;

    public BarcodeUploadTrackerService ( @Qualifier( "barcodeUploadScheduledExecutor" ) ScheduledExecutorService scheduler, RedisTemplate< String, String > redisTemplate, EventNotificationService eventNotificationService, KafkaTopicConfig kafkaTopicConfig ) {
        this.scheduler = scheduler;
        this.redisTemplate = redisTemplate;
        this.eventNotificationService = eventNotificationService;
        this.kafkaTopicConfig = kafkaTopicConfig;
    }

    @PostConstruct
    public void initialize ( ) {
        scheduler.scheduleWithFixedDelay( this::processCompletedBarcodes, 60, 60, TimeUnit.SECONDS );
        log.info( "initialize :: Barcode inactivity monitor started" );
    }

    /**
     * Called for every upload.
     * <p>
     * Every upload resets the timer.
     */
    public void recordUpload ( String barcodeValue, String studyInstanceUID, String seriesInstanceUID, String deviceSerialNumber ) {
        try {
            String barcodeKey = BARCODE_PREFIX + seriesInstanceUID;
            long expiryTime = System.currentTimeMillis( ) + QUIET_PERIOD_MILLIS;
            redisTemplate.opsForHash( ).put( barcodeKey, "barcode", barcodeValue );
            redisTemplate.opsForHash( ).put( barcodeKey, "studyInstanceUID", studyInstanceUID );
            redisTemplate.opsForHash( ).put( barcodeKey, "deviceSerialNumber", deviceSerialNumber );
            redisTemplate.opsForHash( ).put( barcodeKey, "expiryTime", String.valueOf( expiryTime ) );

            /**
             * Reset housekeeping TTL.
             */
            redisTemplate.expire( barcodeKey, RECORD_TTL );
            log.info( "recordUpload :: Reset inactivity timer for series {}", seriesInstanceUID );
        } catch ( Exception ex ) {
            log.error( "recordUpload :: Failed recording upload :: {}", ex.getMessage() );
        }
    }

    private void processCompletedBarcodes ( ) {
        try {
            Set< String > barcodeKeys = redisTemplate.keys( BARCODE_PREFIX + "*" );
            if ( barcodeKeys == null || barcodeKeys.isEmpty( ) ) {
                log.info( "processCompletedBarcodes :: No barcodes to process" );
                return;
            }
            long now = System.currentTimeMillis( );
            for ( String barcodeKey : barcodeKeys ) {
                processBarcode( barcodeKey, now );
            }
        } catch ( Exception ex ) {
            log.error( "processCompletedBarcodes :: Error processing barcode notifications :: {}", ex.getMessage() );
        }
    }

    private void processBarcode ( String barcodeKey, long now ) {
        try {
            Object expiryTimeObj = redisTemplate.opsForHash( ).get( barcodeKey, "expiryTime" );
            if ( expiryTimeObj == null ) {
                log.info( "processBarcode :: No expiry time found for barcode key {}", barcodeKey );
                return;
            }
            long expiryTime = Long.parseLong( expiryTimeObj.toString( ) );
            if ( now < expiryTime ) {
                log.info( "processBarcode :: Barcode key {} is still active", barcodeKey );
                return;
            }
            String seriesUid = barcodeKey.substring( BARCODE_PREFIX.length( ) );
            if ( seriesUid == null ) {
                log.info( "processBarcode :: No series UID found for barcode key {}", barcodeKey );
                return;
            }
            String lockKey = LOCK_PREFIX + seriesUid;
            Boolean lockAcquired = redisTemplate.opsForValue( ).setIfAbsent( lockKey, "LOCKED", LOCK_TTL );
            if ( !Boolean.TRUE.equals( lockAcquired ) ) {
                log.info( "processBarcode :: Failed to acquire lock for barcode key {}", barcodeKey );
                return;
            }
            try {
                /**
                 * Double-check after lock
                 * acquisition.
                 */
                expiryTimeObj = redisTemplate.opsForHash( ).get( barcodeKey, "expiryTime" );
                if ( expiryTimeObj == null ) {
                    log.info( "processBarcode :: No expiry time found for barcode key {}", barcodeKey );
                    return;
                }
                expiryTime = Long.parseLong( expiryTimeObj.toString( ) );
                if ( System.currentTimeMillis( ) < expiryTime ) {
                    log.info( "processBarcode :: Barcode key {} is still active", barcodeKey );
                    return;
                }
                String barcode = (String) redisTemplate.opsForHash( ).get( barcodeKey, "barcode" );
                String studyInstanceUID = (String) redisTemplate.opsForHash( ).get( barcodeKey, "studyInstanceUID" );
                String deviceSerialNumber = (String) redisTemplate.opsForHash( ).get( barcodeKey, "deviceSerialNumber" );
                if ( barcode == null || studyInstanceUID == null ) {
                    log.info( "processBarcode :: Incomplete data for barcode key {}", barcodeKey );
                    redisTemplate.delete( barcodeKey );
                    return;
                }
                log.info( "processBarcode :: Barcode {} inactive for 5 minutes. Sending Kafka notification.", barcode );
                sendKafkaNotification( barcode, studyInstanceUID, seriesUid, deviceSerialNumber );
                redisTemplate.delete( barcodeKey );
                log.info( "processBarcode :: Kafka notification sent and cache removed for series {}", seriesUid );
            } finally {
                redisTemplate.delete( lockKey );
            }
        } catch ( Exception ex ) {
            log.error( "processBarcode :: Failed processing barcode key {}:: {}", barcodeKey, ex.getMessage() );
        }
    }

    private void sendKafkaNotification ( String barcode, String studyInstanceUID, String seriesInstanceUID, String deviceSerialNumber ) {
        try {
            PathQa pathQa = new PathQa( barcode, studyInstanceUID, seriesInstanceUID, deviceSerialNumber );
            String payload = objectMapper.writeValueAsString( pathQa );
            eventNotificationService.sendEvent( kafkaTopicConfig.getPathqa( ), barcode, payload );
            log.info( "sendKafkaNotification :: Kafka notification sent for barcode {}", barcode );
        } catch ( Exception ex ) {
            log.error( "sendKafkaNotification :: Failed sending Kafka notification for barcode {}: {}", barcode, ex.getMessage() );
        }
    }
}

