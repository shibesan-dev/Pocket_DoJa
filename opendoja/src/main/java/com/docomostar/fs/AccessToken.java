package com.docomostar.fs;

/**
 * Defines an access right for accessing storage devices, folders, and files
 * through the Star storage API.
 * <p>
 * This is a compatibility shim over the DoJa storage implementation: the
 * wrapped token is passed to {@code com.nttdocomo.device.StorageDevice} when
 * resolving a folder.
 */
public class AccessToken {
    private final com.nttdocomo.fs.AccessToken delegate;

    AccessToken(com.nttdocomo.fs.AccessToken delegate) {
        this.delegate = delegate;
    }

    /**
     * Gets the corresponding DoJa access token.
     *
     * @return the DoJa access token backing this Star token
     */
    public com.nttdocomo.fs.AccessToken toDoJaAccessToken() {
        return delegate;
    }
}
