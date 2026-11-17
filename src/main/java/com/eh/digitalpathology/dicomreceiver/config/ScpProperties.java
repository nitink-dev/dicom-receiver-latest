package com.eh.digitalpathology.dicomreceiver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.scp")
public class ScpProperties {

    private boolean enabled = true;
    private String aeTitle = "DICOMRECV";
    private int port = 11112;
    private String bindAddress = "0.0.0.0";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getAeTitle() { return aeTitle; }
    public void setAeTitle(String aeTitle) { this.aeTitle = aeTitle; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getBindAddress() { return bindAddress; }
    public void setBindAddress(String bindAddress) { this.bindAddress = bindAddress; }
}
