package com.eh.digitalpathology.dicomreceiver.service;

import com.eh.digitalpathology.dicomreceiver.api.DicomObjectSink;
import com.eh.digitalpathology.dicomreceiver.entity.TransferRecord;
import com.eh.digitalpathology.dicomreceiver.model.DicomMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;

/**
 * Main business workflow: store the incoming file, extract its metadata, and record
 * the transfer so it shows up in the monitoring dashboard. Files that aren't valid
 * DICOM are moved aside into a "rejected" folder instead of being processed.
 */
@Service
public class FileProcessingService {

    private static final Logger log = LoggerFactory.getLogger(FileProcessingService.class);

    private final DicomObjectSink sink;
    private final DicomExtractorService extractorService;
    private final DatabaseService databaseService;

    public FileProcessingService(DicomObjectSink sink, DicomExtractorService extractorService,
                                  DatabaseService databaseService) {
        this.sink = sink;
        this.extractorService = extractorService;
        this.databaseService = databaseService;
    }

    public void processFile(Path file) {
        String fileName = file.getFileName().toString();
        try {
            DicomMetadata metadata;
            try (InputStream in = Files.newInputStream(file)) {
                metadata = extractorService.extract(in);
            }

            String storedPath = sink.store(file, fileName);

            TransferRecord record = new TransferRecord();
            record.setFileName(fileName);
            record.setSeriesInstanceUid(metadata.seriesInstanceUid());
            record.setSopInstanceUid(metadata.sopInstanceUid());
            record.setStudyInstanceUid(metadata.studyInstanceUid());
            record.setPatientId(metadata.patientId());
            record.setPatientName(metadata.patientName());
            record.setModality(metadata.modality());
            record.setManufacturer(metadata.manufacturer());
            record.setManufacturerModelName(metadata.manufacturerModelName());
            record.setDeviceSerialNumber(metadata.deviceSerialNumber());
            record.setStoragePath(storedPath);
            record.setTransactionDateTime(LocalDateTime.now());

            databaseService.save(record);
            log.info("Processed {} -> {}", fileName, storedPath);
        } catch (Exception e) {
            log.warn("Rejecting {}: {}", fileName, e.getMessage());
            reject(file);
        }
    }

    private void reject(Path file) {
        try {
            Path rejectedDir = file.getParent().resolve("rejected");
            Files.createDirectories(rejectedDir);
            Files.move(file, rejectedDir.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException moveFailure) {
            log.error("Failed to move rejected file {}: {}", file, moveFailure.getMessage());
        }
    }
}
