package com.eh.digitalpathology.dicomreceiver.service;

import com.eh.digitalpathology.dicomreceiver.config.GcpConfig;
import com.eh.digitalpathology.dicomreceiver.exceptions.HealthcareApiException;
import com.eh.digitalpathology.dicomreceiver.util.GCPUtils;
import org.apache.http.HttpStatus;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DicomHealthcareApiServiceTest {

    @Mock
    private GcpConfig gcpConfig;

    @Mock
    private CloseableHttpClient httpClient;

    @Mock
    private CloseableHttpResponse response;

    @Mock
    private StatusLine statusLine;

    private DicomHealthcareApiService service;

    private Attributes fileMeta;

    private Attributes dataset;

    @BeforeEach
    void setUp ( ) {

        service = new DicomHealthcareApiService( gcpConfig, httpClient );
        fileMeta = new Attributes( );

        dataset = new Attributes( );
        dataset.setString( Tag.SOPInstanceUID, VR.UI, "1.2.3.4" );

        dataset.setString( Tag.StudyInstanceUID, VR.UI, "1.2.840.study" );
        dataset.setString( Tag.SeriesInstanceUID, VR.UI, "1.2.840.series" );
    }


    @Test
    void storeEnrichedDicomInstance_shouldUploadSuccessfully ( ) throws Exception {

        try ( MockedStatic< GCPUtils > mocked = Mockito.mockStatic( GCPUtils.class ) ) {
            mocked.when( ( ) -> GCPUtils.getAccessToken( gcpConfig ) ).thenReturn( "token" );

            when( gcpConfig.getDicomWebUrl( ) ).thenReturn( "http://localhost" );
            when( httpClient.execute( any( HttpPost.class ) ) ).thenReturn( response );

            when( response.getStatusLine( ) ).thenReturn( statusLine );
            when( statusLine.getStatusCode( ) ).thenReturn( HttpStatus.SC_OK );

            assertDoesNotThrow( ( ) -> service.storeDicomInstances( fileMeta, dataset, "store" ) );
            verify( httpClient ).execute( any( HttpPost.class ) );
        }
    }

    @Test
    void storeEnrichedDicomInstance_shouldThrowExceptionForBadStatus ( ) throws Exception {

        try ( MockedStatic< GCPUtils > mocked = Mockito.mockStatic( GCPUtils.class ) ) {

            mocked.when( ( ) -> GCPUtils.getAccessToken( gcpConfig ) ).thenReturn( "token" );
            when( gcpConfig.getDicomWebUrl( ) ).thenReturn( "http://localhost" );

            when( httpClient.execute( any( HttpPost.class ) ) ).thenReturn( response );

            when( response.getStatusLine( ) ).thenReturn( statusLine );
            when( statusLine.getStatusCode( ) ).thenReturn( HttpStatus.SC_BAD_REQUEST );

            when( statusLine.getReasonPhrase( ) ).thenReturn( "Bad Request" );
            HealthcareApiException exception = assertThrows( HealthcareApiException.class, ( ) -> service.storeDicomInstances( fileMeta, dataset, "store" ) );
            assertTrue( exception.getMessage( ).contains( "Failed storing SOPInstanceUID" ) );
        }
    }

    @Test
    void storeEnrichedDicomInstance_shouldThrowExceptionWhenHttpClientFails ( ) throws Exception {

        try ( MockedStatic< GCPUtils > mocked = Mockito.mockStatic( GCPUtils.class ) ) {

            mocked.when( ( ) -> GCPUtils.getAccessToken( gcpConfig ) ).thenReturn( "token" );
            when( gcpConfig.getDicomWebUrl( ) ).thenReturn( "http://localhost" );

            when( httpClient.execute( any( HttpPost.class ) ) ).thenThrow( new IOException( "network failure" ) );

            HealthcareApiException exception = assertThrows( HealthcareApiException.class, ( ) -> service.storeDicomInstances( fileMeta, dataset, "store" ) );
            assertTrue( exception.getMessage( ).contains( "Failed during STOW-RS upload" ) );
        }
    }

    @Test
    void storeEnrichedDicomInstance_shouldDeleteAndRetryOnConflict ( ) throws Exception {

        try ( MockedStatic< GCPUtils > mocked = Mockito.mockStatic( GCPUtils.class ) ) {

            mocked.when( ( ) -> GCPUtils.getAccessToken( gcpConfig ) ).thenReturn( "token" );
            when( gcpConfig.getDicomWebUrl( ) ).thenReturn( "http://localhost" );

            CloseableHttpResponse uploadConflict = mock( CloseableHttpResponse.class );

            StatusLine conflictStatus = mock( StatusLine.class );
            when( uploadConflict.getStatusLine( ) ).thenReturn( conflictStatus );

            when( conflictStatus.getStatusCode( ) ).thenReturn( HttpStatus.SC_CONFLICT );
            CloseableHttpResponse deleteResponse = mock( CloseableHttpResponse.class );

            StatusLine deleteStatus = mock( StatusLine.class );
            when( deleteResponse.getStatusLine( ) ).thenReturn( deleteStatus );

            when( deleteStatus.getStatusCode( ) ).thenReturn( HttpStatus.SC_NO_CONTENT );
            CloseableHttpResponse uploadSuccess = mock( CloseableHttpResponse.class );

            StatusLine successStatus = mock( StatusLine.class );
            when( uploadSuccess.getStatusLine( ) ).thenReturn( successStatus );

            when( successStatus.getStatusCode( ) ).thenReturn( HttpStatus.SC_OK );
            when( httpClient.execute( any( HttpPost.class ) ) ).thenReturn( uploadConflict ).thenReturn( uploadSuccess );

            when( httpClient.execute( any( HttpDelete.class ) ) ).thenReturn( deleteResponse );
            assertDoesNotThrow( ( ) -> service.storeDicomInstances( fileMeta, dataset, "store" ) );
            verify( httpClient, times( 2 ) ).execute( any( HttpPost.class ) );
            verify( httpClient ).execute( any( HttpDelete.class ) );
        }
    }

    @Test
    void storeEnrichedDicomInstance_shouldThrowExceptionWhenDeleteFails ( ) throws Exception {

        try ( MockedStatic< GCPUtils > mocked = Mockito.mockStatic( GCPUtils.class ) ) {

            mocked.when( ( ) -> GCPUtils.getAccessToken( gcpConfig ) ).thenReturn( "token" );
            when( gcpConfig.getDicomWebUrl( ) ).thenReturn( "http://localhost" );

            CloseableHttpResponse conflictResp = mock( CloseableHttpResponse.class );

            StatusLine conflictStatus = mock( StatusLine.class );
            when( conflictResp.getStatusLine( ) ).thenReturn( conflictStatus );

            when( conflictStatus.getStatusCode( ) ).thenReturn( HttpStatus.SC_CONFLICT );
            CloseableHttpResponse deleteResp = mock( CloseableHttpResponse.class );

            StatusLine deleteStatus = mock( StatusLine.class );
            when( deleteResp.getStatusLine( ) ).thenReturn( deleteStatus );

            when( deleteStatus.getStatusCode( ) ).thenReturn( HttpStatus.SC_BAD_REQUEST );
            when( httpClient.execute( any( HttpPost.class ) ) ).thenReturn( conflictResp );

            when( httpClient.execute( any( HttpDelete.class ) ) ).thenReturn( deleteResp );
            HealthcareApiException exception = assertThrows( HealthcareApiException.class, ( ) -> service.storeDicomInstances( fileMeta, dataset, "store" ) );

            assertTrue( exception.getMessage( ).contains( "Failed deleting SOPInstanceUID" ) );
        }
    }

    @Test
    void storeEnrichedDicomInstance_shouldWorkWithoutAccessToken ( ) throws Exception {

        try ( MockedStatic< GCPUtils > mocked = Mockito.mockStatic( GCPUtils.class ) ) {

            mocked.when( ( ) -> GCPUtils.getAccessToken( gcpConfig ) ).thenReturn( null );
            when( gcpConfig.getDicomWebUrl( ) ).thenReturn( "http://localhost" );

            when( httpClient.execute( any( HttpPost.class ) ) ).thenReturn( response );

            when( response.getStatusLine( ) ).thenReturn( statusLine );
            when( statusLine.getStatusCode( ) ).thenReturn( HttpStatus.SC_OK );

            assertDoesNotThrow( ( ) -> service.storeDicomInstances( fileMeta, dataset, "store" ) );
        }
    }


}
