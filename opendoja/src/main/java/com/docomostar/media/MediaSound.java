package com.docomostar.media;

public interface MediaSound extends com.nttdocomo.ui.MediaSound {
    /**
     * Star-typed bridge for the reusable-resource form of {@code use}.
     *
     * @param overwritten the media resource whose storage should be reused
     * @param exclusive whether exclusive use is requested
     * @throws com.nttdocomo.io.ConnectionException if use fails
     */
    default void use(MediaResource overwritten, boolean exclusive)
            throws com.nttdocomo.io.ConnectionException {
        use((com.nttdocomo.ui.MediaResource) overwritten, exclusive);
    }
}
