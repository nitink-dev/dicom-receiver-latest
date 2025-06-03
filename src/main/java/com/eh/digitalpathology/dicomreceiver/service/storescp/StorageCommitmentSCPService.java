package com.eh.digitalpathology.dicomreceiver.service.storescp;

import com.eh.digitalpathology.dicomreceiver.model.DicomInstance;
import com.eh.digitalpathology.dicomreceiver.model.StorageCommitmentRequest;
import com.eh.digitalpathology.dicomreceiver.model.StorageCommitmentTracker;
import com.eh.digitalpathology.dicomreceiver.service.DatabaseService;
import org.dcm4che3.data.*;
import org.dcm4che3.io.DicomInputStream;
import org.dcm4che3.net.*;
import org.dcm4che3.net.pdu.PresentationContext;
import org.dcm4che3.net.service.DicomService;
import org.dcm4che3.net.service.DicomServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

@Component
class StorageCommitmentSCPService implements DicomService {

    private static final Logger log = LoggerFactory.getLogger(StorageCommitmentSCPService.class);

    // Storage Commitment SOP Class UID (Push Model)
    public static final String STORAGE_COMMITMENT_PUSH_MODEL_SOP_CLASS = "1.2.840.10008.1.20.1";
    // Storage Commitment SOP Instance UID (always fixed)
    public static final String STORAGE_COMMITMENT_PUSH_MODEL_SOP_INSTANCE = "1.2.840.10008.1.20.1.1";

    private final DatabaseService databaseService;

    StorageCommitmentSCPService(DatabaseService databaseService) {
        this.databaseService = databaseService;
    }

    @Override
    public void onDimseRQ(Association as, PresentationContext pc, Dimse dimse,
                          Attributes cmd, PDVInputStream data) throws IOException {
        if (dimse != Dimse.N_ACTION_RQ) {
            throw new DicomServiceException(Status.UnrecognizedOperation,
                    "Only N-ACTION is supported for Storage Commitment");
        }
        Attributes actionInfo;
        try (DicomInputStream dis = new DicomInputStream(data)) {
            actionInfo = dis.readDataset(-1, -1);
        }
        String transactionUID = actionInfo.getString(Tag.TransactionUID);
        Sequence refSOPSeq = actionInfo.getSequence(Tag.ReferencedSOPSequence);
        if (transactionUID == null || refSOPSeq == null) {
            throw new DicomServiceException(Status.MissingAttributeValue,
                    "Missing Transaction UID or Referenced SOP Sequence");
        }
        System.out.printf("Received N-ACTION for Transaction UID: %s with %d SOP Instances%n",
                transactionUID, refSOPSeq.size());
        Attributes eventInfo = new Attributes();
        eventInfo.setString(Tag.TransactionUID, VR.UI, transactionUID);
        Sequence successSeq = eventInfo.newSequence(Tag.ReferencedSOPSequence, refSOPSeq.size());
        Sequence failedSeq = eventInfo.newSequence(Tag.FailedSOPSequence, refSOPSeq.size());
        String seriesId = null;

        List<StorageCommitmentTracker> trackers = new ArrayList<>();
        for (Attributes item : refSOPSeq) {
            String sopClassUID = item.getString(Tag.ReferencedSOPClassUID);
            String sopInstanceUID = item.getString(Tag.ReferencedSOPInstanceUID);
            if (sopClassUID == null || sopInstanceUID == null) {
                System.out.println("Missing SOP Class UID or Instance UID in a referenced item");
                continue;
            }

            // get data from db connector service
            //Optional<DicomInstance> record = dicomInstanceRepository.findBySopInstanceUid(sopInstanceUID);
            Optional<DicomInstance> record = databaseService.getDicomInstanceBySopInstanceUid(sopInstanceUID);

            if (record.isPresent()) {
                seriesId = seriesId == null ? record.get().getSeriesInstanceUid(): seriesId;
                Attributes successItem = new Attributes();
                successItem.setString(Tag.ReferencedSOPClassUID, VR.UI, sopClassUID);
                successItem.setString(Tag.ReferencedSOPInstanceUID, VR.UI, sopInstanceUID);
                successSeq.add(successItem);
            } else {
                Attributes failedItem = new Attributes();
                failedItem.setString(Tag.ReferencedSOPClassUID, VR.UI, sopClassUID);
                failedItem.setString(Tag.ReferencedSOPInstanceUID, VR.UI, sopInstanceUID);
                failedItem.setInt(Tag.FailureReason, VR.US, 0x0110); // Processing failure
                failedSeq.add(failedItem);
            }

            // update database for commitment request status
            trackers.add(saveStorageCommitmentTracker(seriesId, sopInstanceUID, record.isPresent()));
        }

        int eventTypeID = failedSeq.isEmpty() ? 1 : 2;
        System.out.printf("Sending N-EVENT-REPORT (eventTypeID = %d): %d success, %d failed%n",
                eventTypeID, successSeq.size(), failedSeq.size());

        try {
            as.neventReport(
                    STORAGE_COMMITMENT_PUSH_MODEL_SOP_CLASS,
                    STORAGE_COMMITMENT_PUSH_MODEL_SOP_INSTANCE,
                    eventTypeID,
                    eventInfo,
                    null
            );
        } catch (InterruptedException e) {
            System.err.println("Interrupting thread for EventReport "+e);
            Thread.currentThread().interrupt();
        }
        Attributes rsp = Commands.mkNActionRSP(cmd, Status.Success);
        as.writeDimseRSP(pc, rsp, null);

        // send commitment status to database service
        if (seriesId != null && !trackers.isEmpty()) {
                StorageCommitmentRequest commitRequest = new StorageCommitmentRequest();
                commitRequest.setSeriesInstanceUid(seriesId);
                commitRequest.setStorageCmtStatus(failedSeq.isEmpty());
                commitRequest.setTrackers(trackers);
                databaseService.saveStorageCommitment(commitRequest);
        }
    }
    @Override
    public void onClose(Association association) {
        log.info("Association closed with {}", association.getRemoteAET());
    }

    @Override
    public String[] getSOPClasses() {
        return new String[]{ UID.StorageCommitmentPushModel };
    }

    private static boolean checkIfStored(String sopClassUID, String sopInstanceUID) {
        File dicomFile = new File("D:\\opt\\dicom\\storage\\" + sopInstanceUID + ".dcm");
        if (!dicomFile.exists() || !dicomFile.isFile()) {
            System.out.println("File not found: " + dicomFile.getAbsolutePath());
            return false;
        }
        try (DicomInputStream dis = new DicomInputStream(dicomFile)) {
            Attributes dataset = dis.readDataset(-1, -1);
            String fileSOPClassUID = dataset.getString(Tag.SOPClassUID);
            String fileSOPInstanceUID = dataset.getString(Tag.SOPInstanceUID);
            if (!sopClassUID.equals(fileSOPClassUID) || !sopInstanceUID.equals(fileSOPInstanceUID)) {
                System.out.println("SOP UID mismatch for file: " + dicomFile.getName());
                return false;
            }
            return true; // Valid and matches
        } catch (IOException e) {
            System.out.println("Failed to read DICOM file: " + dicomFile.getName());
            e.printStackTrace();
            return false;
        }
    }

    private StorageCommitmentTracker saveStorageCommitmentTracker(String seriesId, String sopInstanceUID, boolean present) {
        StorageCommitmentTracker tracker = new StorageCommitmentTracker();
        tracker.setSopInstanceUid(sopInstanceUID);
        tracker.setSeriesInstanceUid(seriesId);
        tracker.setCmtRequestStatus(true);
        tracker.setCmtResponseStatus(present);
        tracker.setTimestamp(new Date());
        return tracker;
    }

}
