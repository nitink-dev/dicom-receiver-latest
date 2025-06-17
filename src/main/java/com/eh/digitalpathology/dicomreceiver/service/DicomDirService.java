package com.eh.digitalpathology.dicomreceiver.service;

import com.eh.digitalpathology.dicomreceiver.exceptions.DbConnectorExeption;
import com.eh.digitalpathology.dicomreceiver.model.DicomDirDocument;
import com.eh.digitalpathology.dicomreceiver.model.SgmtStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Sequence;
import org.dcm4che3.data.Tag;
import org.dcm4che3.io.DicomInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class DicomDirService {

    private static final Logger log = LoggerFactory.getLogger(DicomDirService.class.getName());
    private final DatabaseService databaseService;
    private final EventNotificationService eventNotificationService;
    private final ObjectMapper objectMapper = new ObjectMapper( );

    @Autowired
    public DicomDirService( DatabaseService databaseService, EventNotificationService eventNotificationService ) {
        this.databaseService = databaseService;
        this.eventNotificationService = eventNotificationService;
    }

    public void fetchAndStoreMetaData(Path filePath) throws IOException {
        log.info("fetchAndStoreMetaData :: filePath of dicom dir file :: {}", filePath);
        DicomDirDocument dicomDirDocument = fetchMetaData(filePath);
        log.info("fetchAndStoreMetaData :: dicomDirDocument :: {}", dicomDirDocument.studyId());
        try {
            if (dicomDirDocument != null) {
                String status = databaseService.saveMetaDataInfo(dicomDirDocument);
                log.info("fetchAndStoreMetaData :: status :: {}", status);
                // on success delete file at /opt/received
                if (status.equalsIgnoreCase("success")) {
                    SgmtStatus sgmtStatus = new SgmtStatus(dicomDirDocument.seriesId(),  "DICOM_DIR_CREATED");
                    eventNotificationService.sendEvent( "stgcmt-svc-topic", objectMapper.writeValueAsString(  sgmtStatus) );

                }
            }
        } catch ( IOException | DbConnectorExeption e) {
            log.error("Unable to convert DICOMDIR file to bytes {}", e.getMessage());
        }finally {
            boolean deleted = Files.deleteIfExists(filePath);
            log.info("fetchAndStoreMetaData :: on success delete DICOMDIR at /opt/received :: {}", deleted);
        }

    }

    private DicomDirDocument fetchMetaData(Path filePath) {
        try (DicomInputStream dis = new DicomInputStream(filePath.toFile())) {
            dis.readFileMetaInformation();
            Attributes dataSet = dis.readDataset(-1);

            Sequence dirRecords = dataSet.getSequence(Tag.DirectoryRecordSequence);
            if (dirRecords == null) {
                return null;
            }
            String studyId = null;
            String seriesId = null;
            int imageCount = 0;

            for (Attributes item : dirRecords) {
                String type = item.getString(Tag.DirectoryRecordType);
                if ("STUDY".equalsIgnoreCase(type) && studyId == null) {
                    studyId = item.getString(Tag.StudyInstanceUID);

                } else if ("SERIES".equalsIgnoreCase(type) && seriesId == null) {
                    seriesId = item.getString(Tag.SeriesInstanceUID);

                } else if ("IMAGE".equalsIgnoreCase(type)) {
                    imageCount++;
                }
            }
            byte[] dicomDirBinary = Files.readAllBytes(filePath);
            return  new DicomDirDocument( studyId, seriesId, imageCount, dicomDirBinary );

        } catch (IOException e) {
            log.error("Unable to extract DICOMDIR file:: {}", e.getMessage());
        }
        return null;
    }

}
