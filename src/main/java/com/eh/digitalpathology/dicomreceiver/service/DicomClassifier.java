package com.eh.digitalpathology.dicomreceiver.service;

import com.eh.digitalpathology.dicomreceiver.config.SopClassPolicy;
import com.eh.digitalpathology.dicomreceiver.enums.DicomKind;
import org.apache.commons.vfs2.FileObject;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.io.DicomInputStream;
import org.springframework.stereotype.Component;

import java.io.InputStream;

@Component
public class DicomClassifier {


    private final SopClassPolicy sopClassPolicy;

    public DicomClassifier ( SopClassPolicy sopClassPolicy ) {
        this.sopClassPolicy = sopClassPolicy;
    }

    public DicomKind classify ( FileObject file ) {
        String ext = file.getName().getExtension();
        if ("svs".equalsIgnoreCase( ext )){
            return DicomKind.SVS;
        }
        try ( InputStream is = file.getContent( ).getInputStream( ); DicomInputStream dis = new DicomInputStream( is ) ) {

            dis.setIncludeBulkData( DicomInputStream.IncludeBulkData.NO );

            Attributes fmi = dis.readFileMetaInformation( );
            if ( UID.MediaStorageDirectoryStorage.equals( fmi.getString( Tag.MediaStorageSOPClassUID ) ) ) {
                return DicomKind.DICOMDIR;
            }

            Attributes attrs = dis.readDatasetUntilPixelData( );
            String sopClassUid = attrs.getString( Tag.SOPClassUID );

            if ( sopClassUid == null ) return DicomKind.NON_DICOM;
            if ( sopClassPolicy.isAllowed( sopClassUid ) ) return DicomKind.WSI;

            return DicomKind.DICOM;

        } catch ( Exception e ) {
            return DicomKind.NON_DICOM;
        }
    }

}
