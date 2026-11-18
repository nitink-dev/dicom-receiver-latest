package com.eh.digitalpathology.dicomreceiver.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * Storage abstraction for managed DICOM files. Implementations move a file out of the
 * watch directory into durable storage (local disk or GCS) and hand back a reference
 * that can later be used to re-open the stored content.
 */
public interface DicomObjectSink {

    String store(Path source, String targetName) throws IOException;

    InputStream openInputStream(String storedRef) throws IOException;

    /** Removes a previously stored object. Used when a duplicate SOP replaces an older copy. */
    default void delete(String storedRef) throws IOException {
    }
}
