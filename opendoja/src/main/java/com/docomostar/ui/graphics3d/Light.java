package com.docomostar.ui.graphics3d;

import com.docomostar.ui.util3d.Vector3D;

/**
 * Defines a light source used by the graphics3d renderer.
 */
public class Light extends Object3D {
    /**
     * Ambient-light mode.
     */
    public static final int AMBIENT = 128;
    /**
     * Directional-light mode.
     */
    public static final int DIRECTIONAL = 129;
    /**
     * Omni-directional point-light mode.
     */
    public static final int OMNI = 130;
    /**
     * Spot-light mode.
     */
    public static final int SPOT = 131;

    /**
     * Creates a light object.
     */
    public Light() {
        super(new com.nttdocomo.ui.graphics3d.Light());
    }

    Light(com.nttdocomo.ui.graphics3d.Light delegate) {
        super(delegate);
    }

    private com.nttdocomo.ui.graphics3d.Light light() {
        return (com.nttdocomo.ui.graphics3d.Light) delegate();
    }

    /**
     * Sets the light position.
     *
     * @param position the light position
     */
    public void setPosition(Vector3D position) {
        light().setPosition(position);
    }

    /**
     * Gets the light position.
     *
     * @return a copy of the light position
     */
    public Vector3D getPosition() {
        com.nttdocomo.ui.util3d.Vector3D position = light().getPosition();
        return new Vector3D(position.getX(), position.getY(), position.getZ());
    }

    /**
     * Sets the light direction vector.
     *
     * @param vector the direction vector
     */
    public void setVector(Vector3D vector) {
        light().setVector(vector);
    }

    /**
     * Gets the light direction vector.
     *
     * @return a copy of the light direction vector
     */
    public Vector3D getVector() {
        com.nttdocomo.ui.util3d.Vector3D vector = light().getVector();
        return new Vector3D(vector.getX(), vector.getY(), vector.getZ());
    }

    /**
     * Sets the light mode.
     *
     * @param mode the light mode
     */
    public void setMode(int mode) {
        light().setMode(mode);
    }

    /**
     * Sets the light intensity.
     *
     * @param intensity the light intensity
     */
    public void setIntensity(float intensity) {
        light().setIntensity(intensity);
    }

    /**
     * Sets the light color.
     *
     * @param color the light color
     */
    public void setColor(int color) {
        light().setColor(color);
    }

    /**
     * Sets the spotlight angle.
     *
     * @param spotAngle the spotlight angle
     */
    public void setSpotAngle(float spotAngle) {
        light().setSpotAngle(spotAngle);
    }

    /**
     * Sets the spotlight exponent.
     *
     * @param spotExponent the spotlight exponent
     */
    public void setSpotExponent(float spotExponent) {
        light().setSpotExponent(spotExponent);
    }

    /**
     * Sets the attenuation coefficients.
     *
     * @param constant the constant term
     * @param linear the linear term
     * @param quadratic the quadratic term
     */
    public void setAttenuation(float constant, float linear, float quadratic) {
        light().setAttenuation(constant, linear, quadratic);
    }

    /**
     * Gets the maximum number of simultaneously supported lights.
     *
     * @return the maximum light count
     */
    public static int getMaxLights() {
        return com.nttdocomo.ui.graphics3d.Light.getMaxLights();
    }
}
