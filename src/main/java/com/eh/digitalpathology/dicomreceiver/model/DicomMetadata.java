package com.eh.digitalpathology.dicomreceiver.model;

/**
 * Metadata extracted from a single DICOM file: patient, study, series and scanner details.
 */
public record DicomMetadata(
        String patientId,
        String patientName,
        String studyInstanceUid,
        String studyDescription,
        String seriesInstanceUid,
        String sopInstanceUid,
        String modality,
        String manufacturer,
        String manufacturerModelName,
        String deviceSerialNumber
) {
}
