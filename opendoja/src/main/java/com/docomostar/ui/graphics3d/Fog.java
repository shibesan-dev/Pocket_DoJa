package com.docomostar.ui.graphics3d;

/**
 * Defines the data that controls a fog effect.
 */
public class Fog extends Object3D {
    /**
     * Exponential fog mode.
     */
    public static final int EXPONENTIAL = 80;
    /**
     * Linear fog mode.
     */
    public static final int LINEAR = 81;

    /**
     * Creates a fog object.
     */
    public Fog() {
        super(new com.nttdocomo.ui.graphics3d.Fog());
    }

    Fog(com.nttdocomo.ui.graphics3d.Fog delegate) {
        super(delegate);
    }

    private com.nttdocomo.ui.graphics3d.Fog fog() {
        return (com.nttdocomo.ui.graphics3d.Fog) delegate();
    }

    /**
     * Sets the fog mode.
     *
     * @param mode the fog mode, either {@link #EXPONENTIAL} or {@link #LINEAR}
     */
    public void setMode(int mode) {
        fog().setMode(mode);
    }

    /**
     * Sets the near and far distances used by linear fog mode.
     *
     * @param near the start distance
     * @param far the end distance
     */
    public void setLinear(float near, float far) {
        fog().setLinear(near, far);
    }

    /**
     * Sets the density used by exponential fog mode.
     *
     * @param density the fog density
     */
    public void setDensity(float density) {
        fog().setDensity(density);
    }

    /**
     * Sets the fog color in {@code 0x00RRGGBB} form.
     *
     * @param color the fog color
     */
    public void setColor(int color) {
        fog().setColor(color);
    }
}
