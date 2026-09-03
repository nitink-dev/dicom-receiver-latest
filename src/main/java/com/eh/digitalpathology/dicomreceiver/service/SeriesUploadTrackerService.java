package com.eh.digitalpathology.dicomreceiver.service;

import com.eh.digitalpathology.dicomreceiver.config.GcpConfig;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Service
public class SeriesUploadTrackerService {

    private static final Logger log = LoggerFactory.getLogger( SeriesUploadTrackerService.class );

    private static final String SERIES_PREFIX = "series:data:";
    private static final String LOCK_PREFIX = "series:lock:";
    /**
     * Send notification after 5 minutes of inactivity.
     */
    private static final long QUIET_PERIOD_MILLIS = TimeUnit.MINUTES.toMillis( 5 );
    /**
     * Auto-cleanup if something goes wrong.
     */
    private static final Duration SERIES_TTL = Duration.ofMinutes( 15 );

    /**
     * Distributed lock TTL.
     */
    private static final Duration LOCK_TTL = Duration.ofMinutes( 2 );

    private final ScheduledExecutorService scheduler;
    private final RedisTemplate< String, String > redisTemplate;
    private final GcpConfig gcpConfig;
    private final SeriesNotifierService seriesNotifierService;

    public SeriesUploadTrackerService ( @Qualifier( "barcodeUploadScheduledExecutor" ) ScheduledExecutorService scheduler, RedisTemplate< String, String > redisTemplate, GcpConfig gcpConfig, SeriesNotifierService seriesNotifierService ) {

        this.scheduler = scheduler;
        this.redisTemplate = redisTemplate;
        this.gcpConfig = gcpConfig;
        this.seriesNotifierService = seriesNotifierService;
    }

    @PostConstruct
    public void initialize ( ) {
        scheduler.scheduleWithFixedDelay( this::processCompletedSeries, 60, 60, TimeUnit.SECONDS );
        log.info( "initialize :: Series inactivity monitor started" );
    }

    /**
     * Called whenever a file is uploaded.
     */
    public void recordUpload ( String studyInstanceUID, String seriesInstanceUID ) {
        try {
            String seriesKey = SERIES_PREFIX + seriesInstanceUID;
            String seriesUrl = String.format( "%s/%s/dicomWeb/studies/%s/series/%s", gcpConfig.getDicomWebUrl( ), gcpConfig.getResearchStoreUrl( ), studyInstanceUID, seriesInstanceUID );
            long expiryTime = System.currentTimeMillis( ) + QUIET_PERIOD_MILLIS;
            redisTemplate.opsForHash( ).put( seriesKey, "seriesUrl", seriesUrl );
            redisTemplate.opsForHash( ).put( seriesKey, "expiryTime", String.valueOf( expiryTime ) );
            /*
             * Reset housekeeping TTL.
             */
            redisTemplate.expire( seriesKey, SERIES_TTL );
            log.info( "recordUpload :: Reset inactivity timer for series {}", seriesInstanceUID );
        } catch ( Exception ex ) {
            log.error( "recordUpload :: Failed to record upload for series {} :: {}", seriesInstanceUID, ex.getMessage() );
        }
    }

    private void processCompletedSeries ( ) {
        try {
            Set< String > seriesKeys = redisTemplate.keys( SERIES_PREFIX + "*" );
            if ( seriesKeys == null || seriesKeys.isEmpty( ) ) {
                log.info( "processCompletedSeries :: No series to process" );
                return;
            }
            long now = System.currentTimeMillis( );
            for ( String seriesKey : seriesKeys ) {
                processSeries( seriesKey, now );
            }
        } catch ( Exception ex ) {
            log.error( "processCompletedSeries :: Error processing inactive series", ex );
        }
    }

    private void processSeries ( String seriesKey, long now ) {
        try {
            Object expiryTimeObj = redisTemplate.opsForHash( ).get( seriesKey, "expiryTime" );
            if ( expiryTimeObj == null ) {
                return;
            }
            long expiryTime = Long.parseLong( expiryTimeObj.toString( ) );
            if ( now < expiryTime ) {
                return;
            }
            String seriesUid = seriesKey.substring( SERIES_PREFIX.length( ) );
            String lockKey = LOCK_PREFIX + seriesUid;
            Boolean lockAcquired = redisTemplate.opsForValue( ).setIfAbsent( lockKey, "LOCKED", LOCK_TTL );
            if ( !Boolean.TRUE.equals( lockAcquired ) ) {
                log.debug( "processSeries :: Series {} is already being processed by another pod", seriesUid );
                return;
            }
            try {
                /*
                 * Double-check after lock acquisition.
                 * Another upload may have arrived while
                 * lock acquisition was happening.
                 */
                expiryTimeObj = redisTemplate.opsForHash( ).get( seriesKey, "expiryTime" );
                if ( expiryTimeObj == null ) {
                    return;
                }
                expiryTime = Long.parseLong( expiryTimeObj.toString( ) );
                if ( System.currentTimeMillis( ) < expiryTime ) {
                    log.debug( "processSeries :: Series {} became active again", seriesUid );
                    return;
                }
                Object seriesUrlObj = redisTemplate.opsForHash( ).get( seriesKey, "seriesUrl" );
                if ( seriesUrlObj == null ) {
                    redisTemplate.delete( seriesKey );
                    return;
                }
                String seriesUrl = seriesUrlObj.toString( );
                log.info( "processSeries :: Series {} inactive for 10 minutes. Sending notification.", seriesUid );
                seriesNotifierService.notifyVisiopharm( seriesUrl );
                redisTemplate.delete( seriesKey );
                log.info( "processSeries :: Notification sent and cache removed for series {}", seriesUid );
            } finally {
                redisTemplate.delete( lockKey );
            }
        } catch ( Exception ex ) {
            log.error( "processSeries :: Failed processing {} :: {}", seriesKey, ex.getMessage() );
        }
    }
}
