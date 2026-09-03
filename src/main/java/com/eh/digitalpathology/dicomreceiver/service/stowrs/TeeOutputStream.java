package com.eh.digitalpathology.dicomreceiver.service.stowrs;

import java.io.IOException;
import java.io.OutputStream;

public class TeeOutputStream extends OutputStream {

    private final OutputStream main;
    private final OutputStream branch;

    public TeeOutputStream(OutputStream main, OutputStream branch) {
        this.main = main;
        this.branch = branch;
    }

    @Override
    public void write(int b) throws IOException {
        main.write(b);
        branch.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        main.write(b, off, len);
        branch.write(b, off, len);
    }

    @Override
    public void flush() throws IOException {
        main.flush();
        branch.flush();
    }

    @Override
    public void close() throws IOException {
        try {
            main.close();
        } finally {
            branch.close();
        }
    }
}