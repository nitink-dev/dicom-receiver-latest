package com.eh.digitalpathology.dicomreceiver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.storage")
public class StorageProperties {

    /** "local" or "gcs". */
    private String type = "local";
    private String localPath = "./data/storage";
    private String gcsBucket = "";

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getLocalPath() {
        return localPath;
    }

    public void setLocalPath(String localPath) {
        this.localPath = localPath;
    }

    public String getGcsBucket() {
        return gcsBucket;
    }

    public void setGcsBucket(String gcsBucket) {
        this.gcsBucket = gcsBucket;
    }
}
