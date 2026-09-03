package com.eh.digitalpathology.dicomreceiver.service;

import com.eh.digitalpathology.dicomreceiver.model.DicomMetadata;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.io.DicomInputStream;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;

/**
 * Reads Patient / Study / Series / Scanner attributes out of a DICOM file.
 */
@Service
public class DicomExtractorService {

    public DicomMetadata extract(InputStream in) throws IOException {
        try (DicomInputStream dis = new DicomInputStream(in)) {
            Attributes attrs = dis.readDataset();

            return new DicomMetadata(
                    attrs.getString(Tag.PatientID),
                    attrs.getString(Tag.PatientName),
                    attrs.getString(Tag.StudyInstanceUID),
                    attrs.getString(Tag.StudyDescription),
                    attrs.getString(Tag.SeriesInstanceUID),
                    attrs.getString(Tag.SOPInstanceUID),
                    attrs.getString(Tag.Modality),
                    attrs.getString(Tag.Manufacturer),
                    attrs.getString(Tag.ManufacturerModelName),
                    attrs.getString(Tag.DeviceSerialNumber)
            );
        }
    }
}
