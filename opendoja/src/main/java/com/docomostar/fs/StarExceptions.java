package com.docomostar.fs;

import java.io.IOException;

/**
 * Translates DoJa storage exceptions into their Star counterparts so that
 * Star applications can catch the exception types they expect.
 */
public final class StarExceptions {
    private StarExceptions() {
    }

    /**
     * Translates a DoJa storage exception into an equivalent Star exception.
     * Unknown exception types are returned unchanged.
     *
     * @param exception the exception to translate
     * @return the translated exception
     */
    public static IOException translate(IOException exception) {
        if (exception instanceof com.nttdocomo.fs.FileNotAccessibleException source) {
            return new FileNotAccessibleException(source.getStatus(), source.getMessage());
        }
        if (exception instanceof com.nttdocomo.fs.MediaNotFoundException) {
            return new MediaNotFoundException(exception.getMessage());
        }
        if (exception instanceof com.nttdocomo.fs.FileSystemFullException) {
            return new FileSystemFullException(exception.getMessage());
        }
        return exception;
    }
}
