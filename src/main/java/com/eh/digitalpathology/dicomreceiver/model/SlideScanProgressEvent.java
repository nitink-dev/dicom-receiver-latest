package com.eh.digitalpathology.dicomreceiver.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SlideScanProgressEvent(String slideBarcode, String seriesId, String deviceSerialNumber, String scanStatus,
                                     String sourceService, String event) {

    private static final String DEFAULT_SOURCE = "eh-dp-dicom-receiver";

    public SlideScanProgressEvent(String slideBarcode, String seriesId, String deviceSerialNumber, String scanStatus) {
        this(slideBarcode, seriesId, deviceSerialNumber, scanStatus, DEFAULT_SOURCE, null);
    }

    public SlideScanProgressEvent(String event) {
        this(null, null, null, null, DEFAULT_SOURCE, event);
    }
}
