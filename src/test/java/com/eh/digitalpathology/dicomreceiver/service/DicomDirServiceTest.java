package com.eh.digitalpathology.dicomreceiver.service;

import com.eh.digitalpathology.dicomreceiver.config.KafkaTopicConfig;
import com.eh.digitalpathology.dicomreceiver.model.DicomDirDocument;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Sequence;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;


@ExtendWith(MockitoExtension.class)
class DicomDirServiceTest {

    @Mock
    private DatabaseService databaseService;

    @Mock
    private EventNotificationService eventNotificationService;

    @Mock
    private KafkaTopicConfig kafkaTopicConfig;

    @InjectMocks
    private DicomDirService dicomDirService;

    @TempDir
    Path tempDir;

    private Path dicomFilePath;

    @BeforeEach
    void setUp() throws Exception {
        dicomFilePath = tempDir.resolve("testDicomDir.dcm");
        Files.writeString(dicomFilePath, "test dicom content");
    }

    @Test
    void testFetchAndStoreMetaData_Success() throws Exception {
        DicomDirDocument doc = new DicomDirDocument("studyId","seriesId",3,new byte[3]);
        when(kafkaTopicConfig.getStgcmt()).thenReturn("test-topic");
        when(databaseService.saveMetaDataInfo(any())).thenReturn("success");

        dicomDirService.fetchAndStoreMetaData(doc);

        verify(databaseService, times(1)).saveMetaDataInfo(any(DicomDirDocument.class));
        verify(eventNotificationService, times(1))
                .sendEvent(eq("test-topic"), eq(""), anyString());
    }

    @Test
    void testFetchMetaData_WithStudySeriesAndImages_ShouldReturnDocument()  {

        Attributes dataSet = new Attributes();
        Attributes studyRecord = new Attributes();
        studyRecord.setString(Tag.DirectoryRecordType, VR.CS, "STUDY");
        studyRecord.setString(Tag.StudyInstanceUID, VR.UI, "STUDY123");

        Attributes seriesRecord = new Attributes();
        seriesRecord.setString(Tag.DirectoryRecordType, VR.CS, "SERIES");
        seriesRecord.setString(Tag.SeriesInstanceUID, VR.UI, "SERIES123");

        Attributes imageRecord = new Attributes();
        imageRecord.setString(Tag.DirectoryRecordType, VR.CS, "IMAGE");

        Sequence sequence = dataSet.newSequence(Tag.DirectoryRecordSequence, 3);
        sequence.add(studyRecord);
        sequence.add(seriesRecord);
        sequence.add(imageRecord);
        byte[] dicomContent = "test dicom content".getBytes();
        DicomDirDocument result = dicomDirService.fetchMetaData(dicomContent, dataSet);

        assertNotNull(result);
        assertEquals("STUDY123", result.studyId());
        assertEquals("SERIES123", result.seriesId());
        assertEquals(1, result.imageCount());

    }


    @Test
    void testFetchMetaData_WithNullSequence_ShouldReturnNull() {
        Attributes dataSet = new Attributes();
        byte[] dicomContent = "test dicom content".getBytes();
        DicomDirDocument result = dicomDirService.fetchMetaData(dicomContent, dataSet);
        assertNull(result);
    }


    @Test
    void testFetchMetaData_WhenFileReadFails_ShouldReturnNull() throws Exception {
        Attributes dataSet = new Attributes();
        Sequence sequence = dataSet.newSequence(Tag.DirectoryRecordSequence, 1);
        Attributes rec = new Attributes();
        rec.setString(Tag.DirectoryRecordType, VR.CS, "STUDY");
        rec.setString(Tag.StudyInstanceUID, VR.UI, "STUDY001");
        sequence.add(rec);

        Path unreadableFile = tempDir.resolve("deletedFile.dcm");
        Files.writeString(unreadableFile, "temporary content");
        Files.delete(unreadableFile);

        DicomDirDocument result = dicomDirService.fetchMetaData(null, dataSet);

        assertNull(result);
    }

    @Test
    void testFetchAndStoreMetaData_WhenDbThrowsDbConnectorException_ShouldCatchAndNotRethrow() {
        DicomDirDocument doc = new DicomDirDocument("studyId", "seriesId", 3, new byte[3]);

        when(databaseService.saveMetaDataInfo(any()))
                .thenThrow(new com.eh.digitalpathology.dicomreceiver.exceptions.DbConnectorExeption("ERR", "DB error"));

        assertDoesNotThrow(() -> dicomDirService.fetchAndStoreMetaData( doc));

        verify(eventNotificationService, never()).sendEvent(any(), any(), any());

    }

    @Test
    void testFetchAndStoreMetaData_WhenObjectMapperThrowsIOException_ShouldCatchAndNotRethrow() throws Exception {
        ObjectMapper mockObjectMapper = mock(ObjectMapper.class);
        when(mockObjectMapper.writeValueAsString(any()))
                .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("Serialization failed") {});

        java.lang.reflect.Field field = DicomDirService.class.getDeclaredField("objectMapper");
        field.setAccessible(true);
        field.set(dicomDirService, mockObjectMapper);

        DicomDirDocument doc = new DicomDirDocument("studyId", "seriesId", 3, new byte[3]);
        when(kafkaTopicConfig.getStgcmt()).thenReturn("test-topic");
        when(databaseService.saveMetaDataInfo(any())).thenReturn("success");

        assertDoesNotThrow(() -> dicomDirService.fetchAndStoreMetaData( doc));

        verify(eventNotificationService, never()).sendEvent(any(), any(), any());

    }

    @Test
    void testFetchAndStoreMetaData_WhenDeleteFileFails_ShouldLogAndNotRethrow() {
        DicomDirDocument doc = new DicomDirDocument("studyId", "seriesId", 3, new byte[3]);

        when(kafkaTopicConfig.getStgcmt()).thenReturn("test-topic");
        when(databaseService.saveMetaDataInfo(any())).thenReturn("success");

        try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
            mockedFiles.when(() -> Files.deleteIfExists(any(Path.class)))
                    .thenThrow(new IOException("Permission denied"));

            assertDoesNotThrow(() -> dicomDirService.fetchAndStoreMetaData( doc));

            verify(eventNotificationService).sendEvent(any(), any(), any());
        }
    }

    @Test
    void testFetchAndStoreMetaData_WhenDocumentIsNull_ShouldSkipSaveAndDeleteFile() {
        assertDoesNotThrow(() -> dicomDirService.fetchAndStoreMetaData( null));

        verify(databaseService, never()).saveMetaDataInfo(any());
        verify(eventNotificationService, never()).sendEvent(any(), any(), any());

    }

    @Test
    void testFetchAndStoreMetaData_WhenStatusIsNotSuccess_ShouldNotSendKafkaEvent() {
        DicomDirDocument doc = new DicomDirDocument("studyId", "seriesId", 3, new byte[3]);

        when(databaseService.saveMetaDataInfo(any())).thenReturn("failure");

        assertDoesNotThrow(() -> dicomDirService.fetchAndStoreMetaData( doc));

        verify(eventNotificationService, never()).sendEvent(any(), any(), any());

    }

    @Test
    void testFetchMetaData_WithOnlyStudyRecord_ShouldReturnDocumentWithNullSeriesAndZeroImages() {
        Attributes dataSet = new Attributes();
        Sequence sequence = dataSet.newSequence(Tag.DirectoryRecordSequence, 1);

        Attributes studyRecord = new Attributes();
        studyRecord.setString(Tag.DirectoryRecordType, VR.CS, "STUDY");
        studyRecord.setString(Tag.StudyInstanceUID, VR.UI, "STUDY_ONLY");
        sequence.add(studyRecord);
        byte[] dicomContent = "test dicom content".getBytes();
        DicomDirDocument result = dicomDirService.fetchMetaData(dicomContent, dataSet);

        assertNotNull(result);
        assertEquals("STUDY_ONLY", result.studyId());
        assertNull(result.seriesId());
        assertEquals(0, result.imageCount());
    }

    @Test
    void testFetchMetaData_WithMultipleImages_ShouldCountAllImages() {
        Attributes dataSet = new Attributes();
        Sequence sequence = dataSet.newSequence(Tag.DirectoryRecordSequence, 5);

        Attributes study = new Attributes();
        study.setString(Tag.DirectoryRecordType, VR.CS, "STUDY");
        study.setString(Tag.StudyInstanceUID, VR.UI, "STUDY001");
        sequence.add(study);

        Attributes study2 = new Attributes();
        study2.setString(Tag.DirectoryRecordType, VR.CS, "STUDY");
        study2.setString(Tag.StudyInstanceUID, VR.UI, "STUDY002");
        sequence.add(study2);

        Attributes series = new Attributes();
        series.setString(Tag.DirectoryRecordType, VR.CS, "SERIES");
        series.setString(Tag.SeriesInstanceUID, VR.UI, "SERIES001");
        sequence.add(series);

        for (int i = 0; i < 3; i++) {
            Attributes image = new Attributes();
            image.setString(Tag.DirectoryRecordType, VR.CS, "IMAGE");
            sequence.add(image);
        }
        byte[] dicomContent = "test dicom content".getBytes();
        DicomDirDocument result = dicomDirService.fetchMetaData(dicomContent, dataSet);

        assertNotNull(result);
        assertEquals("STUDY001", result.studyId());
        assertEquals("SERIES001", result.seriesId());
        assertEquals(3, result.imageCount());
    }

}