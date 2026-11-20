package com.eh.digitalpathology.dicomreceiver.integration;

import com.eh.digitalpathology.dicomreceiver.entity.TransferRecord;
import com.eh.digitalpathology.dicomreceiver.repository.TransferRecordRepository;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.net.ApplicationEntity;
import org.dcm4che3.net.Association;
import org.dcm4che3.net.Connection;
import org.dcm4che3.net.DataWriterAdapter;
import org.dcm4che3.net.Device;
import org.dcm4che3.net.DimseRSPHandler;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.pdu.AAssociateRQ;
import org.dcm4che3.net.pdu.PresentationContext;
import org.dcm4che3.net.pdu.RoleSelection;
import org.dcm4che3.net.service.BasicCStoreSCP;
import org.dcm4che3.net.service.DicomServiceRegistry;
import org.dcm4che3.net.PDVInputStream;
import org.dcm4che3.net.Dimse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ScpAndQrIntegrationTest {

    static Path tmp;
    static int qrPort;
    static final String SOP = "1.2.826.0.1.3680043.9.7.1";
    static final String CUID = UID.VLWholeSlideMicroscopyImageStorage;

    @BeforeAll
    static void init() throws Exception {
        tmp = Files.createTempDirectory("dcmit");
        qrPort = freePort();
    }

    static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        tmp = Files.createTempDirectory("dcmit");
        qrPort = freePort();
        r.add("app.watch.directory", () -> tmp.resolve("in").toString());
        r.add("app.watch.poll-interval-ms", () -> "200");
        r.add("app.storage.local-path", () -> tmp.resolve("store").toString());
        r.add("app.qr.port", () -> qrPort);
        r.add("spring.datasource.url", () -> "jdbc:h2:mem:it" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
    }

    @Autowired TransferRecordRepository repo;
    @Autowired MockMvc mvc;

    static Device clientDevice() {
        Device d = new Device("client");
        ApplicationEntity ae = new ApplicationEntity("CLIENT");
        Connection c = new Connection();
        d.addConnection(c);
        ae.addConnection(c);
        d.addApplicationEntity(ae);
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor();
        d.setExecutor(Executors.newCachedThreadPool());
        d.setScheduledExecutor(s);
        return d;
    }

    static Attributes dataset(String patientName) {
        Attributes a = new Attributes();
        a.setString(Tag.SOPClassUID, VR.UI, CUID);
        a.setString(Tag.SOPInstanceUID, VR.UI, SOP);
        a.setString(Tag.StudyInstanceUID, VR.UI, "1.2.3.4");
        a.setString(Tag.SeriesInstanceUID, VR.UI, "1.2.3.4.5");
        a.setString(Tag.PatientID, VR.LO, "PAT1");
        a.setString(Tag.PatientName, VR.PN, patientName);
        a.setString(Tag.Modality, VR.CS, "SM");
        return a;
    }

    void send(String patientName) throws Exception {
        Device d = clientDevice();
        ApplicationEntity ae = d.getApplicationEntity("CLIENT");
        AAssociateRQ rq = new AAssociateRQ();
        rq.setCalledAET("DCMQRSCP");
        rq.addPresentationContext(new PresentationContext(1, CUID, UID.ExplicitVRLittleEndian));
        Connection remote = new Connection(null, "localhost", qrPort);
        Association as = ae.connect(remote, rq);
        as.cstore(CUID, SOP, 0, new DataWriterAdapter(dataset(patientName)), UID.ExplicitVRLittleEndian,
                new DimseRSPHandler(as.nextMessageID()) {
                    @Override
                    public void onDimseRSP(Association as, Attributes cmd, Attributes data) {
                        super.onDimseRSP(as, cmd, data);
                        assertEquals(Status.Success, cmd.getInt(Tag.Status, -1));
                    }
                });
        as.waitForOutstandingRSP();
        as.release();
        ((java.util.concurrent.ExecutorService) d.getExecutor()).shutdown();
        d.getScheduledExecutor().shutdown();
    }

    void awaitRecords(int n, String name) throws Exception {
        long end = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < end) {
            List<TransferRecord> all = repo.findAll();
            if (all.size() == n && (name == null || name.equals(all.get(0).getPatientName()))) return;
            Thread.sleep(100);
        }
        fail("Expected " + n + " records, got " + repo.findAll().size());
    }

    @Test @Order(1)
    void storeScpDeliversFileToWatcherAndCreatesRecord() throws Exception {
        send("First^Name");
        awaitRecords(1, "First^Name");
        assertTrue(Files.exists(tmp.resolve("store").resolve(SOP + ".dcm")));
    }

    @Test @Order(2)
    void duplicateSopOverwritesRecordInsteadOfAddingNew() throws Exception {
        Long id = repo.findAll().get(0).getId();
        send("Second^Name");
        awaitRecords(1, "Second^Name");
        assertEquals(id, repo.findAll().get(0).getId());
    }

    @Test @Order(3)
    void cFindReturnsStoredInstance() throws Exception {
        Device d = clientDevice();
        ApplicationEntity ae = d.getApplicationEntity("CLIENT");
        AAssociateRQ rq = new AAssociateRQ();
        rq.setCalledAET("DCMQRSCP");
        rq.addPresentationContext(new PresentationContext(1, UID.StudyRootQueryRetrieveInformationModelFind,
                UID.ExplicitVRLittleEndian));
        Association as = ae.connect(new Connection(null, "localhost", qrPort), rq);
        Attributes keys = new Attributes();
        keys.setString(Tag.QueryRetrieveLevel, VR.CS, "IMAGE");
        keys.setString(Tag.StudyInstanceUID, VR.UI, "1.2.3.4");
        keys.setNull(Tag.SOPInstanceUID, VR.UI);
        keys.setNull(Tag.PatientName, VR.PN);
        List<Attributes> found = new CopyOnWriteArrayList<>();
        as.cfind(UID.StudyRootQueryRetrieveInformationModelFind, 0, keys, UID.ExplicitVRLittleEndian,
                new DimseRSPHandler(as.nextMessageID()) {
                    @Override
                    public void onDimseRSP(Association as, Attributes cmd, Attributes data) {
                        super.onDimseRSP(as, cmd, data);
                        if (Status.isPending(cmd.getInt(Tag.Status, -1)) && data != null) found.add(data);
                    }
                });
        as.waitForOutstandingRSP();
        as.release();
        assertEquals(1, found.size());
        assertEquals(SOP, found.get(0).getString(Tag.SOPInstanceUID));
        assertEquals("Second^Name", found.get(0).getString(Tag.PatientName));
    }

    @Test @Order(4)
    void cGetReturnsStoredInstance() throws Exception {
        Device d = clientDevice();
        ApplicationEntity ae = d.getApplicationEntity("CLIENT");
        AtomicInteger received = new AtomicInteger();
        DicomServiceRegistry reg = new DicomServiceRegistry();
        reg.addDicomService(new BasicCStoreSCP("*") {
            @Override
            protected void store(Association as, PresentationContext pc, Attributes rq, PDVInputStream data,
                                 Attributes rsp) throws java.io.IOException {
                Attributes ds = data.readDataset(pc.getTransferSyntax());
                assertEquals(SOP, ds.getString(Tag.SOPInstanceUID));
                received.incrementAndGet();
            }
        });
        d.setDimseRQHandler(reg);
        AAssociateRQ rq = new AAssociateRQ();
        rq.setCalledAET("DCMQRSCP");
        rq.addPresentationContext(new PresentationContext(1, UID.StudyRootQueryRetrieveInformationModelGet,
                UID.ExplicitVRLittleEndian));
        rq.addPresentationContext(new PresentationContext(3, CUID, UID.ExplicitVRLittleEndian));
        rq.addRoleSelection(new RoleSelection(CUID, false, true));
        Association as = ae.connect(new Connection(null, "localhost", qrPort), rq);
        Attributes keys = new Attributes();
        keys.setString(Tag.QueryRetrieveLevel, VR.CS, "STUDY");
        keys.setString(Tag.StudyInstanceUID, VR.UI, "1.2.3.4");
        as.cget(UID.StudyRootQueryRetrieveInformationModelGet, 0, keys, UID.ExplicitVRLittleEndian,
                new DimseRSPHandler(as.nextMessageID()));
        as.waitForOutstandingRSP();
        as.release();
        assertEquals(1, received.get());
    }

    /** Runs one storage commitment request against the given port/AET; returns {actionStatus, eventType, committed, failed}. */
    int[] commit(int port, String calledAet, String... sopUids) throws Exception {
        Device d = clientDevice();
        ApplicationEntity ae = d.getApplicationEntity("CLIENT");
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        int[] result = new int[4];
        DicomServiceRegistry reg = new DicomServiceRegistry();
        reg.addDicomService(new org.dcm4che3.net.service.AbstractDicomService(UID.StorageCommitmentPushModel) {
            @Override
            protected void onDimseRQ(Association as, PresentationContext pc, Dimse dimse, Attributes cmd, Attributes data)
                    throws java.io.IOException {
                if (dimse == Dimse.N_EVENT_REPORT_RQ) {
                    result[1] = cmd.getInt(Tag.EventTypeID, 0);
                    org.dcm4che3.data.Sequence ok = data.getSequence(Tag.ReferencedSOPSequence);
                    org.dcm4che3.data.Sequence bad = data.getSequence(Tag.FailedSOPSequence);
                    result[2] = ok == null ? 0 : ok.size();
                    result[3] = bad == null ? 0 : bad.size();
                    as.writeDimseRSP(pc, org.dcm4che3.net.Commands.mkNEventReportRSP(cmd, Status.Success), null);
                    latch.countDown();
                }
            }
        });
        d.setDimseRQHandler(reg);
        AAssociateRQ rq = new AAssociateRQ();
        rq.setCalledAET(calledAet);
        rq.addPresentationContext(new PresentationContext(1, UID.StorageCommitmentPushModel, UID.ImplicitVRLittleEndian));
        rq.addRoleSelection(new RoleSelection(UID.StorageCommitmentPushModel, true, true));
        Association as = ae.connect(new Connection(null, "localhost", port), rq);
        Attributes action = new Attributes();
        action.setString(Tag.TransactionUID, VR.UI, "1.2.3.99." + System.nanoTime());
        org.dcm4che3.data.Sequence seq = action.newSequence(Tag.ReferencedSOPSequence, sopUids.length);
        for (String u : sopUids) {
            Attributes item = new Attributes();
            item.setString(Tag.ReferencedSOPClassUID, VR.UI, CUID);
            item.setString(Tag.ReferencedSOPInstanceUID, VR.UI, u);
            seq.add(item);
        }
        as.naction(UID.StorageCommitmentPushModel, UID.StorageCommitmentPushModelInstance, 1, action,
                UID.ImplicitVRLittleEndian, new DimseRSPHandler(as.nextMessageID()) {
                    @Override
                    public void onDimseRSP(Association as, Attributes cmd, Attributes data) {
                        super.onDimseRSP(as, cmd, data);
                        result[0] = cmd.getInt(Tag.Status, -1);
                    }
                });
        assertTrue(latch.await(10, java.util.concurrent.TimeUnit.SECONDS), "no N-EVENT-REPORT received");
        as.waitForOutstandingRSP();
        as.release();
        return result;
    }

    @Test @Order(5)
    void storageCommitmentConfirmsKnownAndRejectsUnknownInstances() throws Exception {
        // known instance on the Store SCP (DICOMRECV) - the exact scenario from the reported error
        int[] r = commit(qrPort, "DCMQRSCP", SOP);
        assertEquals(Status.Success, r[0]);
        assertEquals(1, r[1]);
        assertEquals(1, r[2]);
        assertEquals(0, r[3]);
        // unknown instance -> failure event with reason 0112H
        r = commit(qrPort, "DCMQRSCP", SOP, "9.9.9.9");
        assertEquals(Status.Success, r[0]);
        assertEquals(2, r[1]);
        assertEquals(1, r[2]);
        assertEquals(1, r[3]);
    }

    @Test @Order(6)
    void dashboardShowsDeleteButtonAndDeleteRemovesRecord() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("confirm(")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(">Delete</button>")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/delete")));
        Long id = repo.findAll().get(0).getId();
        mvc.perform(post("/records/" + id + "/delete")).andExpect(status().is3xxRedirection());
        assertEquals(0, repo.count());
    }
}
