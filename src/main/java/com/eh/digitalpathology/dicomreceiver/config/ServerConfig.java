package com.eh.digitalpathology.dicomreceiver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "file.server")
@RefreshScope
public class ServerConfig {
    private String path;

    public String getPath ( ) {
        return path;
    }

    public void setPath ( String path ) {
        this.path = path;
    }
}
