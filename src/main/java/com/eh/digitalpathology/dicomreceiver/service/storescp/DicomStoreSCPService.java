package com.eh.digitalpathology.dicomreceiver.service.storescp;

import com.eh.digitalpathology.dicomreceiver.api.DicomObjectSink;
import com.eh.digitalpathology.dicomreceiver.service.MetadataDispatcher;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomOutputStream;
import org.dcm4che3.net.Association;
import org.dcm4che3.net.PDVInputStream;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.pdu.PresentationContext;
import org.dcm4che3.net.service.BasicCStoreSCP;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;


@Service
public class DicomStoreSCPService extends BasicCStoreSCP {

    private final MetadataDispatcher metadataDispatcher;
    private final DicomObjectSink sink;

    public DicomStoreSCPService ( MetadataDispatcher metadataDispatcher, DicomObjectSink sink ) {
        super( "*" );
        this.metadataDispatcher = metadataDispatcher;
        this.sink = sink;
    }


    @Override
    protected void store ( Association as, PresentationContext pc, Attributes rq, PDVInputStream data, Attributes rsp ) throws IOException {
        String sopClassUID = rq.getString( Tag.AffectedSOPClassUID );
        String sopInstanceUID = rq.getString( Tag.AffectedSOPInstanceUID );
        String transferSyntaxUID = pc.getTransferSyntax( );

        if ( sopClassUID == null || sopInstanceUID == null || transferSyntaxUID == null ) {
            rsp.setInt( Tag.Status, VR.US, Status.CannotUnderstand );
            throw new IOException( "Missing required C-STORE attributes. sopClassUID=" + sopClassUID + ", sopInstanceUID=" + sopInstanceUID + ", transferSyntaxUID=" + transferSyntaxUID );
        }
        /*
         * C-STORE sends only the DICOM dataset.
         * It does NOT include Part-10 File Meta Information group 0002.
         *
         * Therefore, before storing to GCS/file system, we must write:
         * - 128-byte preamble
         * - DICM marker
         * - 0002 File Meta Information
         * - actual dataset stream
         */
        Attributes fileMetaInformation = as.createFileMetaInformation( sopInstanceUID, sopClassUID, transferSyntaxUID );

        String objectName = sopInstanceUID + ".dcm";
        // ✅ Stream C‑STORE payload directly to GCS
        try ( OutputStream out = sink.openStream( objectName ); DicomOutputStream dos = new DicomOutputStream( out, UID.ExplicitVRLittleEndian ) ) {
            dos.writeFileMetaInformation( fileMetaInformation );
            byte[] buf = new byte[ 32 * 1024 * 1024 ];
            int r;
            while ( ( r = data.read( buf ) ) != -1 ) {
                dos.write( buf, 0, r );
            }
        } catch ( IOException e ) {
            rsp.setInt( Tag.Status, VR.US, Status.ProcessingFailure );
            throw new IOException( "Failed to store DICOM object to sink: " + e.getMessage( ) );
        }
        // ✅ Upload is guaranteed complete here
        // ✅ Hand off metadata extraction asynchronously
        metadataDispatcher.submit( sink.objectRef( objectName ) );
        rsp.setInt( Tag.Status, VR.US, Status.Success );

    }
}