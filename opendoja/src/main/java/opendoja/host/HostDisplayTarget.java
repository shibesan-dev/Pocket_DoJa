package opendoja.host;

/**
 * On-screen size of the game viewport, reported by the host UI.
 *
 * <p>The OpenGLES renderer uses this to avoid rasterizing 3D content at the
 * title's native framebuffer size when the host only displays it in a much
 * smaller skin opening. Rendering at the visible size is cheaper and also
 * avoids the over-sharp, aliased look produced by downscaling a high internal
 * resolution onto a small LCD area.</p>
 */
public final class HostDisplayTarget {
    private static volatile int width;
    private static volatile int height;

    private HostDisplayTarget() {
    }

    public static void set(int width, int height) {
        if (width < 0) {
            width = 0;
        }
        if (height < 0) {
            height = 0;
        }
        if (width == HostDisplayTarget.width && height == HostDisplayTarget.height) {
            return;
        }
        HostDisplayTarget.width = width;
        HostDisplayTarget.height = height;
    }

    public static int width() {
        return width;
    }

    public static int height() {
        return height;
    }

    public static void clear() {
        width = 0;
        height = 0;
    }
}
