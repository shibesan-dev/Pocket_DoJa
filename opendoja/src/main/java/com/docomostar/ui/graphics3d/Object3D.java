package com.docomostar.ui.graphics3d;

import java.io.IOException;
import java.io.InputStream;

/**
 * Base class for all 3D objects handled by the Star graphics3d API.
 *
 * <p>Star exposes a separate class hierarchy from the DoJa 5.1 3D API even
 * though the underlying object data and rendering engine are the same. These
 * wrappers keep the Star hierarchy intact and delegate the actual work to the
 * DoJa implementation.</p>
 */
public abstract class Object3D {
    /**
     * Type value indicating that the object has been disposed or has no type.
     */
    public static final int TYPE_NONE = 0;
    /**
     * Type value for an action-table object.
     */
    public static final int TYPE_ACTION_TABLE = 1;
    /**
     * Type value for a figure object.
     */
    public static final int TYPE_FIGURE = 2;
    /**
     * Type value for a texture object.
     */
    public static final int TYPE_TEXTURE = 3;
    /**
     * Type value for a fog object.
     */
    public static final int TYPE_FOG = 4;
    /**
     * Type value for a light object.
     */
    public static final int TYPE_LIGHT = 5;
    /**
     * Type value for a primitive object.
     */
    public static final int TYPE_PRIMITIVE = 6;
    /**
     * Type value for a group object.
     */
    public static final int TYPE_GROUP = 7;
    /**
     * Type value for a group-mesh object.
     */
    public static final int TYPE_GROUP_MESH = 8;

    private final com.nttdocomo.ui.graphics3d.Object3D delegate;

    /**
     * Initializes a 3D object wrapper.
     *
     * @param delegate the DoJa implementation object
     */
    Object3D(com.nttdocomo.ui.graphics3d.Object3D delegate) {
        this.delegate = delegate;
    }

    final com.nttdocomo.ui.graphics3d.Object3D delegate() {
        return delegate;
    }

    /**
     * Creates a 3D object from encoded object data read from a stream.
     *
     * @param inputStream the stream containing encoded 3D object data
     * @return the created 3D object
     * @throws IOException if the data cannot be read or does not describe a supported object
     */
    public static Object3D createInstance(InputStream inputStream) throws IOException {
        Object3D object = createInstance(opendoja.host.ByteStreams.readAll(inputStream));
        if (object == null) {
            throw new IOException("Unsupported Object3D data");
        }
        return object;
    }

    /**
     * Creates a 3D object from encoded object data in a byte array.
     *
     * @param data the encoded 3D object data
     * @return the created object, or {@code null} if the data is unsupported
     */
    public static Object3D createInstance(byte[] data) {
        return wrap(com.nttdocomo.ui.graphics3d.Object3D.createInstance(data));
    }

    static Object3D wrap(com.nttdocomo.ui.graphics3d.Object3D object) {
        if (object == null) {
            return null;
        }
        if (object instanceof com.nttdocomo.ui.graphics3d.Figure figure) {
            return new Figure(figure);
        }
        if (object instanceof com.nttdocomo.ui.graphics3d.Primitive primitive) {
            return new Primitive(primitive);
        }
        if (object instanceof com.nttdocomo.ui.graphics3d.Group group) {
            return new Group(group);
        }
        if (object instanceof com.nttdocomo.ui.graphics3d.Texture texture) {
            return new Texture(texture);
        }
        if (object instanceof com.nttdocomo.ui.graphics3d.Light light) {
            return new Light(light);
        }
        if (object instanceof com.nttdocomo.ui.graphics3d.Fog fog) {
            return new Fog(fog);
        }
        if (object instanceof com.nttdocomo.ui.graphics3d.ActionTable actionTable) {
            return new ActionTable(actionTable);
        }
        return null;
    }

    /**
     * Releases any data held by this 3D object. After disposal the type value
     * becomes {@link #TYPE_NONE}.
     */
    public void dispose() {
        delegate.dispose();
    }

    /**
     * Gets the type number of this 3D object.
     *
     * @return the object type
     */
    public int getType() {
        return delegate.getType();
    }

    /**
     * Sets the current time associated with this 3D object.
     *
     * @param time the current time
     */
    public void setTime(int time) {
        delegate.setTime(time);
    }

    /**
     * Gets the current time associated with this 3D object.
     *
     * @return the current time
     */
    public int getTime() {
        return delegate.getTime();
    }
}
