package com.eh.digitalpathology.dicomreceiver.service.storescp;

import com.eh.digitalpathology.dicomreceiver.constants.StorageObjectRef;
import com.eh.digitalpathology.dicomreceiver.enums.StorageType;
import com.eh.digitalpathology.dicomreceiver.api.DicomObjectSink;
import com.eh.digitalpathology.dicomreceiver.service.MetadataDispatcher;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.dcm4che3.net.Association;
import org.dcm4che3.net.PDVInputStream;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.pdu.PresentationContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith( MockitoExtension.class )
class DicomStoreSCPServiceTest {


    @Mock
    private MetadataDispatcher metadataDispatcher;

    @Mock
    private DicomObjectSink sink;

    @InjectMocks
    private DicomStoreSCPService service;

    @Test
    void store_successfulUpload_shouldWriteDataAndDispatchMetadata ( ) throws Exception {
        // -------- Arrange --------
        String sopUid = "1.2.3.4";
        String sopClassUid = "1.2.840.10008";
        String transferSyntaxUid = "1.2.840.10008.1.2";
        String objectName = sopUid + ".dcm";

        // Real Attributes object
        Attributes rq = new Attributes( );
        rq.setString( Tag.AffectedSOPInstanceUID, VR.UI, sopUid );
        rq.setString( Tag.AffectedSOPClassUID, VR.UI, sopClassUid );
        rq.setString( Tag.TransferSyntaxUID, VR.UI, transferSyntaxUid );

        PresentationContext presentationContext = mock( PresentationContext.class );
        when( presentationContext.getTransferSyntax() ).thenReturn( transferSyntaxUid );

        Association association = mock(Association.class);

        Attributes fmi = new Attributes();
        fmi.setString(Tag.MediaStorageSOPClassUID, VR.UI, sopClassUid);
        fmi.setString(Tag.MediaStorageSOPInstanceUID, VR.UI, sopUid);
        fmi.setString(Tag.TransferSyntaxUID, VR.UI, transferSyntaxUid);
        fmi.setString(Tag.ImplementationClassUID, VR.UI, "1.2.826.0.1.3680043.10.999");
        fmi.setString(Tag.ImplementationVersionName, VR.SH, "TEST_1_0");

        when(association.createFileMetaInformation(
                eq(sopUid),
                eq(sopClassUid),
                eq(transferSyntaxUid)
        )).thenReturn(fmi);

        Attributes rsp = new Attributes( );

        // Mock PDVInputStream
        PDVInputStream pdvInputStream = mock( PDVInputStream.class );
        when( pdvInputStream.read( any( byte[].class ) ) ).thenReturn( 5 ).thenReturn( -1 );

        // OutputStream to capture writes
        OutputStream outputStream = spy( new ByteArrayOutputStream( ) );
        when( sink.openStream( objectName ) ).thenReturn( outputStream );

        StorageObjectRef ref = new StorageObjectRef( StorageType.GCS, "bucket", objectName );
        when( sink.objectRef( objectName ) ).thenReturn( ref );

        // -------- Act --------
        service.store( association,presentationContext, rq, pdvInputStream, rsp );

        // -------- Assert --------
        verify( sink ).openStream( objectName );
        verify( outputStream, atLeastOnce( ) ).write( any( byte[].class ), anyInt( ), anyInt( ) );
        verify( metadataDispatcher ).submit( ref );

        assertEquals( Status.Success, rsp.getInt( Tag.Status, -1 ) );
    }

    @Test
    void store_whenOpenStreamFails_shouldNotDispatchMetadata ( ) throws Exception {
        // -------- Arrange --------
        String sopUid = "1.2.3.4";
        String objectName = sopUid + ".dcm";

        Attributes rq = new Attributes( );
        rq.setString( Tag.AffectedSOPInstanceUID, VR.UI, sopUid );

        Attributes rsp = new Attributes( );

        PDVInputStream data = mock( PDVInputStream.class );

        lenient().when( sink.openStream( objectName ) ).thenThrow( new IOException( "GCS failure" ) );

        // -------- Act & Assert --------
        assertThrows( IOException.class, ( ) -> service.store( mock( Association.class ), mock( PresentationContext.class ), rq, data, rsp ) );

        verify( metadataDispatcher, never( ) ).submit( any( ) );
    }


}