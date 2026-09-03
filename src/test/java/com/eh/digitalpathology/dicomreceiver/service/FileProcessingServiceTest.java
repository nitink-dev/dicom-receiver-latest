package com.eh.digitalpathology.dicomreceiver.service;

import com.eh.digitalpathology.dicomreceiver.api.DicomObjectSink;
import com.eh.digitalpathology.dicomreceiver.config.KafkaTopicConfig;
import com.eh.digitalpathology.dicomreceiver.constants.StorageObjectRef;
import com.eh.digitalpathology.dicomreceiver.enums.StorageType;
import com.eh.digitalpathology.dicomreceiver.model.DicomRequestDBObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

@ExtendWith( MockitoExtension.class )
class FileProcessingServiceTest {


    @Mock
    private DicomExtractorService dicomExtractorService;

    @Mock
    private DatabaseService databaseService;

    @Mock
    private EventNotificationService eventNotificationService;

    @Mock
    private KafkaTopicConfig kafkaTopicConfig;

    @Mock
    private DicomObjectSink sink;

    @InjectMocks
    private FileProcessingService service;

    private StorageObjectRef ref;

    @BeforeEach
    void setUp ( ) {
        ref = new StorageObjectRef( StorageType.GCS, "bucket", "1.2.3.4.dcm" );

        lenient().when( kafkaTopicConfig.getDcm( ) ).thenReturn( "receiver-topic" );
        lenient().when( kafkaTopicConfig.getScanProgress( ) ).thenReturn( "scan-progress-topic" );
    }

    @Test
    void processFile_whenSvsFile_shouldDoNothing ( ) {
        StorageObjectRef svsRef = new StorageObjectRef( StorageType.GCS, "bucket", "slide.svs" );
        service.processFile( svsRef );
        verifyNoInteractions( dicomExtractorService, databaseService, eventNotificationService );
    }

    @Test
    void processFile_whenExtractorReturnsNull_shouldNotInsertOrNotify ( ) throws Exception {
        when( dicomExtractorService.extract( ref ) ).thenReturn( null );
        service.processFile( ref );
        verify( dicomExtractorService ).extract( ref );
        verifyNoInteractions( databaseService, eventNotificationService, sink );
    }

    @Test
    void processFile_successfulFlow_shouldInsertAndSendKafkaEvents ( ) throws Exception {
        DicomRequestDBObject dbo = new DicomRequestDBObject( );
        dbo.setBarcode( "BC123" );
        dbo.setSopInstanceUid( "1.2.3" );
        dbo.setSeriesInstanceUid( "4.5.6" );
        dbo.setDeviceSerialNumber( "DEV-001" );

        when( dicomExtractorService.extract( ref ) ).thenReturn( dbo );
        when( databaseService.insertDicomData( dbo, FileProcessingService.DICOM_RECEIVER ) ).thenReturn( "success" );

        service.processFile( ref );

        verify( databaseService ).insertDicomData( dbo, FileProcessingService.DICOM_RECEIVER );

        verify( eventNotificationService, times( 2 ) ).sendEvent( anyString( ), anyString( ), anyString( ) );

        verify( sink, never( ) ).delete( any( ) );
    }

    @Test
    void processFile_whenDbInsertFails_shouldNotSendKafka ( ) throws Exception {
        DicomRequestDBObject dbo = new DicomRequestDBObject( );
        dbo.setBarcode( "BC123" );

        when( dicomExtractorService.extract( ref ) ).thenReturn( dbo );
        when( databaseService.insertDicomData( any( ), any( ) ) ).thenReturn( "failure" );

        service.processFile( ref );

        verify( databaseService ).insertDicomData( any( ), any( ) );
        verifyNoInteractions( eventNotificationService );
        verify( sink, never( ) ).delete( any( ) );
    }

    @Test
    void processFile_whenExceptionOccurs_shouldDeleteFromSink ( ) throws Exception {
        when( dicomExtractorService.extract( ref ) ).thenThrow( new RuntimeException( "boom" ) );

        service.processFile( ref );

        verify( sink ).delete( ref );
        verifyNoInteractions( eventNotificationService );
    }

}