package com.docomostar.fs;

import java.io.IOException;

/**
 * Indicates that writing failed because there was no free space.
 */
public class FileSystemFullException extends IOException {
    /**
     * Creates an exception object without a detail message.
     */
    public FileSystemFullException() {
    }

    /**
     * Creates an exception object with a detail message.
     *
     * @param message the detail message
     */
    public FileSystemFullException(String message) {
        super(message);
    }
}
