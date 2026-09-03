package com.eh.digitalpathology.dicomreceiver.api;

import com.eh.digitalpathology.dicomreceiver.constants.StorageObjectRef;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public interface DicomObjectSink {

    OutputStream openStream ( String objectName ) throws IOException;

    StorageObjectRef objectRef ( String objectName );

    InputStream openInputStream ( StorageObjectRef ref ) throws IOException;

    void delete ( StorageObjectRef ref );

    StorageObjectRef move ( StorageObjectRef ref, String newPrefix );

}
