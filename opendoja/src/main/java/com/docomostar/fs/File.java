package com.docomostar.fs;

import java.io.IOException;

/**
 * Defines a file in the Star storage API.
 * This shim delegates to {@code com.nttdocomo.fs.File}.
 */
public class File {
    /** Mode indicating that the file is opened read-only (=0). */
    public static final int MODE_READ_ONLY = 0;

    /** Mode indicating that the file is opened write-only (=1). */
    public static final int MODE_WRITE_ONLY = 1;

    /** Mode indicating that the file is opened for reading and writing (=2). */
    public static final int MODE_READ_WRITE = 2;

    private final com.nttdocomo.fs.File delegate;

    public File(com.nttdocomo.fs.File delegate) {
        this.delegate = delegate;
    }

    /**
     * Opens the file.
     *
     * @param mode the open mode
     * @return the file entity
     * @throws IOException if the file cannot be accessed
     */
    public com.docomostar.io.FileEntity open(int mode) throws IOException {
        try {
            return new com.docomostar.io.FileEntity(delegate.open(mode));
        } catch (IOException exception) {
            throw StarExceptions.translate(exception);
        }
    }

    /**
     * Deletes the file.
     *
     * @throws IOException if the file cannot be deleted
     */
    public void delete() throws IOException {
        try {
            delegate.delete();
        } catch (IOException exception) {
            throw StarExceptions.translate(exception);
        }
    }

    /**
     * Gets the file size in bytes.
     *
     * @return the file size in bytes
     * @throws IOException if the file cannot be accessed
     */
    public long getLength() throws IOException {
        try {
            return delegate.getLength();
        } catch (IOException exception) {
            throw StarExceptions.translate(exception);
        }
    }
}
