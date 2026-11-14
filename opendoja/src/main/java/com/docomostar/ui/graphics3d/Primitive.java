package com.docomostar.ui.graphics3d;

/**
 * Defines the primitive object that stores vertex and attribute data for
 * points, lines, polygons, and point sprites.
 */
public class Primitive extends DrawableObject3D {
    /**
     * Primitive type for points.
     */
    public static final int PRIMITIVE_POINTS = 1;
    /**
     * Primitive type for lines.
     */
    public static final int PRIMITIVE_LINES = 2;
    /**
     * Primitive type for triangles.
     */
    public static final int PRIMITIVE_TRIANGLES = 3;
    /**
     * Primitive type for quadrilaterals.
     */
    public static final int PRIMITIVE_QUADS = 4;
    /**
     * Primitive type for point sprites.
     */
    public static final int PRIMITIVE_POINT_SPRITES = 5;
    /**
     * Flag indicating that no normal data is stored.
     */
    public static final int NORMAL_NONE = 0;
    /**
     * Flag indicating that normals are stored per face.
     */
    public static final int NORMAL_PER_FACE = 512;
    /**
     * Flag indicating that normals are stored per vertex.
     */
    public static final int NORMAL_PER_VERTEX = 768;
    /**
     * Flag indicating that no color data is stored.
     */
    public static final int COLOR_NONE = 0;
    /**
     * Flag indicating that a single color is stored per primitive.
     */
    public static final int COLOR_PER_PRIMITIVE = 1024;
    /**
     * Flag indicating that color data is stored per face.
     */
    public static final int COLOR_PER_FACE = 2048;
    /**
     * Flag indicating that no texture coordinates are stored.
     */
    public static final int TEXTURE_COORD_NONE = 0;
    /**
     * Flag indicating that texture coordinates are stored per vertex.
     */
    public static final int TEXTURE_COORD_PER_VERTEX = 12288;
    /**
     * Flag enabling the transparent color in the assigned texture.
     */
    public static final int TEXTURE_COLORKEY = 16;
    /**
     * Flag indicating that a single point-sprite definition is stored per primitive.
     */
    public static final int POINT_SPRITE_PER_PRIMITIVE = 4096;
    /**
     * Flag indicating that point-sprite data is stored per vertex.
     */
    public static final int POINT_SPRITE_PER_VERTEX = 12288;
    /**
     * Point-sprite flag indicating that size is specified in local coordinates.
     */
    public static final int POINT_SPRITE_FLAG_LOCAL_SIZE = 0;
    /**
     * Point-sprite flag indicating that size is specified in pixels.
     */
    public static final int POINT_SPRITE_FLAG_PIXEL_SIZE = 1;
    /**
     * Point-sprite flag indicating that perspective scaling is enabled.
     */
    public static final int POINT_SPRITE_FLAG_PERSPECTIVE = 0;
    /**
     * Point-sprite flag indicating that perspective scaling is disabled.
     */
    public static final int POINT_SPRITE_FLAG_NO_PERSPECTIVE = 2;

    /**
     * Creates a primitive object with the specified type, parameter flags, and
     * primitive count.
     *
     * @param primitiveType the primitive type
     * @param primitiveParam the primitive parameter flags
     * @param primitiveCount the number of primitives
     */
    public Primitive(int primitiveType, int primitiveParam, int primitiveCount) {
        super(new com.nttdocomo.ui.graphics3d.Primitive(primitiveType, primitiveParam, primitiveCount));
    }

    Primitive(com.nttdocomo.ui.graphics3d.Primitive delegate) {
        super(delegate);
    }

    private com.nttdocomo.ui.graphics3d.Primitive primitive() {
        return (com.nttdocomo.ui.graphics3d.Primitive) delegate();
    }

    /**
     * Gets the primitive type.
     *
     * @return the primitive type
     */
    public int getPrimitiveType() {
        return primitive().getPrimitiveType();
    }

    /**
     * Gets the primitive parameter flags.
     *
     * @return the primitive parameter flags
     */
    public int getPrimitiveParam() {
        return primitive().getPrimitiveParam();
    }

    /**
     * Gets the number of primitives stored in this object.
     *
     * @return the primitive count
     */
    public int size() {
        return primitive().size();
    }

    /**
     * Gets the vertex array.
     *
     * @return the vertex array
     */
    public int[] getVertexArray() {
        return primitive().getVertexArray();
    }

    /**
     * Gets the normal array.
     *
     * @return the normal array, or {@code null} if no normals are stored
     */
    public int[] getNormalArray() {
        return primitive().getNormalArray();
    }

    /**
     * Gets the color array.
     *
     * @return the color array, or {@code null} if no colors are stored
     */
    public int[] getColorArray() {
        return primitive().getColorArray();
    }

    /**
     * Gets the texture-coordinate array.
     *
     * @return the texture-coordinate array, or {@code null} if no texture coordinates are stored
     */
    public int[] getTextureCoordArray() {
        return primitive().getTextureCoordArray();
    }

    /**
     * Gets the point-sprite array.
     *
     * @return the point-sprite array, or {@code null} if no point-sprite data is stored
     */
    public int[] getPointSpriteArray() {
        return primitive().getPointSpriteArray();
    }

    /**
     * Normalizes a 3-component fixed-point vector in-place.
     *
     * @param vector the vector array
     * @param offset the offset of the x component
     */
    public static void normalize(int[] vector, int offset) {
        com.nttdocomo.ui.graphics3d.Primitive.normalize(vector, offset);
    }

    /**
     * Converts a radian angle to the integer angle representation used by the
     * engine.
     *
     * @param angle the angle in radians
     * @return the converted integer angle
     */
    public static int convertAngle(float angle) {
        return com.nttdocomo.ui.graphics3d.Primitive.convertAngle(angle);
    }

    /**
     * Converts an engine integer angle to radians.
     *
     * @param angle the engine integer angle
     * @return the angle in radians
     */
    public static float convertAngle(int angle) {
        return com.nttdocomo.ui.graphics3d.Primitive.convertAngle(angle);
    }

    /**
     * Sets the texture associated with this primitive object.
     *
     * @param texture the texture object
     */
    public void setTexture(Texture texture) {
        primitive().setTexture(texture == null ? null : (com.nttdocomo.ui.graphics3d.Texture) texture.delegate());
    }
}
