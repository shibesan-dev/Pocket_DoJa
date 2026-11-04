package com.docomostar.util;

/**
 * Star compatibility shim delegating to {@code com.nttdocomo.util.JarInflater}.
 */
public class JarInflater extends com.nttdocomo.util.JarInflater {
    public JarInflater(java.io.InputStream inputStream)
            throws com.nttdocomo.util.JarFormatException, java.io.IOException {
        super(inputStream);
    }

    public JarInflater(byte[] data) throws com.nttdocomo.util.JarFormatException {
        super(data);
    }
}