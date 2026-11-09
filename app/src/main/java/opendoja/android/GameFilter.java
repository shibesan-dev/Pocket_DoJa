package opendoja.android;

/**
 * A display filter applied to game frames at native framebuffer resolution,
 * before the image is upscaled to the display. The look of each filter is
 * ported from popular libretro slang-shaders presets (crt, lcd-grid,
 * handheld, etc.). Running at native resolution keeps the per-frame cost tiny
 * (~240x320 pixels) and mirrors how RetroArch executes shaders on the core's
 * output surface before the video driver scales it.
 */
public abstract class GameFilter {
    final String id;

    GameFilter(String id) {
        this.id = id;
    }

    /**
     * Filters one frame in place.
     *
     * @param pixels ARGB_8888 pixel data, row-major
     * @param width  frame width
     * @param height frame height
     */
    abstract void apply(int[] pixels, int width, int height);
}
