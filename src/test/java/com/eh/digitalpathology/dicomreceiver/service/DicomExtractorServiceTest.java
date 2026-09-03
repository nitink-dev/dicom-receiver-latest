package com.eh.digitalpathology.dicomreceiver.service;


import com.eh.digitalpathology.dicomreceiver.api.DicomObjectSink;
import com.eh.digitalpathology.dicomreceiver.config.GcpConfig;
import com.eh.digitalpathology.dicomreceiver.config.KafkaTopicConfig;
import com.eh.digitalpathology.dicomreceiver.constants.StorageObjectRef;
import com.eh.digitalpathology.dicomreceiver.enums.StorageType;
import com.eh.digitalpathology.dicomreceiver.exceptions.DicomAttributesException;
import com.eh.digitalpathology.dicomreceiver.model.DicomDirDocument;
import com.eh.digitalpathology.dicomreceiver.model.DicomRequestDBObject;
import com.eh.digitalpathology.dicomreceiver.model.SlideScanner;
import com.github.benmanes.caffeine.cache.Cache;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;


@ExtendWith( MockitoExtension.class )
class DicomExtractorServiceTest {


    @Mock
    private EventNotificationService eventNotificationService;

    @Mock
    private DatabaseService dbService;

    @Mock
    private DicomHealthcareApiService dicomHealthcareApiClient;

    @Mock
    private DicomDirService dicomDirService;

    @Mock
    private Cache< String, String > studyBarcodeCache;

    @Mock
    private RedisTemplate< String, String > redisTemplate;

    @Mock
    private ValueOperations< String, String > valueOperations;

    @Mock
    private BarcodeUploadTrackerService barcodeUploadTrackerService;

    @Mock
    private KafkaTopicConfig kafkaTopicConfig;

    @Mock
    private GcpConfig gcpConfig;

    @Mock
    private DicomObjectSink dicomObjectSink;

    @Mock
    private SeriesUploadTrackerService seriesUploadTrackerService;

    @InjectMocks
    private DicomExtractorService service;

    private StorageObjectRef ref;
    private DicomDirDocument dicomDirDocument;

    @BeforeEach
    void setup ( ) {
        ref = new StorageObjectRef( StorageType.GCS, "bucket", "1.2.3.4.dcm" );

        lenient( ).when( kafkaTopicConfig.getEmail( ) ).thenReturn( "email-topic" );
        lenient( ).when( gcpConfig.getResearchStoreUrl( ) ).thenReturn( "research-url" );
        lenient( ).when( gcpConfig.getPathqaStoreUrl( ) ).thenReturn( "pathqa-url" );

        dicomDirDocument = mock( DicomDirDocument.class );
        lenient( ).when( dicomDirDocument.studyId( ) ).thenReturn( "STUDY-1" );
        lenient().when( redisTemplate.opsForValue( ) ).thenReturn( valueOperations );
        lenient().when( valueOperations.setIfAbsent( anyString( ), any( ), anyLong( ), any( TimeUnit.class ) ) ).thenReturn( true );
    }

    /* ------------------------------------------------------------
     * Helper – SAFE constructor mocking
     * ------------------------------------------------------------ */
    private MockedConstruction< DicomInputStream > mockDicomStream ( Attributes attrs, Attributes fmi ) throws Exception {

        when( dicomObjectSink.openInputStream( ref ) ).thenReturn( new ByteArrayInputStream( new byte[ 0 ] ) );

        return Mockito.mockConstruction( DicomInputStream.class, ( mock, context ) -> {
            when( mock.readDataset( ) ).thenReturn( attrs );
            when( mock.readFileMetaInformation( ) ).thenReturn( fmi );
        } );
    }

    /* ------------------------------------------------------------
     * 1) Scanner NOT connected → move
     * ------------------------------------------------------------ */
    @Test
    void extract_scannerNotConnected_shouldMoveAndReturnNull ( ) throws Exception {

        Attributes attrs = new Attributes( );
        attrs.setString( Tag.SOPInstanceUID, VR.UI, "1" );
        attrs.setString( Tag.SeriesInstanceUID, VR.UI, "2" );
        attrs.setString( Tag.StudyInstanceUID, VR.UI, "3" );
        attrs.setString( Tag.BarcodeValue, VR.LO, "BC-123" );

        Attributes fmi = new Attributes( );

        SlideScanner scanner = mock( SlideScanner.class );
        when( scanner.connected( ) ).thenReturn( false );
        when( dbService.fetchScannerByDeviceSerialNumber( null ) ).thenReturn( scanner );

        try ( MockedConstruction< DicomInputStream > ignored = mockDicomStream( attrs, fmi ) ) {

            DicomRequestDBObject result = service.extract( ref );

            assertNull( result );
            verify( dicomObjectSink ).move( ref, null + "/3/2" );
        }
    }

    /* ------------------------------------------------------------
     * 2) Research scanner → store + delete
     * ------------------------------------------------------------ */
    @Test
    void extract_researchScanner_shouldStoreAndDelete ( ) throws Exception {

        Attributes attrs = new Attributes( );
        attrs.setString( Tag.SOPInstanceUID, VR.UI, "1" );
        attrs.setString( Tag.SeriesInstanceUID, VR.UI, "2" );
        attrs.setString( Tag.StudyInstanceUID, VR.UI, "3" );
        attrs.setString( Tag.BarcodeValue, VR.LO, "BC-123" );

        Attributes fmi = new Attributes( );

        SlideScanner scanner = mock( SlideScanner.class );
        when( scanner.connected( ) ).thenReturn( true );
        when( scanner.research( ) ).thenReturn( true );
        when( dbService.fetchScannerByDeviceSerialNumber( null ) ).thenReturn( scanner );

        try ( MockedConstruction< DicomInputStream > ignored = mockDicomStream( attrs, fmi ) ) {

            DicomRequestDBObject result = service.extract( ref );

            assertNull( result );
            verify( dicomHealthcareApiClient ).storeDicomInstances( fmi, attrs, "research-url" );
            verify( seriesUploadTrackerService ).recordUpload( "3", "2" );
            verify( dicomObjectSink ).delete( ref );
        }
    }

    /* ------------------------------------------------------------
     * 3) Barcode exists → PathQA store
     * ------------------------------------------------------------ */
    @Test
    void extract_barcodeExists_shouldStorePathQaAndReturnNull ( ) throws Exception {

        Attributes attrs = new Attributes( );
        attrs.setString( Tag.SOPInstanceUID, VR.UI, "1" );
        attrs.setString( Tag.SeriesInstanceUID, VR.UI, "2" );
        attrs.setString( Tag.StudyInstanceUID, VR.UI, "3" );
        attrs.setString( Tag.BarcodeValue, VR.LO, "BC-123" );

        Attributes fmi = new Attributes( );

        SlideScanner scanner = mock( SlideScanner.class );
        when( scanner.connected( ) ).thenReturn( true );
        when( scanner.research( ) ).thenReturn( false );
        when( dbService.fetchScannerByDeviceSerialNumber( null ) ).thenReturn( scanner );
        when( dbService.isBarcodeExists( "BC-123" ) ).thenReturn( true );

        try ( MockedConstruction< DicomInputStream > ignored = mockDicomStream( attrs, fmi ) ) {

            DicomRequestDBObject result = service.extract( ref );

            assertNull( result );
            verify( dicomHealthcareApiClient ).storeDicomInstances( fmi, attrs, "pathqa-url" );
            verify( barcodeUploadTrackerService ).recordUpload( "BC-123", "3", "2", null );
        }
    }

    /* ------------------------------------------------------------
     * 4) Happy path → returns DBO
     * ------------------------------------------------------------ */
    @Test
    void extract_happyPath_shouldReturnDBObject ( ) throws Exception {

        Attributes attrs = new Attributes( );
        attrs.setString( Tag.SOPInstanceUID, VR.UI, "1" );
        attrs.setString( Tag.SeriesInstanceUID, VR.UI, "2" );
        attrs.setString( Tag.StudyInstanceUID, VR.UI, "3" );
        attrs.setString( Tag.BarcodeValue, VR.LO, "BC-123" );

        Attributes fmi = new Attributes( );

        SlideScanner scanner = mock( SlideScanner.class );
        when( scanner.connected( ) ).thenReturn( true );
        when( scanner.research( ) ).thenReturn( false );
        when( dbService.fetchScannerByDeviceSerialNumber( null ) ).thenReturn( scanner );
        when( dbService.isBarcodeExists( "BC-123" ) ).thenReturn( false );
        when( valueOperations.get( anyString( ) ) ).thenReturn( null );
        when( redisTemplate.delete( anyString( ) ) ).thenReturn( true );

        try ( MockedConstruction< DicomInputStream > ignored = mockDicomStream( attrs, fmi ) ) {

            DicomRequestDBObject dbo = service.extract( ref );

            assertNotNull( dbo );
            assertEquals( "BC-123", dbo.getBarcode( ) );
            verify( studyBarcodeCache ).put( "3", "BC-123" );
        }
    }

    /* ------------------------------------------------------------
     * 5) Missing barcode → notify + delete + exception
     * ------------------------------------------------------------ */
    @Test
    void extract_missingBarcode_shouldNotifyAndDelete ( ) throws Exception {

        Attributes attrs = new Attributes( );
        attrs.setString( Tag.SOPInstanceUID, VR.UI, "1" );
        attrs.setString( Tag.SeriesInstanceUID, VR.UI, "2" );
        attrs.setString( Tag.StudyInstanceUID, VR.UI, "3" );
        // Barcode missing

        Attributes fmi = new Attributes( );

        SlideScanner scanner = mock( SlideScanner.class );
        when( scanner.connected( ) ).thenReturn( true );
        when( dbService.fetchScannerByDeviceSerialNumber( null ) ).thenReturn( scanner );

        try ( MockedConstruction< DicomInputStream > ignored = mockDicomStream( attrs, fmi ) ) {

            assertThrows( DicomAttributesException.class, ( ) -> service.extract( ref ) );

            verify( eventNotificationService ).sendEvent( "email-topic", "MISSING_BARCODE", null );
            verify( dicomObjectSink ).delete( ref );
        }
    }

    /* ------------------------------------------------------------
     * 6) DICOMDIR handling (no constructor mocking needed)
     * ------------------------------------------------------------ */

    @Test
    void handleDicomDir_whenMetadataIsNull_shouldExitGracefully ( ) throws IOException {

        lenient( ).when( dicomDirService.fetchMetaData( any( ), any( ) ) ).thenReturn( null );

        service.handleDicomDir( new ByteArrayInputStream( new byte[]{ 1, 2, 3 } ), "DICOMDIR" );

        verify( dicomDirService, never( ) ).fetchAndStoreMetaData( any( ) );
    }

    @Test
    void handleDicomDir_whenBarcodeExistsInDb_shouldSkipStore ( ) throws IOException {

        lenient( ).when( dicomDirService.fetchMetaData( any( ), any( ) ) ).thenReturn( dicomDirDocument );
        lenient( ).when( studyBarcodeCache.getIfPresent( "STUDY-1" ) ).thenReturn( "BC-123" );
        lenient( ).when( dbService.isBarcodeExists( "BC-123" ) ).thenReturn( true );

        service.handleDicomDir( new ByteArrayInputStream( new byte[]{ 1, 2, 3 } ), "DICOMDIR" );

        verify( dicomDirService, never( ) ).fetchAndStoreMetaData( any( ) );
    }

    @Test
    void handleDicomDir_whenBarcodeInCacheNotInDb_shouldStore ( ) throws IOException {

        lenient( ).when( dicomDirService.fetchMetaData( any( ), any( ) ) ).thenReturn( dicomDirDocument );
        lenient( ).when( studyBarcodeCache.getIfPresent( "STUDY-1" ) ).thenReturn( "BC-123" );
        lenient( ).when( dbService.isBarcodeExists( "BC-123" ) ).thenReturn( false );

        service.handleDicomDir( new ByteArrayInputStream( new byte[]{ 1, 2, 3 } ), "DICOMDIR" );

        verify( dicomDirService, never( ) ).fetchAndStoreMetaData( eq( dicomDirDocument ) );
    }


}