package com.eh.digitalpathology.dicomreceiver.model;

import java.util.List;

public class StorageCommitmentRequest {
    private String seriesInstanceUid;
    private boolean storageCmtStatus;
    private List<StorageCommitmentTracker> trackers;

    public String getSeriesInstanceUid() {
        return seriesInstanceUid;
    }

    public void setSeriesInstanceUid(String seriesInstanceUid) {
        this.seriesInstanceUid = seriesInstanceUid;
    }

    public boolean isStorageCmtStatus() {
        return storageCmtStatus;
    }

    public void setStorageCmtStatus(boolean storageCmtStatus) {
        this.storageCmtStatus = storageCmtStatus;
    }

    public List<StorageCommitmentTracker> getTrackers() {
        return trackers;
    }

    public void setTrackers(List<StorageCommitmentTracker> trackers) {
        this.trackers = trackers;
    }
}
