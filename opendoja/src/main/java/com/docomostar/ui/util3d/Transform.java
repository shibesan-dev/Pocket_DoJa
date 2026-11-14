package com.docomostar.ui.util3d;

/**
 * Star compatibility view of the DoJa 4x4 transformation matrix.
 */
public class Transform extends com.nttdocomo.ui.util3d.Transform {
    /**
     * Creates a transform initialized to the identity matrix.
     */
    public Transform() {
        super();
    }

    /**
     * Creates a transform by copying another transform.
     *
     * @param transform the source transform
     */
    public Transform(Transform transform) {
        super(transform);
    }

    /**
     * Replaces this matrix with a copy of another transform.
     *
     * @param transform the source transform
     */
    public void set(Transform transform) {
        super.set(transform);
    }

    /**
     * Post-multiplies this matrix by another transform.
     *
     * @param transform the transform to multiply by
     */
    public void multiply(Transform transform) {
        super.multiply(transform);
    }

    /**
     * Scales this matrix by the components of a vector.
     *
     * @param vector the scale vector
     */
    public void scale(Vector3D vector) {
        super.scale(vector);
    }

    /**
     * Rotates this matrix about an axis vector.
     *
     * @param vector the rotation axis
     * @param angle the rotation angle
     */
    public void rotate(Vector3D vector, float angle) {
        super.rotate(vector, angle);
    }

    /**
     * Rotates this matrix using a quaternion axis vector.
     *
     * @param vector the rotation axis
     * @param w the quaternion w component
     */
    public void rotateQuat(Vector3D vector, float w) {
        super.rotateQuat(vector, w);
    }

    /**
     * Translates this matrix by a vector.
     *
     * @param vector the translation vector
     */
    public void translate(Vector3D vector) {
        super.translate(vector);
    }

    /**
     * Sets this matrix to a view transform looking from a position toward a
     * target.
     *
     * @param position the eye position
     * @param look the look-at target
     * @param up the up vector
     */
    public void lookAt(Vector3D position, Vector3D look, Vector3D up) {
        super.lookAt(position, look, up);
    }

    /**
     * Transforms a vector by this matrix.
     *
     * @param vector the source vector
     * @param result the destination vector
     */
    public void transVector(Vector3D vector, Vector3D result) {
        super.transVector(vector, result);
    }
}
