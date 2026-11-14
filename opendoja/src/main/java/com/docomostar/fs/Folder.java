package com.docomostar.fs;

import java.io.IOException;

/**
 * Defines a folder in the Star storage API.
 * This shim delegates to {@code com.nttdocomo.fs.Folder}.
 */
public class Folder {
    private final com.nttdocomo.fs.Folder delegate;

    public Folder(com.nttdocomo.fs.Folder delegate) {
        this.delegate = delegate;
    }

    /**
     * Gets the file with the specified name.
     *
     * @param fileName the file name
     * @return the file instance
     * @throws IOException if the file cannot be accessed
     */
    public File getFile(String fileName) throws IOException {
        try {
            return new File(delegate.getFile(fileName));
        } catch (IOException exception) {
            throw StarExceptions.translate(exception);
        }
    }

    /**
     * Creates a file with the specified name.
     *
     * @param fileName the file name
     * @return the created file instance
     * @throws IOException if the file cannot be created
     */
    public File createFile(String fileName) throws IOException {
        try {
            return new File(delegate.createFile(fileName));
        } catch (IOException exception) {
            throw StarExceptions.translate(exception);
        }
    }

    /**
     * Gets the free space of this folder in bytes.
     *
     * @return the free space in bytes
     * @throws IOException if an I/O error occurs
     */
    public long getFreeSize() throws IOException {
        try {
            return delegate.getFreeSize();
        } catch (IOException exception) {
            throw StarExceptions.translate(exception);
        }
    }
}
