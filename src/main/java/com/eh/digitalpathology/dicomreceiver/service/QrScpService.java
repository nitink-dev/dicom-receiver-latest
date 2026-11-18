package com.eh.digitalpathology.dicomreceiver.service;

import com.eh.digitalpathology.dicomreceiver.api.DicomObjectSink;
import com.eh.digitalpathology.dicomreceiver.config.QrScpProperties;
import com.eh.digitalpathology.dicomreceiver.entity.TransferRecord;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomInputStream;
import org.dcm4che3.net.Association;
import org.dcm4che3.net.ApplicationEntity;
import org.dcm4che3.net.Connection;
import org.dcm4che3.net.DataWriter;
import org.dcm4che3.net.Device;
import org.dcm4che3.net.Dimse;
import org.dcm4che3.net.InputStreamDataWriter;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.TransferCapability;
import org.dcm4che3.net.pdu.AAssociateRQ;
import org.dcm4che3.net.pdu.PresentationContext;
import org.dcm4che3.net.service.BasicCEchoSCP;
import org.dcm4che3.net.service.BasicCFindSCP;
import org.dcm4che3.net.service.BasicCGetSCP;
import org.dcm4che3.net.service.BasicCMoveSCP;
import org.dcm4che3.net.service.BasicQueryTask;
import org.dcm4che3.net.service.BasicRetrieveTask;
import org.dcm4che3.net.service.DicomServiceException;
import org.dcm4che3.net.service.DicomServiceRegistry;
import org.dcm4che3.net.service.InstanceLocator;
import org.dcm4che3.net.service.QueryRetrieveLevel2;
import org.dcm4che3.net.service.QueryTask;
import org.dcm4che3.net.service.RetrieveTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Pattern;

/**
 * Query/Retrieve SCP (equivalent of dcm4che's {@code dcmqrscp}) served from the transfer
 * records in the database and the files held by the configured storage sink.
 * Supports C-ECHO, C-FIND, C-GET and C-MOVE on the Patient Root and Study Root models.
 */
@Service
public class QrScpService {

    private static final Logger log = LoggerFactory.getLogger(QrScpService.class);

    private static final String[] FIND = {
            UID.PatientRootQueryRetrieveInformationModelFind, UID.StudyRootQueryRetrieveInformationModelFind};
    private static final String[] GET = {
            UID.PatientRootQueryRetrieveInformationModelGet, UID.StudyRootQueryRetrieveInformationModelGet};
    private static final String[] MOVE = {
            UID.PatientRootQueryRetrieveInformationModelMove, UID.StudyRootQueryRetrieveInformationModelMove};

    private final QrScpProperties props;
    private final DatabaseService databaseService;
    private final DicomObjectSink sink;
    private ExecutorService executor;
    private ScheduledExecutorService scheduler;
    private Device device;

    public QrScpService(QrScpProperties props, DatabaseService databaseService, DicomObjectSink sink) {
        this.props = props;
        this.databaseService = databaseService;
        this.sink = sink;
    }

    @PostConstruct
    public void start() throws Exception {
        if (!props.isEnabled()) {
            log.info("Query/Retrieve SCP disabled (app.qr.enabled=false)");
            return;
        }
        Connection conn = new Connection();
        conn.setHostname(props.getBindAddress());
        conn.setPort(props.getPort());
        conn.setMaxOpsInvoked(0);
        conn.setMaxOpsPerformed(0);

        ApplicationEntity ae = new ApplicationEntity(props.getAeTitle());
        ae.setAssociationAcceptor(true);
        ae.addConnection(conn);
        ae.addTransferCapability(new TransferCapability(null, UID.Verification, TransferCapability.Role.SCP, UID.ImplicitVRLittleEndian));
        for (String[] group : new String[][]{FIND, GET, MOVE}) {
            for (String cuid : group) {
                ae.addTransferCapability(new TransferCapability(null, cuid, TransferCapability.Role.SCP, UID.ImplicitVRLittleEndian,
                        UID.ExplicitVRLittleEndian));
            }
        }
        // Needed so C-GET can push the instances back over the same association.
        ae.addTransferCapability(new TransferCapability(null, "*", TransferCapability.Role.SCU, "*"));

        DicomServiceRegistry registry = new DicomServiceRegistry();
        registry.addDicomService(new BasicCEchoSCP());
        registry.addDicomService(new BasicCFindSCP(FIND) {
            @Override
            protected QueryTask calculateMatches(Association as, PresentationContext pc, Attributes rq, Attributes keys)
                    throws DicomServiceException {
                QueryRetrieveLevel2 level = QueryRetrieveLevel2.validateQueryIdentifier(
                        keys, EnumSet.of(QueryRetrieveLevel2.PATIENT, QueryRetrieveLevel2.STUDY,
                                QueryRetrieveLevel2.SERIES, QueryRetrieveLevel2.IMAGE), true);
                return new ListQueryTask(as, pc, rq, keys, query(level, keys), level);
            }
        });
        registry.addDicomService(new BasicCGetSCP(GET) {
            @Override
            protected RetrieveTask calculateMatches(Association as, PresentationContext pc, Attributes rq, Attributes keys)
                    throws DicomServiceException {
                QueryRetrieveLevel2.validateRetrieveIdentifier(keys, EnumSet.of(QueryRetrieveLevel2.PATIENT,
                        QueryRetrieveLevel2.STUDY, QueryRetrieveLevel2.SERIES, QueryRetrieveLevel2.IMAGE), true);
                return new InstanceRetrieveTask(Dimse.C_GET_RQ, as, pc, rq, locate(keys), as);
            }
        });
        registry.addDicomService(new BasicCMoveSCP(MOVE) {
            @Override
            protected RetrieveTask calculateMatches(Association as, PresentationContext pc, Attributes rq, Attributes keys)
                    throws DicomServiceException {
                QueryRetrieveLevel2.validateRetrieveIdentifier(keys, EnumSet.of(QueryRetrieveLevel2.PATIENT,
                        QueryRetrieveLevel2.STUDY, QueryRetrieveLevel2.SERIES, QueryRetrieveLevel2.IMAGE), true);
                String dest = rq.getString(Tag.MoveDestination);
                List<InstanceLocator> insts = locate(keys);
                return new InstanceRetrieveTask(Dimse.C_MOVE_RQ, as, pc, rq, insts, connectToDestination(as, dest, insts));
            }
        });

        device = new Device("dicom-receiver-qr-scp");
        device.setDimseRQHandler(registry);
        device.addConnection(conn);
        device.addApplicationEntity(ae);
        executor = Executors.newCachedThreadPool();
        scheduler = Executors.newSingleThreadScheduledExecutor();
        device.setExecutor(executor);
        device.setScheduledExecutor(scheduler);
        device.bindConnections();

        log.info("Query/Retrieve SCP listening on {}:{} (AE title {})",
                props.getBindAddress(), props.getPort(), props.getAeTitle());
    }

    @PreDestroy
    public void stop() {
        if (device != null) device.unbindConnections();
        if (executor != null) executor.shutdown();
        if (scheduler != null) scheduler.shutdown();
    }

    // ---------------------------------------------------------------- C-FIND

    List<Attributes> query(QueryRetrieveLevel2 level, Attributes keys) {
        Map<String, Attributes> results = new LinkedHashMap<>();
        for (TransferRecord r : databaseService.findAll()) {
            if (!matches(r, keys)) continue;
            String uniq = switch (level) {
                case PATIENT -> r.getPatientId();
                case STUDY -> r.getStudyInstanceUid();
                case SERIES -> r.getSeriesInstanceUid();
                case IMAGE -> r.getSopInstanceUid();
            };
            results.computeIfAbsent(String.valueOf(uniq) + "|" + (level == QueryRetrieveLevel2.PATIENT ? "" : r.getPatientId()),
                    k -> toAttributes(r, level));
        }
        return new ArrayList<>(results.values());
    }

    private Attributes toAttributes(TransferRecord r, QueryRetrieveLevel2 level) {
        Attributes a = new Attributes();
        a.setString(Tag.QueryRetrieveLevel, VR.CS, level.name());
        a.setString(Tag.PatientID, VR.LO, r.getPatientId());
        a.setString(Tag.PatientName, VR.PN, r.getPatientName());
        if (level != QueryRetrieveLevel2.PATIENT) {
            a.setString(Tag.StudyInstanceUID, VR.UI, r.getStudyInstanceUid());
        }
        if (level == QueryRetrieveLevel2.SERIES || level == QueryRetrieveLevel2.IMAGE) {
            a.setString(Tag.SeriesInstanceUID, VR.UI, r.getSeriesInstanceUid());
            a.setString(Tag.Modality, VR.CS, r.getModality());
            a.setString(Tag.Manufacturer, VR.LO, r.getManufacturer());
            a.setString(Tag.ManufacturerModelName, VR.LO, r.getManufacturerModelName());
            a.setString(Tag.DeviceSerialNumber, VR.LO, r.getDeviceSerialNumber());
        }
        if (level == QueryRetrieveLevel2.IMAGE) {
            a.setString(Tag.SOPInstanceUID, VR.UI, r.getSopInstanceUid());
        }
        return a;
    }

    /** Matches a record against the supported key attributes; unsupported keys are ignored. */
    static boolean matches(TransferRecord r, Attributes keys) {
        return matchKey(keys, Tag.PatientID, r.getPatientId())
                && matchKey(keys, Tag.PatientName, r.getPatientName())
                && matchKey(keys, Tag.StudyInstanceUID, r.getStudyInstanceUid())
                && matchKey(keys, Tag.SeriesInstanceUID, r.getSeriesInstanceUid())
                && matchKey(keys, Tag.SOPInstanceUID, r.getSopInstanceUid())
                && matchKey(keys, Tag.Modality, r.getModality())
                && matchKey(keys, Tag.Manufacturer, r.getManufacturer())
                && matchKey(keys, Tag.ManufacturerModelName, r.getManufacturerModelName())
                && matchKey(keys, Tag.DeviceSerialNumber, r.getDeviceSerialNumber());
    }

    private static boolean matchKey(Attributes keys, int tag, String value) {
        String[] wanted = keys.getStrings(tag);
        if (wanted == null || wanted.length == 0) return true;
        boolean any = false;
        for (String w : wanted) {
            if (w == null || w.isEmpty() || w.equals("*")) return true;
            any = true;
            if (value == null) continue;
            if (w.contains("*") || w.contains("?")) {
                StringBuilder re = new StringBuilder();
                for (char c : w.toCharArray()) {
                    re.append(c == '*' ? ".*" : c == '?' ? "." : Pattern.quote(String.valueOf(c)));
                }
                if (Pattern.compile(re.toString(), Pattern.CASE_INSENSITIVE).matcher(value).matches()) return true;
            } else if (w.toUpperCase(Locale.ROOT).equals(value.toUpperCase(Locale.ROOT))) {
                return true;
            }
        }
        return !any;
    }

    private static class ListQueryTask extends BasicQueryTask {
        private final Iterator<Attributes> it;
        private final QueryRetrieveLevel2 level;

        ListQueryTask(Association as, PresentationContext pc, Attributes rq, Attributes keys,
                      List<Attributes> matches, QueryRetrieveLevel2 level) {
            super(as, pc, rq, keys);
            this.it = matches.iterator();
            this.level = level;
        }

        @Override
        protected boolean hasMoreMatches() {
            return it.hasNext();
        }

        @Override
        protected Attributes nextMatch() {
            return it.next();
        }

        @Override
        protected Attributes adjust(Attributes match) {
            Attributes out = new Attributes();
            out.addSelected(match, keys);
            out.setString(Tag.QueryRetrieveLevel, VR.CS, level.name());
            out.addSelected(match, level.uniqueKey());
            return out;
        }
    }

    // ------------------------------------------------------ C-GET / C-MOVE

    List<InstanceLocator> locate(Attributes keys) throws DicomServiceException {
        List<InstanceLocator> out = new ArrayList<>();
        for (TransferRecord r : databaseService.findAll()) {
            if (r.getStoragePath() == null || !matches(r, keys)) continue;
            try (InputStream in = sink.openInputStream(r.getStoragePath());
                 DicomInputStream din = new DicomInputStream(in)) {
                Attributes fmi = din.readFileMetaInformation();
                if (fmi == null) continue;
                out.add(new InstanceLocator(fmi.getString(Tag.MediaStorageSOPClassUID),
                        r.getSopInstanceUid() != null ? r.getSopInstanceUid() : fmi.getString(Tag.MediaStorageSOPInstanceUID),
                        fmi.getString(Tag.TransferSyntaxUID), r.getStoragePath()));
            } catch (Exception e) {
                log.warn("Skipping unreadable instance {}: {}", r.getStoragePath(), e.getMessage());
            }
        }
        return out;
    }

    private Association connectToDestination(Association as, String destAet, List<InstanceLocator> insts)
            throws DicomServiceException {
        String target = destAet == null ? null : props.getMoveDestinations().get(destAet);
        if (target == null || !target.contains(":")) {
            throw new DicomServiceException(Status.MoveDestinationUnknown, "Unknown move destination: " + destAet);
        }
        try {
            Connection remote = new Connection();
            remote.setHostname(target.substring(0, target.lastIndexOf(':')));
            remote.setPort(Integer.parseInt(target.substring(target.lastIndexOf(':') + 1)));
            AAssociateRQ aarq = new AAssociateRQ();
            aarq.setCalledAET(destAet);
            aarq.setCallingAET(as.getCalledAET());
            int pcid = 1;
            Map<String, Boolean> seen = new LinkedHashMap<>();
            for (InstanceLocator il : insts) {
                if (seen.put(il.cuid + "|" + il.tsuid, true) == null) {
                    aarq.addPresentationContext(new PresentationContext(pcid, il.cuid, il.tsuid));
                    pcid += 2;
                }
            }
            ApplicationEntity ae = as.getApplicationEntity();
            return ae.connect(ae.getConnections().get(0), remote, aarq);
        } catch (Exception e) {
            throw new DicomServiceException(Status.UnableToProcess, e);
        }
    }

    private class InstanceRetrieveTask extends BasicRetrieveTask<InstanceLocator> {
        InstanceRetrieveTask(Dimse rq, Association as, PresentationContext pc, Attributes rqCmd,
                             List<InstanceLocator> insts, Association storeas) {
            super(rq, as, pc, rqCmd, insts, storeas);
        }

        @Override
        protected String selectTransferSyntaxFor(Association storeas, InstanceLocator inst) {
            // Files are streamed as stored (no transcoding): require the stored syntax.
            return inst.tsuid;
        }

        @Override
        protected DataWriter createDataWriter(InstanceLocator inst, String tsuid) throws Exception {
            InputStream in = sink.openInputStream(inst.uri);
            DicomInputStream din = new DicomInputStream(in);
            din.readFileMetaInformation();
            return new InputStreamDataWriter(din);
        }
    }
}
