package com.eh.digitalpathology.dicomreceiver.service;

import com.eh.digitalpathology.dicomreceiver.entity.TransferRecord;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Sequence;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.net.Association;
import org.dcm4che3.net.Commands;
import org.dcm4che3.net.Dimse;
import org.dcm4che3.net.DimseRSPHandler;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.pdu.PresentationContext;
import org.dcm4che3.net.service.AbstractDicomService;
import org.dcm4che3.net.service.DicomServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Optional;

/**
 * Storage Commitment Push Model SCP. The SCU sends an N-ACTION-RQ listing the SOP
 * instances it wants us to confirm; we answer immediately and then report the outcome with
 * an N-EVENT-REPORT on the same association: "committed" for every instance we hold a
 * record for, "failed" (reason 0112H, no such object instance) for the others.
 */
@Component
public class StorageCommitmentScp extends AbstractDicomService {

    private static final Logger log = LoggerFactory.getLogger(StorageCommitmentScp.class);

    private static final int ACTION_REQUEST_COMMITMENT = 1;
    private static final int EVENT_ALL_SUCCESS = 1;
    private static final int EVENT_SOME_FAILED = 2;
    private static final String STGCMT_INSTANCE_UID = UID.StorageCommitmentPushModelInstance;

    private final DatabaseService databaseService;

    public StorageCommitmentScp(DatabaseService databaseService) {
        super(UID.StorageCommitmentPushModel);
        this.databaseService = databaseService;
    }

    @Override
    protected void onDimseRQ(Association as, PresentationContext pc, Dimse dimse, Attributes cmd, Attributes data)
            throws IOException {
        if (dimse != Dimse.N_ACTION_RQ) {
            throw new DicomServiceException(Status.UnrecognizedOperation);
        }
        int actionTypeId = cmd.getInt(Tag.ActionTypeID, 0);
        if (actionTypeId != ACTION_REQUEST_COMMITMENT) {
            throw new DicomServiceException(Status.NoSuchActionType).setActionTypeID(actionTypeId);
        }
        String transactionUid = data == null ? null : data.getString(Tag.TransactionUID);
        Sequence referenced = data == null ? null : data.getSequence(Tag.ReferencedSOPSequence);
        if (transactionUid == null || referenced == null || referenced.isEmpty()) {
            throw new DicomServiceException(Status.MissingAttributeValue);
        }

        Attributes eventInfo = buildEventInfo(transactionUid, referenced);
        int eventTypeId = eventInfo.contains(Tag.FailedSOPSequence) ? EVENT_SOME_FAILED : EVENT_ALL_SUCCESS;

        as.writeDimseRSP(pc, Commands.mkNActionRSP(cmd, Status.Success), null);
        log.info("Storage commitment {} from {}: {} instance(s), event type {}",
                transactionUid, as.getRemoteAET(), referenced.size(), eventTypeId);

        as.getApplicationEntity().getDevice().execute(() -> sendEventReport(as, eventTypeId, eventInfo));
    }

    private Attributes buildEventInfo(String transactionUid, Sequence referenced) {
        Attributes info = new Attributes();
        info.setString(Tag.TransactionUID, VR.UI, transactionUid);
        Sequence ok = info.newSequence(Tag.ReferencedSOPSequence, referenced.size());
        Sequence failed = info.newSequence(Tag.FailedSOPSequence, referenced.size());
        for (Attributes item : referenced) {
            String cuid = item.getString(Tag.ReferencedSOPClassUID);
            String iuid = item.getString(Tag.ReferencedSOPInstanceUID);
            Optional<TransferRecord> record = databaseService.findBySopInstanceUid(iuid);
            Attributes out = new Attributes(3);
            out.setString(Tag.ReferencedSOPClassUID, VR.UI, cuid);
            out.setString(Tag.ReferencedSOPInstanceUID, VR.UI, iuid);
            if (record.isPresent() && record.get().getStoragePath() != null) {
                ok.add(out);
            } else {
                out.setInt(Tag.FailureReason, VR.US, Status.NoSuchObjectInstance);
                failed.add(out);
            }
        }
        if (failed.isEmpty()) {
            info.remove(Tag.FailedSOPSequence);
        }
        return info;
    }

    private void sendEventReport(Association as, int eventTypeId, Attributes eventInfo) {
        try {
            if (!as.isReadyForDataTransfer()) {
                log.warn("Cannot send storage commitment report: association already closed");
                return;
            }
            as.neventReport(UID.StorageCommitmentPushModel, STGCMT_INSTANCE_UID, eventTypeId, eventInfo,
                    UID.ImplicitVRLittleEndian, new DimseRSPHandler(as.nextMessageID()));
        } catch (Exception e) {
            log.warn("Failed to send storage commitment event report: {}", e.getMessage());
        }
    }
}
