package com.docomostar.ui.ogl;

/**
 * Star compatibility view of the DoJa OpenGL ES 1.x drawing interface.
 * The Star buffer types are subtypes of the DoJa types, so the bridge
 * overloads below hand the work to the existing renderer.
 */
public interface GraphicsOGL extends com.nttdocomo.ui.ogl.GraphicsOGL {
    default void glBufferData(int target, DirectBuffer data, int usage) {
        glBufferData(target, (com.nttdocomo.ui.ogl.DirectBuffer) data, usage);
    }

    default void glBufferSubData(int target, int offset, DirectBuffer data) {
        glBufferSubData(target, offset, (com.nttdocomo.ui.ogl.DirectBuffer) data);
    }

    default void glColorPointer(int size, int type, int stride, DirectBuffer pointer) {
        glColorPointer(size, type, stride, (com.nttdocomo.ui.ogl.DirectBuffer) pointer);
    }

    default void glNormalPointer(int type, int stride, DirectBuffer pointer) {
        glNormalPointer(type, stride, (com.nttdocomo.ui.ogl.DirectBuffer) pointer);
    }

    default void glTexCoordPointer(int size, int type, int stride, DirectBuffer pointer) {
        glTexCoordPointer(size, type, stride, (com.nttdocomo.ui.ogl.DirectBuffer) pointer);
    }

    default void glVertexPointer(int size, int type, int stride, DirectBuffer pointer) {
        glVertexPointer(size, type, stride, (com.nttdocomo.ui.ogl.DirectBuffer) pointer);
    }

    default void glMatrixIndexPointerOES(int size, int type, int stride, DirectBuffer pointer) {
        glMatrixIndexPointerOES(size, type, stride, (com.nttdocomo.ui.ogl.DirectBuffer) pointer);
    }

    default void glPointSizePointerOES(int type, int stride, DirectBuffer pointer) {
        glPointSizePointerOES(type, stride, (com.nttdocomo.ui.ogl.DirectBuffer) pointer);
    }

    default void glWeightPointerOES(int size, int type, int stride, DirectBuffer pointer) {
        glWeightPointerOES(size, type, stride, (com.nttdocomo.ui.ogl.DirectBuffer) pointer);
    }

    default void glDrawElements(int mode, int count, DirectBuffer indices) {
        glDrawElements(mode, count, (com.nttdocomo.ui.ogl.DirectBuffer) indices);
    }

    default void glCompressedTexImage2D(int target, int level, int internalformat, int width, int height, int border,
                                        ByteBuffer data) {
        glCompressedTexImage2D(target, level, internalformat, width, height, border,
                (com.nttdocomo.ui.ogl.ByteBuffer) data);
    }

    default void glCompressedTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height,
                                           int format, ByteBuffer data) {
        glCompressedTexSubImage2D(target, level, xoffset, yoffset, width, height, format,
                (com.nttdocomo.ui.ogl.ByteBuffer) data);
    }

    default void glTexImage2D(int target, int level, int internalformat, int width, int height, int border,
                              int format, int type, DirectBuffer pixels) {
        glTexImage2D(target, level, internalformat, width, height, border, format, type,
                (com.nttdocomo.ui.ogl.DirectBuffer) pixels);
    }

    default void glTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height,
                                 int format, int type, DirectBuffer pixels) {
        glTexSubImage2D(target, level, xoffset, yoffset, width, height, format, type,
                (com.nttdocomo.ui.ogl.DirectBuffer) pixels);
    }
}
