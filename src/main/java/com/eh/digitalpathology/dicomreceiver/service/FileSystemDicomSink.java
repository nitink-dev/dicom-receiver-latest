package com.eh.digitalpathology.dicomreceiver.service;

import com.eh.digitalpathology.dicomreceiver.api.DicomObjectSink;
import com.eh.digitalpathology.dicomreceiver.config.StorageProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

@Service
@ConditionalOnProperty(name = "app.storage.type", havingValue = "local", matchIfMissing = true)
public class FileSystemDicomSink implements DicomObjectSink {

    private final Path root;

    public FileSystemDicomSink(StorageProperties storageProperties) throws IOException {
        this.root = Paths.get(storageProperties.getLocalPath());
        Files.createDirectories(root);
    }

    @Override
    public String store(Path source, String targetName) throws IOException {
        Path target = root.resolve(targetName);
        Files.createDirectories(target.getParent());
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        return target.toString();
    }

    @Override
    public InputStream openInputStream(String storedRef) throws IOException {
        return Files.newInputStream(Paths.get(storedRef));
    }
}
