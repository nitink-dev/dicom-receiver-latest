package com.eh.digitalpathology.dicomreceiver.service;

import com.eh.digitalpathology.dicomreceiver.api.DicomObjectSink;
import com.eh.digitalpathology.dicomreceiver.entity.TransferRecord;
import com.eh.digitalpathology.dicomreceiver.model.DicomMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FileProcessingServiceTest {

    @Test
    void storesFileAndSavesTransferRecordOnSuccess(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("sample.dcm");
        Files.writeString(file, "not-real-dicom-bytes");

        DicomObjectSink sink = mock(DicomObjectSink.class);
        DicomExtractorService extractor = mock(DicomExtractorService.class);
        DatabaseService databaseService = mock(DatabaseService.class);

        DicomMetadata metadata = new DicomMetadata(
                "PAT001", "Doe^John", "1.2.STU", "desc",
                "1.2.SER", "1.2.SOP", "SM", "Acme", "Model X", "SN-1");
        when(extractor.extract(any(InputStream.class))).thenReturn(metadata);
        when(sink.store(eq(file), eq("sample.dcm"))).thenReturn("/data/storage/sample.dcm");

        FileProcessingService service = new FileProcessingService(sink, extractor, databaseService);
        service.processFile(file);

        ArgumentCaptor<TransferRecord> captor = ArgumentCaptor.forClass(TransferRecord.class);
        verify(databaseService).save(captor.capture());
        TransferRecord saved = captor.getValue();
        assertEquals("sample.dcm", saved.getFileName());
        assertEquals("1.2.SER", saved.getSeriesInstanceUid());
        assertEquals("1.2.SOP", saved.getSopInstanceUid());
        assertEquals("/data/storage/sample.dcm", saved.getStoragePath());
        assertNotNull(saved.getTransactionDateTime());
    }

    @Test
    void movesFileToRejectedFolderWhenExtractionFails(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("bad.dcm");
        Files.writeString(file, "garbage");

        DicomObjectSink sink = mock(DicomObjectSink.class);
        DicomExtractorService extractor = mock(DicomExtractorService.class);
        DatabaseService databaseService = mock(DatabaseService.class);

        when(extractor.extract(any(InputStream.class))).thenThrow(new IOException("not a DICOM file"));

        FileProcessingService service = new FileProcessingService(sink, extractor, databaseService);
        service.processFile(file);

        assertFalse(Files.exists(file));
        assertTrue(Files.exists(tempDir.resolve("rejected").resolve("bad.dcm")));
        verifyNoInteractions(databaseService);
    }
}
