package com.eh.digitalpathology.dicomreceiver.service;

import com.eh.digitalpathology.dicomreceiver.config.GcpConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.*;

@ExtendWith( MockitoExtension.class )
class SeriesUploadTrackerServiceTest {

    @Mock
    private ScheduledExecutorService scheduler;

    @Mock
    private RedisTemplate< String, String > redisTemplate;

    @Mock
    private HashOperations< String, Object, Object > hashOperations;

    @Mock
    private ValueOperations< String, String > valueOperations;

    @Mock
    private GcpConfig gcpConfig;

    @Mock
    private SeriesNotifierService seriesNotifierService;

    @InjectMocks
    private SeriesUploadTrackerService service;

    @BeforeEach
    void setUp ( ) {

        service = new SeriesUploadTrackerService( scheduler, redisTemplate, gcpConfig, seriesNotifierService );
        lenient( ).when( redisTemplate.opsForHash( ) ).thenReturn( hashOperations );
        lenient( ).when( redisTemplate.opsForValue( ) ).thenReturn( valueOperations );
    }

    @Test
    void initialize_ShouldScheduleMonitor ( ) {
        service.initialize( );
        verify( scheduler ).scheduleWithFixedDelay( any( Runnable.class ), eq( 60L ), eq( 60L ), eq( TimeUnit.SECONDS ) );
    }

    @Test
    void recordUpload_ShouldStoreSeriesInRedis ( ) {
        when( gcpConfig.getDicomWebUrl( ) ).thenReturn( "http://dicom" );
        when( gcpConfig.getResearchStoreUrl( ) ).thenReturn( "researchStore" );
        service.recordUpload( "STUDY001", "SERIES001" );
        String expectedSeriesUrl = "http://dicom/researchStore/dicomWeb/studies/STUDY001/series/SERIES001";
        verify( hashOperations ).put( "series:data:SERIES001", "seriesUrl", expectedSeriesUrl );

        verify( hashOperations ).put( eq( "series:data:SERIES001" ), eq( "expiryTime" ), anyString( ) );
        verify( redisTemplate ).expire( "series:data:SERIES001", Duration.ofMinutes( 15 ) );
    }

    @Test
    void processCompletedSeries_ShouldHandleNoKeys ( ) {

        when( redisTemplate.keys( "series:data:*" ) ).thenReturn( Collections.emptySet( ) );
        ReflectionTestUtils.invokeMethod( service, "processCompletedSeries" );
        verifyNoInteractions( seriesNotifierService );
    }

    @Test
    void processCompletedSeries_ShouldSkipActiveSeries ( ) {

        String key = "series:data:SERIES001";
        when( redisTemplate.keys( "series:data:*" ) ).thenReturn( Set.of( key ) );
        when( hashOperations.get( key, "expiryTime" ) ).thenReturn( String.valueOf( System.currentTimeMillis( ) + 600000 ) );

        ReflectionTestUtils.invokeMethod( service, "processCompletedSeries" );
        verifyNoInteractions( seriesNotifierService );
    }

    @Test
    void processCompletedSeries_ShouldNotifyExpiredSeries ( ) {

        String key = "series:data:SERIES001";
        String seriesUrl = "http://localhost/studies/STUDY001/series/SERIES001";

        when( redisTemplate.keys( "series:data:*" ) ).thenReturn( Set.of( key ) );
        when( hashOperations.get( key, "expiryTime" ) ).thenReturn( String.valueOf( System.currentTimeMillis( ) - 10000 ) );

        when( valueOperations.setIfAbsent( eq( "series:lock:SERIES001" ), eq( "LOCKED" ), any( Duration.class ) ) ).thenReturn( true );
        when( hashOperations.get( key, "seriesUrl" ) ).thenReturn( seriesUrl );

        ReflectionTestUtils.invokeMethod( service, "processCompletedSeries" );
        verify( seriesNotifierService ).notifyVisiopharm( seriesUrl );

        verify( redisTemplate ).delete( key );
        verify( redisTemplate ).delete( "series:lock:SERIES001" );
    }

    @Test
    void processCompletedSeries_ShouldSkipWhenLockAcquisitionFails ( ) {

        String key = "series:data:SERIES001";

        when( redisTemplate.keys( "series:data:*" ) ).thenReturn( Set.of( key ) );
        when( hashOperations.get( key, "expiryTime" ) ).thenReturn( String.valueOf( System.currentTimeMillis( ) - 10000 ) );

        when( valueOperations.setIfAbsent( anyString( ), anyString( ), any( Duration.class ) ) ).thenReturn( false );
        ReflectionTestUtils.invokeMethod( service, "processCompletedSeries" );

        verifyNoInteractions( seriesNotifierService );
    }

    @Test
    void processCompletedSeries_ShouldDeleteRecordWhenSeriesUrlMissing ( ) {

        String key = "series:data:SERIES001";

        when( redisTemplate.keys( "series:data:*" ) ).thenReturn( Set.of( key ) );
        when( hashOperations.get( key, "expiryTime" ) ).thenReturn( String.valueOf( System.currentTimeMillis( ) - 10000 ) );

        when( valueOperations.setIfAbsent( anyString( ), anyString( ), any( Duration.class ) ) ).thenReturn( true );
        when( hashOperations.get( key, "seriesUrl" ) ).thenReturn( null );

        ReflectionTestUtils.invokeMethod( service, "processCompletedSeries" );
        verify( redisTemplate ).delete( key );
        verifyNoInteractions( seriesNotifierService );
    }

    @Test
    void processCompletedSeries_ShouldSkipIfSeriesBecomesActiveAgain ( ) {

        String key = "series:data:SERIES001";

        when( redisTemplate.keys( "series:data:*" ) ).thenReturn( Set.of( key ) );
        when( hashOperations.get( key, "expiryTime" ) ).thenReturn( String.valueOf( System.currentTimeMillis( ) - 10000 ) ).thenReturn( String.valueOf( System.currentTimeMillis( ) + 600000 ) );

        when( valueOperations.setIfAbsent( anyString( ), anyString( ), any( Duration.class ) ) ).thenReturn( true );
        ReflectionTestUtils.invokeMethod( service, "processCompletedSeries" );

        verifyNoInteractions( seriesNotifierService );
    }

    @Test
    void processCompletedSeries_ShouldHandleMissingExpiryTime ( ) {

        String key = "series:data:SERIES001";
        when( redisTemplate.keys( "series:data:*" ) ).thenReturn( Set.of( key ) );

        when( hashOperations.get( key, "expiryTime" ) ).thenReturn( null );
        ReflectionTestUtils.invokeMethod( service, "processCompletedSeries" );

        verifyNoInteractions( seriesNotifierService );
    }
}

