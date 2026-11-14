package com.docomostar.ui.graphics3d;

import com.docomostar.ui.util3d.Transform;

/**
 * Abstract base class for 3D objects that can be rendered and can participate
 * in collision testing.
 */
public abstract class DrawableObject3D extends Object3D {
    /**
     * Blend mode for normal drawing with no blending.
     */
    public static final int BLEND_NORMAL = 0;
    /**
     * Blend mode for alpha blending.
     */
    public static final int BLEND_ALPHA = 32;
    /**
     * Blend mode for additive blending.
     */
    public static final int BLEND_ADD = 64;

    /**
     * Initializes a drawable 3D object wrapper.
     *
     * @param delegate the DoJa implementation object
     */
    DrawableObject3D(com.nttdocomo.ui.graphics3d.DrawableObject3D delegate) {
        super(delegate);
    }

    final com.nttdocomo.ui.graphics3d.DrawableObject3D drawableDelegate() {
        return (com.nttdocomo.ui.graphics3d.DrawableObject3D) delegate();
    }

    /**
     * Tests this drawable object against another drawable object.
     *
     * @param other the other drawable object
     * @param thisTransform the transform applied to this object
     * @param otherTransform the transform applied to the other object
     * @return {@code true} if the objects intersect
     */
    public boolean isCross(DrawableObject3D other, Transform thisTransform, Transform otherTransform) {
        return drawableDelegate().isCross(other.drawableDelegate(), thisTransform, otherTransform);
    }

    /**
     * Enables or disables perspective correction.
     *
     * @param enabled {@code true} to enable perspective correction
     */
    public void setPerspectiveCorrectionEnabled(boolean enabled) {
        drawableDelegate().setPerspectiveCorrectionEnabled(enabled);
    }

    /**
     * Sets the primitive blend mode.
     *
     * @param blendMode the blend mode
     */
    public void setBlendMode(int blendMode) {
        drawableDelegate().setBlendMode(blendMode);
    }

    /**
     * Sets the transparency percentage.
     *
     * @param transparency the transparency value
     */
    public void setTransparency(float transparency) {
        drawableDelegate().setTransparency(transparency);
    }
}
