package com.eh.digitalpathology.dicomreceiver.config;

import com.google.api.gax.retrying.RetrySettings;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.http.HttpTransportOptions;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Configuration
public class StorageConfig {

    private final GcpConfig gcpConfig;

    public StorageConfig ( GcpConfig gcpConfig ) {
        this.gcpConfig = gcpConfig;
    }

    @Bean
    public Storage storage ( ) throws IOException {
        String credsJson = gcpConfig.getCreds( );
        HttpTransportOptions transportOptions = HttpTransportOptions.newBuilder( ).setConnectTimeout( 120_000 )   // 60 sec
                .setReadTimeout( 600_000 )     // ✅ 5 minutes
                .build( );
        var retrySettings = RetrySettings.newBuilder( ).setInitialRetryDelayDuration( Duration.ofSeconds( 2 ) ).setRetryDelayMultiplier( 1.5 ).setMaxRetryDelayDuration( Duration.ofSeconds( 30 ) ).setTotalTimeoutDuration( Duration.ofMinutes( 30 ) )   // IMPORTANT
                .build( );

        StorageOptions.Builder builder = StorageOptions.newBuilder( ).setRetrySettings( retrySettings ).setTransportOptions( transportOptions );

        if ( credsJson == null || credsJson.isBlank( ) ) {
            return builder.build( ).getService( );
        }
        try ( ByteArrayInputStream in = new ByteArrayInputStream( credsJson.getBytes( StandardCharsets.UTF_8 ) ) ) {
            ServiceAccountCredentials sac = ServiceAccountCredentials.fromStream( in );
            return builder.setCredentials( sac ).build( ).getService( );
        }
    }
}
