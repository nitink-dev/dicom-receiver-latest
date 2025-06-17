package com.eh.digitalpathology.dicomreceiver.service.util;

import com.eh.digitalpathology.dicomreceiver.model.StorageCommitmentRequest;
import com.eh.digitalpathology.dicomreceiver.model.StorageCommitmentTracker;
import com.eh.digitalpathology.dicomreceiver.service.DatabaseService;
import com.eh.digitalpathology.dicomreceiver.service.EventNotificationService;
import com.eh.digitalpathology.dicomreceiver.model.SgmtStatus;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.VR;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class StorageCommitmentUtils {

    private final DatabaseService databaseService;
    private final EventNotificationService eventNotificationService;

    @Autowired
    public StorageCommitmentUtils(DatabaseService databaseService, EventNotificationService eventNotificationService) {
        this.databaseService = databaseService;
        this.eventNotificationService = eventNotificationService;
    }

    public boolean isSopInDicomInstance(String sopInstanceUID) {
        return databaseService.doesSopExistInDicomInstance(sopInstanceUID);
    }

    public void insertOrUpdateStgCmtTracker(String reqId, List<String> sopUIDs) {
        List<StorageCommitmentTracker> existingTrackers = databaseService.getTrackersByRequestId(reqId);
        for (String sop : sopUIDs) {
            boolean exists = existingTrackers.stream()
                    .anyMatch(tracker -> sop.equals(tracker.getSopInstanceUid()));
            if (!exists) {
                StorageCommitmentTracker tracker = new StorageCommitmentTracker();
                tracker.setRequestId(reqId);
                tracker.setSopInstanceUid(sop);
                tracker.setCmtRequestStatus(true);
                tracker.setCmtResponseStatus(false);
                // TODO: Add DB insert method in DatabaseService and call it here
                 databaseService.saveStgCmtTracker(tracker);
            }
        }
    }

    public void updateCmtResponseStatus(String sopUID, boolean status) {
        // TODO: Add this method in DatabaseService and implement logic to update one tracker's response status
         databaseService.updateCmtResponseStatus(sopUID, status);
    }

    public boolean isAllSopResponded(String reqId) {
        List<StorageCommitmentTracker> trackers = databaseService.getTrackersByRequestId(reqId);
        return trackers.stream().allMatch(StorageCommitmentTracker::getCmtResponseStatus);
    }

    public void sendNEventReport(String transactionUID, List<StorageCommitmentTracker> sopTrackers) {
        eventNotificationService.sendReport(transactionUID, sopTrackers);
    }

    public void updateCmtStatus(String reqId, boolean status) {
        SgmtStatus sgmtStatus = new SgmtStatus();
        sgmtStatus.setReqId(reqId);
        sgmtStatus.setStatus(status);
        // TODO: Add this method in DatabaseService and implement logic to update request status
         databaseService.updateStgCmtStatus(sgmtStatus);
    }

    public Attributes createSuccessItem(String sopClassUID, String sopInstanceUID) {
        Attributes item = new Attributes();
        item.setString(org.dcm4che3.data.Tag.ReferencedSOPClassUID, VR.UI, sopClassUID);
        item.setString(org.dcm4che3.data.Tag.ReferencedSOPInstanceUID, VR.UI, sopInstanceUID);
        return item;
    }

    public Attributes createFailedItem(String sopClassUID, String sopInstanceUID) {
        Attributes item = new Attributes();
        item.setString(org.dcm4che3.data.Tag.ReferencedSOPClassUID, VR.UI, sopClassUID);
        item.setString(org.dcm4che3.data.Tag.ReferencedSOPInstanceUID, VR.UI, sopInstanceUID);
        item.setInt(org.dcm4che3.data.Tag.FailureReason, VR.US, 0x0110); // Processing failure
        return item;
    }

    public StorageCommitmentTracker buildTracker(String seriesId, String sopInstanceUID, boolean present) {
        StorageCommitmentTracker tracker = new StorageCommitmentTracker();
        tracker.setSopInstanceUid(sopInstanceUID);
        tracker.setSeriesInstanceUid(seriesId);
        tracker.setCmtRequestStatus(true);
        tracker.setCmtResponseStatus(present);
        tracker.setTimestamp(new java.util.Date());
        return tracker;
    }
    public StorageCommitmentRequest buildCommitRequest(String seriesId, List<StorageCommitmentTracker> trackers, boolean status) {
        StorageCommitmentRequest request = new StorageCommitmentRequest();
        request.setSeriesInstanceUid(seriesId);
        request.setTrackers(trackers);
        request.setStorageCmtStatus(status);
        return request;
    }



}
