package com.eh.digitalpathology.dicomreceiver.service;

import com.eh.digitalpathology.dicomreceiver.config.GcpConfig;
import com.eh.digitalpathology.dicomreceiver.config.KafkaTopicConfig;
import com.eh.digitalpathology.dicomreceiver.model.PathQa;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith( MockitoExtension.class )
class BarcodeUploadTrackerServiceTest {

    @Mock
    private ScheduledExecutorService scheduler;

    @Mock
    private RedisTemplate< String, String > redisTemplate;

    @Mock
    private HashOperations< String, Object, Object > hashOperations;

    @Mock
    private ValueOperations< String, String > valueOperations;

    @Mock
    private EventNotificationService eventNotificationService;

    @Mock
    private KafkaTopicConfig kafkaTopicConfig;

    @Mock
    private ScheduledFuture< ? > scheduledFuture;

    @InjectMocks
    private BarcodeUploadTrackerService service;

    @BeforeEach
    void setUp ( ) {

        service = new BarcodeUploadTrackerService( scheduler, redisTemplate, eventNotificationService, kafkaTopicConfig );
        lenient( ).when( redisTemplate.opsForHash( ) ).thenReturn( hashOperations );
        lenient( ).when( redisTemplate.opsForValue( ) ).thenReturn( valueOperations );
    }

    @Test
    void initialize_ShouldScheduleScanner ( ) {

        doReturn( scheduledFuture ).when( scheduler ).scheduleWithFixedDelay( any( Runnable.class ), eq( 60L ), eq( 60L ), eq( TimeUnit.SECONDS ) );
        service.initialize( );
        verify( scheduler ).scheduleWithFixedDelay( any( Runnable.class ), eq( 60L ), eq( 60L ), eq( TimeUnit.SECONDS ) );
    }

    @Test
    void recordUpload_ShouldStoreRedisValuesAndSetTTL ( ) {
        service.recordUpload( "BAR001", "STUDY001", "SERIES001", "DEVICE001" );
        verify( hashOperations ).put( eq( "barcode:data:SERIES001" ), eq( "barcode" ), eq( "BAR001" ) );
        verify( hashOperations ).put( eq( "barcode:data:SERIES001" ), eq( "studyInstanceUID" ), eq( "STUDY001" ) );
        verify( hashOperations ).put( eq( "barcode:data:SERIES001" ), eq( "deviceSerialNumber" ), eq( "DEVICE001" ) );
        verify( hashOperations ).put( eq( "barcode:data:SERIES001" ), eq( "expiryTime" ), anyString( ) );
        verify( redisTemplate ).expire( eq( "barcode:data:SERIES001" ), eq( Duration.ofMinutes( 15 ) ) );
    }

    @Test
    void processCompletedBarcodes_ShouldSendNotificationForInactiveSeries ( ) throws Exception {
        when( kafkaTopicConfig.getPathqa( ) ).thenReturn( "pathqa-topic" );
        String key = "barcode:data:SERIES001";
        when( redisTemplate.keys( "barcode:data:*" ) ).thenReturn( Set.of( key ) );
        when( hashOperations.get( key, "expiryTime" ) ).thenReturn( String.valueOf( System.currentTimeMillis( ) - 10000 ) );
        when( valueOperations.setIfAbsent( eq( "barcode:lock:SERIES001" ), eq( "LOCKED" ), any( Duration.class ) ) ).thenReturn( true );
        when( hashOperations.get( key, "barcode" ) ).thenReturn( "BAR001" );
        when( hashOperations.get( key, "studyInstanceUID" ) ).thenReturn( "STUDY001" );
        when( hashOperations.get( key, "deviceSerialNumber" ) ).thenReturn( "DEVICE001" );
        ReflectionTestUtils.invokeMethod( service, "processCompletedBarcodes" );
        verify( eventNotificationService, times( 1 ) ).sendEvent( eq( "pathqa-topic" ), eq( "BAR001" ), anyString( ) );

        verify( redisTemplate ).delete( key );
    }

    @Test
    void processCompletedBarcodes_ShouldSkipActiveBarcode ( ) {
        String key = "barcode:data:SERIES001";
        when( redisTemplate.keys( "barcode:data:*" ) ).thenReturn( Set.of( key ) );
        when( hashOperations.get( key, "expiryTime" ) ).thenReturn( String.valueOf( System.currentTimeMillis( ) + 300000 ) );
        ReflectionTestUtils.invokeMethod( service, "processCompletedBarcodes" );
        verify( eventNotificationService, never( ) ).sendEvent( anyString( ), anyString( ), anyString( ) );
    }

    @Test
    void processCompletedBarcodes_ShouldSkipWhenLockNotAcquired ( ) {

        String key = "barcode:data:SERIES001";
        when( redisTemplate.keys( "barcode:data:*" ) ).thenReturn( Set.of( key ) );
        when( hashOperations.get( key, "expiryTime" ) ).thenReturn( String.valueOf( System.currentTimeMillis( ) - 10000 ) );

        when( valueOperations.setIfAbsent( anyString( ), anyString( ), any( Duration.class ) ) ).thenReturn( false );
        ReflectionTestUtils.invokeMethod( service, "processCompletedBarcodes" );
        verify( eventNotificationService, never( ) ).sendEvent( anyString( ), anyString( ), anyString( ) );
    }

    @Test
    void processCompletedBarcodes_ShouldDeleteIncompleteRecord ( ) {
        String key = "barcode:data:SERIES001";
        when( redisTemplate.keys( "barcode:data:*" ) ).thenReturn( Set.of( key ) );
        when( hashOperations.get( key, "expiryTime" ) ).thenReturn( String.valueOf( System.currentTimeMillis( ) - 10000 ) );
        when( valueOperations.setIfAbsent( anyString( ), anyString( ), any( Duration.class ) ) ).thenReturn( true );
        when( hashOperations.get( key, "barcode" ) ).thenReturn( null );

        when( hashOperations.get( key, "studyInstanceUID" ) ).thenReturn( "STUDY001" );
        ReflectionTestUtils.invokeMethod( service, "processCompletedBarcodes" );

        verify( redisTemplate ).delete( key );
        verify( eventNotificationService, never( ) ).sendEvent( anyString( ), anyString( ), anyString( ) );
    }

    @Test
    void processCompletedBarcodes_ShouldHandleNoKeys ( ) {

        when( redisTemplate.keys( "barcode:data:*" ) ).thenReturn( Collections.emptySet( ) );
        ReflectionTestUtils.invokeMethod( service, "processCompletedBarcodes" );
        verify( eventNotificationService, never( ) ).sendEvent( anyString( ), anyString( ), anyString( ) );
    }

    @Test
    void sendKafkaNotification_ShouldPublishKafkaMessage ( ) throws Exception {

        when( kafkaTopicConfig.getPathqa( ) ).thenReturn( "pathqa-topic" );
        Method method = BarcodeUploadTrackerService.class.getDeclaredMethod( "sendKafkaNotification", String.class, String.class, String.class, String.class );
        method.setAccessible( true );
        method.invoke( service, "BAR001", "STUDY001", "SERIES001", "DEVICE001" );

        verify( eventNotificationService ).sendEvent( eq( "pathqa-topic" ), eq( "BAR001" ), anyString( ) );
    }
}
