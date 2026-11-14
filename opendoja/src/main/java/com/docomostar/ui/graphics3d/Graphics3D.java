package com.docomostar.ui.graphics3d;

import com.docomostar.ui.util3d.Transform;

/**
 * Defines the 3D drawing operations available from a Star
 * {@link com.docomostar.ui.Graphics} instance.
 */
public interface Graphics3D {
    /**
     * Sets the clipping rectangle used for 3D drawing.
     *
     * @param x the left edge
     * @param y the top edge
     * @param width the rectangle width
     * @param height the rectangle height
     */
    void setClipRectFor3D(int x, int y, int width, int height);

    /**
     * Configures a parallel projection.
     *
     * @param width the view width
     * @param height the view height
     */
    void setParallelView(int width, int height);

    /**
     * Configures a perspective projection.
     *
     * @param a the projection parameter
     * @param b the projection parameter
     * @param c the near parameter
     * @param d the far parameter
     */
    void setPerspectiveView(float a, float b, int c, int d);

    /**
     * Configures a perspective projection.
     *
     * @param a the projection parameter
     * @param b the projection parameter
     * @param c the projection parameter
     */
    void setPerspectiveView(float a, float b, float c);

    /**
     * Applies queued 3D drawing to the graphics surface.
     */
    void flushBuffer();

    /**
     * Sets the current view transform.
     *
     * @param transform the view transform
     */
    void setTransform(Transform transform);

    /**
     * Adds a light source for subsequent 3D drawing.
     *
     * @param light the light source
     * @param transform the light transform, or {@code null}
     */
    void addLight(Light light, Transform transform);

    /**
     * Removes all previously added light sources.
     */
    void resetLights();

    /**
     * Sets the fog applied to subsequent 3D drawing.
     *
     * @param fog the fog object, or {@code null}
     */
    void setFog(Fog fog);

    /**
     * Renders a drawable 3D object.
     *
     * @param object the object to render
     * @param transform the object transform, or {@code null}
     */
    void renderObject3D(DrawableObject3D object, Transform transform);
}
