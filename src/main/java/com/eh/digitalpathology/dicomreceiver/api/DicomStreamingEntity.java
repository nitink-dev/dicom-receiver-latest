package com.eh.digitalpathology.dicomreceiver.api;

import org.apache.http.entity.AbstractHttpEntity;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.io.DicomOutputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class DicomStreamingEntity extends AbstractHttpEntity {
    private final Attributes fileMetaInformation;
    private final Attributes dataset;

    public DicomStreamingEntity ( Attributes fileMetaInformation, Attributes dataset ) {

        this.fileMetaInformation = fileMetaInformation;
        this.dataset = dataset;
        setContentType( "application/dicom" );
    }

    @Override
    public void writeTo ( OutputStream outputStream ) throws IOException {
        try ( DicomOutputStream dos = new DicomOutputStream( outputStream, "" ) ) {
            dos.writeDataset( fileMetaInformation, dataset );
        }
    }

    @Override
    public boolean isRepeatable ( ) {
        return false;
    }

    @Override
    public long getContentLength ( ) {
        return -1;
    }

    @Override
    public InputStream getContent ( ) {
        throw new UnsupportedOperationException( );
    }

    @Override
    public boolean isStreaming ( ) {
        return true;
    }
}
