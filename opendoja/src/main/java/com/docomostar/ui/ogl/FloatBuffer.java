package com.docomostar.ui.ogl;

/**
 * Star compatibility view of a float direct buffer.
 */
public interface FloatBuffer extends com.nttdocomo.ui.ogl.FloatBuffer, DirectBuffer {
    /**
     * Star-typed bridge for {@link com.nttdocomo.ui.ogl.FloatBuffer#madd}.
     *
     * @param src1 the first source buffer
     * @param src2 the second source buffer
     * @param multiplier the multiplier applied to the second source
     * @return this buffer
     */
    default FloatBuffer madd(FloatBuffer src1, FloatBuffer src2, float multiplier) {
        return (FloatBuffer) ((com.nttdocomo.ui.ogl.FloatBuffer) this).madd(src1, src2, multiplier);
    }

    /**
     * Star-typed bridge for {@link com.nttdocomo.ui.ogl.FloatBuffer#transform}.
     *
     * @param src the source buffer
     * @param matrix the transform matrix
     * @param itemSize the number of components per item
     * @param itemCount the number of items
     * @return this buffer
     */
    default FloatBuffer transform(FloatBuffer src, com.docomostar.ui.ogl.math.Matrix4f matrix,
                                  int itemSize, int itemCount) {
        return (FloatBuffer) ((com.nttdocomo.ui.ogl.FloatBuffer) this).transform(src, matrix, itemSize, itemCount);
    }
}
