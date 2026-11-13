package com.docomostar.ui.ogl;

/**
 * Star compatibility allocator for direct buffers. The returned buffers are
 * the same host-backed instances used by the DoJa OpenGL renderer.
 */
public final class DirectBufferFactory {
    private static final DirectBufferFactory FACTORY = new DirectBufferFactory();

    private DirectBufferFactory() {
    }

    /**
     * Returns the singleton factory instance.
     *
     * @return the shared factory
     */
    public static DirectBufferFactory getFactory() {
        return FACTORY;
    }

    /**
     * Allocates a byte buffer of the specified length.
     *
     * @param size the number of elements
     * @return the allocated buffer
     */
    public ByteBuffer allocateByteBuffer(int size) {
        return (ByteBuffer) nttFactory().allocateByteBuffer(size);
    }

    /**
     * Allocates a byte buffer initialized from a byte array.
     *
     * @param initialData the initial data
     * @return the allocated buffer
     */
    public ByteBuffer allocateByteBuffer(byte[] initialData) {
        return (ByteBuffer) nttFactory().allocateByteBuffer(initialData);
    }

    /**
     * Allocates a short buffer of the specified length.
     *
     * @param size the number of elements
     * @return the allocated buffer
     */
    public ShortBuffer allocateShortBuffer(int size) {
        return (ShortBuffer) nttFactory().allocateShortBuffer(size);
    }

    /**
     * Allocates a short buffer initialized from short values.
     *
     * @param initialData the initial values
     * @return the allocated buffer
     */
    public ShortBuffer allocateShortBuffer(short[] initialData) {
        return (ShortBuffer) nttFactory().allocateShortBuffer(initialData);
    }

    /**
     * Allocates an int buffer of the specified length.
     *
     * @param size the number of elements
     * @return the allocated buffer
     */
    public IntBuffer allocateIntBuffer(int size) {
        return (IntBuffer) nttFactory().allocateIntBuffer(size);
    }

    /**
     * Allocates an int buffer initialized from int values.
     *
     * @param initialData the initial values
     * @return the allocated buffer
     */
    public IntBuffer allocateIntBuffer(int[] initialData) {
        return (IntBuffer) nttFactory().allocateIntBuffer(initialData);
    }

    /**
     * Allocates a float buffer of the specified length.
     *
     * @param size the number of elements
     * @return the allocated buffer
     */
    public FloatBuffer allocateFloatBuffer(int size) {
        return (FloatBuffer) nttFactory().allocateFloatBuffer(size);
    }

    /**
     * Allocates a float buffer initialized from float values.
     *
     * @param initialData the initial values
     * @return the allocated buffer
     */
    public FloatBuffer allocateFloatBuffer(float[] initialData) {
        return (FloatBuffer) nttFactory().allocateFloatBuffer(initialData);
    }

    private static com.nttdocomo.ui.ogl.DirectBufferFactory nttFactory() {
        return com.nttdocomo.ui.ogl.DirectBufferFactory.getFactory();
    }
}
