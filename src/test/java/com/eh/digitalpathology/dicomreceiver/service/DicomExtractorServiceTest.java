package com.eh.digitalpathology.dicomreceiver.service;

import com.eh.digitalpathology.dicomreceiver.model.DicomMetadata;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DicomExtractorServiceTest {

    @Test
    void extractsPatientStudySeriesAndScannerAttributes(@TempDir Path tempDir) throws Exception {
        Attributes attrs = new Attributes();
        attrs.setString(Tag.SOPClassUID, VR.UI, UID.SecondaryCaptureImageStorage);
        attrs.setString(Tag.SOPInstanceUID, VR.UI, "1.2.840.10008.SOP.001");
        attrs.setString(Tag.PatientID, VR.LO, "PAT001");
        attrs.setString(Tag.PatientName, VR.PN, "Doe^John");
        attrs.setString(Tag.StudyInstanceUID, VR.UI, "1.2.840.10008.STU.001");
        attrs.setString(Tag.StudyDescription, VR.LO, "Slide scan");
        attrs.setString(Tag.SeriesInstanceUID, VR.UI, "1.2.840.10008.SER.001");
        attrs.setString(Tag.Modality, VR.CS, "SM");
        attrs.setString(Tag.Manufacturer, VR.LO, "Acme Scanners");
        attrs.setString(Tag.ManufacturerModelName, VR.LO, "ScanMaster 3000");
        attrs.setString(Tag.DeviceSerialNumber, VR.LO, "SN-42");

        Attributes fmi = attrs.createFileMetaInformation(UID.ExplicitVRLittleEndian);

        Path dicomFile = tempDir.resolve("sample.dcm");
        try (DicomOutputStream dos = new DicomOutputStream(dicomFile.toFile())) {
            dos.writeDataset(fmi, attrs);
        }

        DicomExtractorService extractorService = new DicomExtractorService();
        DicomMetadata metadata;
        try (InputStream in = Files.newInputStream(dicomFile)) {
            metadata = extractorService.extract(in);
        }

        assertEquals("PAT001", metadata.patientId());
        assertEquals("1.2.840.10008.STU.001", metadata.studyInstanceUid());
        assertEquals("1.2.840.10008.SER.001", metadata.seriesInstanceUid());
        assertEquals("1.2.840.10008.SOP.001", metadata.sopInstanceUid());
        assertEquals("SM", metadata.modality());
        assertEquals("Acme Scanners", metadata.manufacturer());
        assertEquals("ScanMaster 3000", metadata.manufacturerModelName());
        assertEquals("SN-42", metadata.deviceSerialNumber());
    }
}
