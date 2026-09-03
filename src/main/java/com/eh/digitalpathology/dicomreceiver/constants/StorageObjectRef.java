package com.eh.digitalpathology.dicomreceiver.constants;

import com.eh.digitalpathology.dicomreceiver.enums.StorageType;

import java.nio.file.Paths;

public record StorageObjectRef(StorageType type, String rootPath, String objectName) {
    public String uri() {
        return switch (type) {
            case FILESYSTEM -> Paths.get(rootPath, objectName).toString();
            case GCS -> "gs://" + rootPath + "/" + objectName;
        };
    }
}
