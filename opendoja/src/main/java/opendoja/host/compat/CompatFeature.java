package opendoja.host.compat;

import java.util.Locale;

/**
 * Per-title compatibility switches. Defaults are the normal behavior for
 * every game; a bundled profile flips one only when a title needs something
 * different. There is no user-facing setting for these.
 */
public enum CompatFeature {
    /**
     * Keeps textured primitive UVs inside their atlas sub-rectangle. Correct
     * for every title; the switch exists so a profile can turn it off if a
     * regression shows up without reverting the global fix.
     */
    G3D_UV_CLAMP_TO_PRIMITIVE_RECT("g3d.uvClampToPrimitiveRect", true),

    /**
     * Rotates the four direction keys one step clockwise before the guest
     * sees them. Some titles request a sideways display and remap their own
     * keys as if the handset pad turned with the screen; our controls always
     * follow the displayed image.
     */
    DPAD_ROTATE_FOR_SIDEWAYS_KEY_STYLE("input.rotateDpadForSidewaysKeyStyle", false),

    /**
     * Renders 3D scenes through the host GLES1 driver instead of the software
     * rasterizer. Off by default because the hardware path is only validated
     * per title; unsupported drivers fall back to software automatically.
     */
    OPENGLES_HARDWARE_RENDERER("renderer.openGlesHardware", false),

    /**
     * Draws handset bitmap text larger with wider advances. Off by default
     * because fixed-position UIs were laid out for the original metrics.
     */
    FONT_ENLARGE_AND_WIDEN("font.enlargeAndWiden", false),

    /**
     * Thickens bitmap glyphs by one pixel without changing their advance.
     * Helps when a wide canvas is scaled down and the thin stems disappear.
     */
    FONT_PIXEL_BOLD("font.pixelBold", false),

    /**
     * Clears the canvas to its background color after a flushed, text-only
     * frame. Some titles expect a blank back buffer and otherwise leave the
     * previous screen showing through. Frames that drew an image are left
     * alone so menu backgrounds survive.
     */
    CLEAR_CANVAS_AFTER_FLUSH("graphics.clearCanvasAfterFlush", false);

    private final String key;
    private final boolean defaultValue;

    CompatFeature(String key, boolean defaultValue) {
        this.key = key;
        this.defaultValue = defaultValue;
    }

    public boolean defaultValue() {
        return defaultValue;
    }

    public static CompatFeature fromKey(String key) {
        if (key == null) {
            return null;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        for (CompatFeature feature : values()) {
            if (feature.key.toLowerCase(Locale.ROOT).equals(normalized)) {
                return feature;
            }
        }
        return null;
    }
}
