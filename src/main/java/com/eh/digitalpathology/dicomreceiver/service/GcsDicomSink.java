package com.eh.digitalpathology.dicomreceiver.service;

import com.eh.digitalpathology.dicomreceiver.api.DicomObjectSink;
import com.eh.digitalpathology.dicomreceiver.config.StorageProperties;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
@ConditionalOnProperty(name = "app.storage.type", havingValue = "gcs")
public class GcsDicomSink implements DicomObjectSink {

    private final Storage storage;
    private final String bucket;

    public GcsDicomSink(StorageProperties storageProperties) {
        this.storage = StorageOptions.getDefaultInstance().getService();
        this.bucket = storageProperties.getGcsBucket();
    }

    @Override
    public String store(Path source, String targetName) throws IOException {
        BlobId blobId = BlobId.of(bucket, targetName);
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId).build();
        storage.createFrom(blobInfo, source);
        Files.deleteIfExists(source);
        return "gs://" + bucket + "/" + targetName;
    }

    @Override
    public void delete(String storedRef) throws IOException {
        String prefix = "gs://" + bucket + "/";
        if (storedRef != null && storedRef.startsWith(prefix)) {
            storage.delete(BlobId.of(bucket, storedRef.substring(prefix.length())));
        }
    }

    @Override
    public InputStream openInputStream(String storedRef) throws IOException {
        String objectName = storedRef.substring(("gs://" + bucket + "/").length());
        Blob blob = storage.get(BlobId.of(bucket, objectName));
        if (blob == null) {
            throw new IOException("Object not found: " + storedRef);
        }
        return Channels.newInputStream(blob.reader());
    }
}
