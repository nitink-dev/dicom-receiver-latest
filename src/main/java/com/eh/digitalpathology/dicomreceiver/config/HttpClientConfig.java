package com.eh.digitalpathology.dicomreceiver.config;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class HttpClientConfig {

    @Bean
    public CloseableHttpClient closeableHttpClient ( ) {
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager( );
        connectionManager.setMaxTotal( 100 );
        connectionManager.setDefaultMaxPerRoute( 50 );
        RequestConfig config = RequestConfig.custom( ).setSocketTimeout( 300000 ).setConnectTimeout( 300000 ).setConnectionRequestTimeout( 300000 ).build( );

        return HttpClients.custom( ).setConnectionManager( connectionManager ).setDefaultRequestConfig( config ).disableAutomaticRetries( ).disableRedirectHandling( ).evictExpiredConnections( ).evictIdleConnections( 5, TimeUnit.MINUTES ).build( );
    }

}
