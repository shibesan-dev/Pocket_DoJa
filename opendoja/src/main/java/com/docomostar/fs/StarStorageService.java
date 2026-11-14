package com.docomostar.fs;

/**
 * Star storage service compatibility shim.
 * Applications obtain an access token here and pass it to
 * {@code com.docomostar.device.StorageDevice.getFolder(AccessToken)}.
 */
public final class StarStorageService {
    /** Indicates sharing only within the same application (=0x08). */
    public static final int SHARE_APPLICATION = 0x08;

    /** Indicates sharing only within the same contents provider (=0x10). */
    public static final int SHARE_CONTENTS_PROVIDER = 0x10;

    private StarStorageService() {
    }

    /**
     * Gets an access token for the specified access and sharing identifiers.
     *
     * @param access the access identifier
     * @param share the sharing identifier
     * @return the access token, or {@code null} if no token can be obtained
     */
    public static StarAccessToken getAccessToken(int access, int share) {
        try {
            return new StarAccessToken(
                    com.nttdocomo.fs.DoJaStorageService.getAccessToken(access, share));
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
