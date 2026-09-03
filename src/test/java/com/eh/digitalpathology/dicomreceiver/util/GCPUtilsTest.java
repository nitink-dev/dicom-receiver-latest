package com.eh.digitalpathology.dicomreceiver.util;

import com.eh.digitalpathology.dicomreceiver.config.GcpConfig;
import com.eh.digitalpathology.dicomreceiver.exceptions.HealthcareApiException;
import com.google.api.services.healthcare.v1.CloudHealthcareScopes;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GCPUtilsTest {

    private GcpConfig gcpConfig;

    @BeforeEach
    void setUp ( ) {
        gcpConfig = mock( GcpConfig.class );
    }

    @Test
    void testGetAccessToken_WhenCredentialsBlank_ShouldReturnEmptyString ( ) throws Exception {
        when( gcpConfig.getCreds( ) ).thenReturn( "   " );


        GoogleCredentials mockedCreds = mockCredentialsWithToken( "ya29.test-blank" );
        try ( MockedStatic< GoogleCredentials > staticMock = mockStatic( GoogleCredentials.class ) ) {
            staticMock.when( GoogleCredentials::getApplicationDefault ).thenReturn( mockedCreds );

            String result = GCPUtils.getAccessToken( gcpConfig );

            assertEquals( "ya29.test-blank", result );
        }
        verify( gcpConfig, times( 1 ) ).getCreds( );
    }

    @Test
    void testGetAccessToken_WhenCredentialsValid_ShouldReturnMockedToken ( ) throws Exception {
        when( gcpConfig.getCreds( ) ).thenReturn( "{\"mock\":\"json\"}" );

        // Mock static method GoogleCredentials.fromStream()
        try ( MockedStatic< GoogleCredentials > mockStatic = mockStatic( GoogleCredentials.class ) ) {

            GoogleCredentials mockCredentials = mock( GoogleCredentials.class );
            AccessToken mockAccessToken = mock( AccessToken.class );

            mockStatic.when( ( ) -> GoogleCredentials.fromStream( any( ByteArrayInputStream.class ) ) ).thenReturn( mockCredentials );

            when( mockCredentials.createScoped( Collections.singleton( CloudHealthcareScopes.CLOUD_PLATFORM ) ) ).thenReturn( mockCredentials );
            doNothing( ).when( mockCredentials ).refreshIfExpired( );
            when( mockCredentials.refreshAccessToken( ) ).thenReturn( mockAccessToken );
            when( mockAccessToken.getTokenValue( ) ).thenReturn( "MOCK_TOKEN_123" );

            String token = GCPUtils.getAccessToken( gcpConfig );

            assertEquals( "MOCK_TOKEN_123", token );
            verify( mockCredentials, times( 1 ) ).refreshIfExpired( );
            verify( mockCredentials, times( 1 ) ).refreshAccessToken( );
        }
    }

    @Test
    void testGetAccessToken_WhenExceptionOccurs_ShouldThrowHealthcareApiException ( ) {
        when( gcpConfig.getCreds( ) ).thenReturn( "invalid-json" );

        try ( MockedStatic< GoogleCredentials > mockStatic = mockStatic( GoogleCredentials.class ) ) {
            mockStatic.when( ( ) -> GoogleCredentials.fromStream( any( ByteArrayInputStream.class ) ) ).thenThrow( new RuntimeException( "Mocked failure" ) );

            HealthcareApiException exception = assertThrows( HealthcareApiException.class, ( ) -> GCPUtils.getAccessToken( gcpConfig ) );

            assertTrue( exception.getMessage( ).contains( "Failed to get access token" ) );
        }
    }

    private GoogleCredentials mockCredentialsWithToken ( String tokenValue ) throws Exception {
        GoogleCredentials creds = mock( GoogleCredentials.class );

        // If your code calls createScopedRequired, you can control it:
        lenient( ).when( creds.createScopedRequired( ) ).thenReturn( false );

        // If createScoped(...) is called, just return the same mock
        when( creds.createScoped( anyCollection( ) ) ).thenReturn( creds );

        // Do nothing on refresh
        doNothing( ).when( creds ).refreshIfExpired( );

        // Return a fake access token valid for 1 hour
        AccessToken accessToken = new AccessToken( tokenValue, new Date( System.currentTimeMillis( ) + 3_600_000 ) );
        when( creds.refreshAccessToken( ) ).thenReturn( accessToken );

        return creds;
    }
}
