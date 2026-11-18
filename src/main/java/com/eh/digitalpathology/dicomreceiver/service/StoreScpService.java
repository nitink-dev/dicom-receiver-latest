package com.eh.digitalpathology.dicomreceiver.service;

import com.eh.digitalpathology.dicomreceiver.config.ScpProperties;
import com.eh.digitalpathology.dicomreceiver.config.WatcherProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.net.ApplicationEntity;
import org.dcm4che3.net.Association;
import org.dcm4che3.net.Connection;
import org.dcm4che3.net.Device;
import org.dcm4che3.net.PDVInputStream;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.TransferCapability;
import org.dcm4che3.net.pdu.PresentationContext;
import org.dcm4che3.net.service.BasicCEchoSCP;
import org.dcm4che3.net.service.BasicCStoreSCP;
import org.dcm4che3.net.service.DicomServiceException;
import org.dcm4che3.net.service.DicomServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Storage SCP started on application load. Receives DICOM objects (C-STORE) from a Leica
 * scanner or any storeSCU client and writes them into the watched folder. Files are
 * written as ".tmp" and atomically renamed once complete, so the DirectoryWatcherService
 * (which ignores ".tmp") never picks up a half-written file.
 */
@Service
public class StoreScpService {

    private static final Logger log = LoggerFactory.getLogger(StoreScpService.class);

    private final ScpProperties props;
    private final Path watchDir;
    private final StorageCommitmentScp storageCommitmentScp;
    private ExecutorService executor;
    private ScheduledExecutorService scheduler;
    private Device device;

    public StoreScpService(ScpProperties props, WatcherProperties watcherProperties,
                           StorageCommitmentScp storageCommitmentScp) {
        this.storageCommitmentScp = storageCommitmentScp;
        this.props = props;
        this.watchDir = Paths.get(watcherProperties.getDirectory());
    }

    @PostConstruct
    public void start() throws Exception {
        if (!props.isEnabled()) {
            log.info("Store SCP disabled (app.scp.enabled=false)");
            return;
        }
        Files.createDirectories(watchDir);

        Connection conn = new Connection();
        conn.setHostname(props.getBindAddress());
        conn.setPort(props.getPort());
        conn.setMaxOpsInvoked(0);
        conn.setMaxOpsPerformed(0);

        ApplicationEntity ae = new ApplicationEntity(props.getAeTitle());
        ae.setAssociationAcceptor(true);
        ae.addConnection(conn);
        // Accept every SOP class / transfer syntax so any scanner or SCU can store.
        ae.addTransferCapability(new TransferCapability(null, "*", TransferCapability.Role.SCP, "*"));

        DicomServiceRegistry registry = new DicomServiceRegistry();
        registry.addDicomService(new BasicCEchoSCP());
        registry.addDicomService(storageCommitmentScp);
        registry.addDicomService(new BasicCStoreSCP("*") {
            @Override
            protected void store(Association as, PresentationContext pc, Attributes rq,
                                 PDVInputStream data, Attributes rsp) throws IOException {
                receive(as, pc, rq, data);
            }
        });

        device = new Device("dicom-receiver-scp");
        device.setDimseRQHandler(registry);
        device.addConnection(conn);
        device.addApplicationEntity(ae);
        executor = Executors.newCachedThreadPool();
        scheduler = Executors.newSingleThreadScheduledExecutor();
        device.setExecutor(executor);
        device.setScheduledExecutor(scheduler);
        device.bindConnections();

        log.info("Store SCP listening on {}:{} (AE title {}), writing to {}",
                props.getBindAddress(), props.getPort(), props.getAeTitle(), watchDir.toAbsolutePath());
    }

    private void receive(Association as, PresentationContext pc, Attributes rq, PDVInputStream data)
            throws IOException {
        String iuid = rq.getString(Tag.AffectedSOPInstanceUID);
        String cuid = rq.getString(Tag.AffectedSOPClassUID);
        String tsuid = pc.getTransferSyntax();
        String fileName = (iuid != null ? iuid : java.util.UUID.randomUUID().toString()) + ".dcm";
        Path tmp = watchDir.resolve(fileName + ".tmp");
        Path target = watchDir.resolve(fileName);
        try {
            Attributes fmi = as.createFileMetaInformation(iuid, cuid, tsuid);
            try (org.dcm4che3.io.DicomOutputStream out =
                         new org.dcm4che3.io.DicomOutputStream(Files.newOutputStream(tmp), UID.ExplicitVRLittleEndian)) {
                out.writeFileMetaInformation(fmi);
                data.copyTo(out);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.info("Received {} from {} -> {}", iuid, as.getRemoteAET(), target);
        } catch (Exception e) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
            log.error("Failed to store {}: {}", iuid, e.getMessage());
            throw new DicomServiceException(Status.ProcessingFailure, e);
        }
    }

    @PreDestroy
    public void stop() {
        if (device != null) {
            device.unbindConnections();
        }
        if (executor != null) executor.shutdown();
        if (scheduler != null) scheduler.shutdown();
    }
}
