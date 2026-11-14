package com.docomostar.ui.util3d;

/**
 * Star compatibility view of the DoJa three-dimensional vector.
 */
public class Vector3D extends com.nttdocomo.ui.util3d.Vector3D {
    /**
     * Creates a vector whose components are all {@code 0}.
     */
    public Vector3D() {
        super();
    }

    /**
     * Creates a copy of another vector.
     *
     * @param vector the source vector
     */
    public Vector3D(Vector3D vector) {
        super(vector);
    }

    /**
     * Creates a vector with the specified components.
     *
     * @param x the x component
     * @param y the y component
     * @param z the z component
     */
    public Vector3D(float x, float y, float z) {
        super(x, y, z);
    }

    /**
     * Sets all components from another vector.
     *
     * @param vector the source vector
     */
    public void set(Vector3D vector) {
        super.set(vector);
    }

    /**
     * Adds another vector to this vector.
     *
     * @param vector the vector to add
     */
    public void add(Vector3D vector) {
        super.add(vector);
    }

    /**
     * Calculates the dot product with another vector.
     *
     * @param vector the other vector
     * @return the dot product
     */
    public float dot(Vector3D vector) {
        return super.dot(vector);
    }

    /**
     * Calculates the dot product of two vectors.
     *
     * @param first the first vector
     * @param second the second vector
     * @return the dot product
     */
    public static float dot(Vector3D first, Vector3D second) {
        return com.nttdocomo.ui.util3d.Vector3D.dot(first, second);
    }

    /**
     * Replaces this vector with its cross product with another vector.
     *
     * @param vector the other vector
     */
    public void cross(Vector3D vector) {
        super.cross(vector);
    }

    /**
     * Sets this vector to the cross product of two vectors.
     *
     * @param first the first vector
     * @param second the second vector
     */
    public void cross(Vector3D first, Vector3D second) {
        super.cross(first, second);
    }
}
