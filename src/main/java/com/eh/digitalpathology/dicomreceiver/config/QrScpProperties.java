package com.eh.digitalpathology.dicomreceiver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "app.qr")
public class QrScpProperties {

    private boolean enabled = true;
    private String aeTitle = "DCMQRSCP";
    private int port = 11112;
    private String bindAddress = "0.0.0.0";
    /** C-MOVE destinations: AE title -> "host:port". */
    private Map<String, String> moveDestinations = new HashMap<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getAeTitle() { return aeTitle; }
    public void setAeTitle(String aeTitle) { this.aeTitle = aeTitle; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getBindAddress() { return bindAddress; }
    public void setBindAddress(String bindAddress) { this.bindAddress = bindAddress; }
    public Map<String, String> getMoveDestinations() { return moveDestinations; }
    public void setMoveDestinations(Map<String, String> moveDestinations) { this.moveDestinations = moveDestinations; }
}
