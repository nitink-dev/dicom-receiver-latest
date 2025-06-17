package com.eh.digitalpathology.dicomreceiver.model;

import java.util.Date;

public class StorageCommitmentTracker {
    private String sopInstanceUid;
    private String seriesInstanceUid;
    private String studyInstanceUid;
    private Boolean cmtRequestStatus;
    private Boolean cmtResponseStatus;
    private Date timestamp;
    private String requestId;

    public String getStudyInstanceUid() {
        return studyInstanceUid;
    }

    public void setStudyInstanceUid(String studyInstanceUid) {
        this.studyInstanceUid = studyInstanceUid;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getSopInstanceUid() {
        return sopInstanceUid;
    }

    public void setSopInstanceUid(String sopInstanceUid) {
        this.sopInstanceUid = sopInstanceUid;
    }

    public String getSeriesInstanceUid() {
        return seriesInstanceUid;
    }

    public void setSeriesInstanceUid(String seriesInstanceUid) {
        this.seriesInstanceUid = seriesInstanceUid;
    }

    public Boolean getCmtRequestStatus() {
        return cmtRequestStatus;
    }

    public void setCmtRequestStatus(Boolean cmtRequestStatus) {
        this.cmtRequestStatus = cmtRequestStatus;
    }

    public Boolean getCmtResponseStatus() {
        return cmtResponseStatus;
    }

    public void setCmtResponseStatus(Boolean cmtResponseStatus) {
        this.cmtResponseStatus = cmtResponseStatus;
    }

    public Date getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Date timestamp) {
        this.timestamp = timestamp;
    }
}
