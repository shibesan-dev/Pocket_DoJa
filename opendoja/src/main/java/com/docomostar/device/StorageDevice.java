package com.docomostar.device;

import com.docomostar.fs.AccessToken;
import com.docomostar.fs.Folder;
import com.docomostar.fs.StarExceptions;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Defines a storage device in the Star API.
 * This shim delegates to {@code com.nttdocomo.device.StorageDevice} and
 * returns Star wrapper objects.
 */
public class StorageDevice {
    private static final Map<String, StorageDevice> DEVICES = new ConcurrentHashMap<>();

    private final com.nttdocomo.device.StorageDevice delegate;

    private StorageDevice(com.nttdocomo.device.StorageDevice delegate) {
        this.delegate = delegate;
    }

    /**
     * Gets the storage-device instance for the specified device name.
     *
     * @param deviceName the device name
     * @return the storage-device instance
     */
    public static StorageDevice getInstance(String deviceName) {
        return DEVICES.computeIfAbsent(deviceName,
                name -> new StorageDevice(com.nttdocomo.device.StorageDevice.getInstance(name)));
    }

    /**
     * Gets whether the medium is accessible.
     *
     * @return {@code true} if the medium is accessible
     */
    public boolean isAccessible() {
        return delegate.isAccessible();
    }

    /**
     * Gets whether the medium is removable.
     *
     * @return {@code true} if the medium is removable
     */
    public boolean isRemovable() {
        return delegate.isRemovable();
    }

    /**
     * Gets whether the medium is readable.
     *
     * @return {@code true} if the medium is readable
     */
    public boolean isReadable() {
        return delegate.isReadable();
    }

    /**
     * Gets whether the medium is writable.
     *
     * @return {@code true} if the medium is writable
     */
    public boolean isWritable() {
        return delegate.isWritable();
    }

    /**
     * Gets a folder corresponding to the specified access token.
     *
     * @param accessToken the access right
     * @return the folder instance
     * @throws IOException if the folder cannot be accessed
     */
    public Folder getFolder(AccessToken accessToken) throws IOException {
        if (accessToken == null) {
            throw new NullPointerException("accessToken");
        }
        try {
            return new Folder(delegate.getFolder(accessToken.toDoJaAccessToken()));
        } catch (IOException exception) {
            throw StarExceptions.translate(exception);
        }
    }
}
