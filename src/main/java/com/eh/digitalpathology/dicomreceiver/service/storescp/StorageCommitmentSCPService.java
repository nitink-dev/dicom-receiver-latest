package com.eh.digitalpathology.dicomreceiver.service.storescp;

import com.eh.digitalpathology.dicomreceiver.model.DicomInstance;
import com.eh.digitalpathology.dicomreceiver.model.SgmtStatus;
import com.eh.digitalpathology.dicomreceiver.model.StorageCommitmentRequest;
import com.eh.digitalpathology.dicomreceiver.model.StorageCommitmentTracker;
import com.eh.digitalpathology.dicomreceiver.service.DatabaseService;
import com.eh.digitalpathology.dicomreceiver.service.EventNotificationService;
import com.eh.digitalpathology.dicomreceiver.util.StorageCommitmentUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dcm4che3.data.*;
import org.dcm4che3.io.DicomInputStream;
import org.dcm4che3.net.*;
import org.dcm4che3.net.pdu.PresentationContext;
import org.dcm4che3.net.service.DicomService;
import org.dcm4che3.net.service.DicomServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static  com.eh.digitalpathology.dicomreceiver.service.util.StorageCommitmentUtils.*;

@Component
public class StorageCommitmentSCPService implements DicomService {

    private static final Logger log = LoggerFactory.getLogger(StorageCommitmentSCPService.class);

    public static final String STORAGE_COMMITMENT_PUSH_MODEL_SOP_CLASS = "1.2.840.10008.1.20.1";
    public static final String STORAGE_COMMITMENT_PUSH_MODEL_SOP_INSTANCE = "1.2.840.10008.1.20.1.1";

    private final DatabaseService databaseService;
    private final EventNotificationService eventNotificationService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public StorageCommitmentSCPService(DatabaseService databaseService, EventNotificationService eventNotificationService) {
        this.databaseService = databaseService;
        this.eventNotificationService = eventNotificationService;
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

        log.info("Received N-ACTION for Transaction UID: {} with {} SOP Instances", transactionUID, refSOPSeq.size());

        Attributes eventInfo = new Attributes();
        eventInfo.setString(Tag.TransactionUID, VR.UI, transactionUID);
        Sequence successSeq = eventInfo.newSequence(Tag.ReferencedSOPSequence, refSOPSeq.size());
        Sequence failedSeq = eventInfo.newSequence(Tag.FailedSOPSequence, refSOPSeq.size());

        List<StorageCommitmentTracker> trackers = new ArrayList<>();
        String seriesId = null;

        for (Attributes item : refSOPSeq) {
            String sopClassUID = item.getString(Tag.ReferencedSOPClassUID);
            String sopInstanceUID = item.getString(Tag.ReferencedSOPInstanceUID);

            if (sopClassUID == null || sopInstanceUID == null) {
                log.warn("Missing SOP Class UID or Instance UID in a referenced item");
                continue;
            }

            Optional<DicomInstance> record = databaseService.getDicomInstanceBySopInstanceUid(sopInstanceUID);
            boolean found = record.isPresent();

            if (found) {
                seriesId = seriesId == null ? record.get().getSeriesInstanceUid() : seriesId;
                successSeq.add(createSuccessItem(sopClassUID, sopInstanceUID));
            } else {
                failedSeq.add(createFailedItem(sopClassUID, sopInstanceUID));
            }

            trackers.add(buildTracker(seriesId, sopInstanceUID, found));
        }

        int eventTypeID = failedSeq.isEmpty() ? 1 : 2;
        log.info("Sending N-EVENT-REPORT (eventTypeID = {}): {} success, {} failed", eventTypeID, successSeq.size(), failedSeq.size());

        try {
            as.neventReport(
                    STORAGE_COMMITMENT_PUSH_MODEL_SOP_CLASS,
                    STORAGE_COMMITMENT_PUSH_MODEL_SOP_INSTANCE,
                    eventTypeID,
                    eventInfo,
                    null
            );
        } catch (InterruptedException e) {
            log.error("Interrupted while sending N-EVENT-REPORT", e);
            Thread.currentThread().interrupt();
        }

        Attributes rsp = Commands.mkNActionRSP(cmd, Status.Success);
        as.writeDimseRSP(pc, rsp, null);

        if (seriesId != null && !trackers.isEmpty()) {
            StorageCommitmentRequest commitRequest = buildCommitRequest(seriesId, trackers, failedSeq.isEmpty());
            String status = databaseService.saveStorageCommitment(commitRequest);
            log.info("saveStorageCommitment response: {}", status);

            if ("success".equalsIgnoreCase(status)) {
                log.info("Storage commitment persisted successfully.");
                SgmtStatus sgmtStatus = new SgmtStatus(seriesId, "STORAGE_COMMITMENT");
                eventNotificationService.sendEvent("stgcmt-svc-topic", objectMapper.writeValueAsString(sgmtStatus));
            } else {
                log.warn("Storage commitment DB save failed; Kafka not notified.");
            }
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
}
