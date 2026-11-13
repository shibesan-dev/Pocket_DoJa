package com.docomostar.ui.ogl.math;

/**
 * Star compatibility view of the DoJa 4x4 float matrix.
 */
public final class Matrix4f extends com.nttdocomo.ui.ogl.math.Matrix4f {
    /**
     * Creates a zero-filled matrix.
     */
    public Matrix4f() {
        super();
    }

    /**
     * Creates a copy of the specified matrix.
     *
     * @param matrix the matrix to copy
     */
    public Matrix4f(Matrix4f matrix) {
        super(matrix);
    }

    /**
     * Sets this matrix to the product of two matrices.
     *
     * @param left the left matrix
     * @param right the right matrix
     */
    public void mul(Matrix4f left, Matrix4f right) {
        super.mul(left, right);
    }

    /**
     * Post-multiplies this matrix by the specified matrix.
     *
     * @param matrix the matrix to multiply by
     */
    public void mul(Matrix4f matrix) {
        super.mul(matrix);
    }

    @Override
    public void invert() {
        super.invert();
    }

    @Override
    public void setIdentity() {
        super.setIdentity();
    }
}
