package com.docomostar.io;

import com.docomostar.fs.StarExceptions;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Defines a file entity in the Star API.
 * This shim delegates to {@code com.nttdocomo.io.FileEntity}.
 */
public class FileEntity {
    private final com.nttdocomo.io.FileEntity delegate;

    public FileEntity(com.nttdocomo.io.FileEntity delegate) {
        this.delegate = delegate;
    }

    /**
     * Opens an input stream for reading the file.
     *
     * @return the input stream
     * @throws IOException if an I/O error occurs
     */
    public InputStream openInputStream() throws IOException {
        try {
            return delegate.openInputStream();
        } catch (IOException exception) {
            throw StarExceptions.translate(exception);
        }
    }

    /**
     * Opens an output stream for writing the file.
     *
     * @return the output stream
     * @throws IOException if an I/O error occurs
     */
    public OutputStream openOutputStream() throws IOException {
        try {
            return delegate.openOutputStream();
        } catch (IOException exception) {
            throw StarExceptions.translate(exception);
        }
    }

    /**
     * Closes the file.
     *
     * @throws IOException if an I/O error occurs
     */
    public void close() throws IOException {
        try {
            delegate.close();
        } catch (IOException exception) {
            throw StarExceptions.translate(exception);
        }
    }
}
