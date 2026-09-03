package com.eh.digitalpathology.dicomreceiver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "storescp")
@RefreshScope
public class StoreScpConfig {
    private String aetitle;
    private int port;

    public String getAetitle ( ) {
        return aetitle;
    }

    public void setAetitle ( String aetitle ) {
        this.aetitle = aetitle;
    }

    public int getPort ( ) {
        return port;
    }

    public void setPort ( int port ) {
        this.port = port;
    }

}

