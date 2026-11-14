package com.docomostar.ui.graphics3d;

/**
 * Internal bridge used by {@link com.docomostar.ui.Graphics} to unwrap Star 3D
 * objects before calling the shared DoJa 5.1 renderer.
 */
public final class Star3DBridge {
    private Star3DBridge() {
    }

    /**
     * Unwraps a Star light object.
     *
     * @param light the Star light, or {@code null}
     * @return the underlying DoJa light
     */
    public static com.nttdocomo.ui.graphics3d.Light unwrap(Light light) {
        return light == null ? null : (com.nttdocomo.ui.graphics3d.Light) light.delegate();
    }

    /**
     * Unwraps a Star fog object.
     *
     * @param fog the Star fog, or {@code null}
     * @return the underlying DoJa fog
     */
    public static com.nttdocomo.ui.graphics3d.Fog unwrap(Fog fog) {
        return fog == null ? null : (com.nttdocomo.ui.graphics3d.Fog) fog.delegate();
    }

    /**
     * Unwraps a Star drawable 3D object.
     *
     * @param object the Star object, or {@code null}
     * @return the underlying DoJa drawable object
     */
    public static com.nttdocomo.ui.graphics3d.DrawableObject3D unwrap(DrawableObject3D object) {
        return object == null
                ? null
                : (com.nttdocomo.ui.graphics3d.DrawableObject3D) object.delegate();
    }
}
