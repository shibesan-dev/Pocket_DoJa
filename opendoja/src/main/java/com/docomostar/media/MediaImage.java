package com.docomostar.media;

public interface MediaImage extends com.nttdocomo.ui.MediaImage {
    @Override
    com.docomostar.ui.Image getImage();

    /**
     * Star-typed bridge for the reusable-resource form of {@code use}.
     *
     * @param overwritten the media image whose storage should be reused
     * @param useOnce whether this media image is for one-time use
     * @throws com.nttdocomo.io.ConnectionException if use fails
     */
    default void use(MediaResource overwritten, boolean useOnce)
            throws com.nttdocomo.io.ConnectionException {
        use((com.nttdocomo.ui.MediaResource) overwritten, useOnce);
    }
}
