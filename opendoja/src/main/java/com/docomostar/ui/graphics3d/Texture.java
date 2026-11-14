package com.docomostar.ui.graphics3d;

/**
 * Defines the texture object used by graphics3d figures and primitives.
 */
public class Texture extends Object3D {
    /**
     * Blend mode that modulates the texture with the primitive color.
     */
    public static final int MODULATE = 0;
    /**
     * Blend mode that replaces the primitive color with the texture color.
     */
    public static final int REPLACE = 1;

    Texture(com.nttdocomo.ui.graphics3d.Texture delegate) {
        super(delegate);
    }

    private com.nttdocomo.ui.graphics3d.Texture texture() {
        return (com.nttdocomo.ui.graphics3d.Texture) delegate();
    }

    /**
     * Sets the texture blend mode.
     *
     * @param blendMode the blend mode
     */
    public void setBlendMode(int blendMode) {
        texture().setBlendMode(blendMode);
    }

    /**
     * Enables or disables environment mapping for this texture.
     *
     * @param enabled {@code true} to enable environment mapping
     */
    public void setEnvironmentMapEnabled(boolean enabled) {
        texture().setEnvironmentMapEnabled(enabled);
    }

    /**
     * Sets the texture used for environment mapping.
     *
     * @param texture the environment-map texture
     */
    public void setEnvironmentMapTexture(Texture texture) {
        texture().setEnvironmentMapTexture(texture == null
                ? null
                : (com.nttdocomo.ui.graphics3d.Texture) texture.delegate());
    }
}
