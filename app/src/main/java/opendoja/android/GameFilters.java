package opendoja.android;

/**
 * Catalog and factory for the display filters (see {@link GameFilter}).
 * The ids here are persisted in the display SharedPreferences, so keep them
 * stable; only append new ids.
 */
public final class GameFilters {
    public static final String ID_NONE = "none";
    public static final String ID_SCANLINES = "scanlines";
    public static final String ID_LCD_GRID = "lcd_grid";
    public static final String ID_CRT = "crt";
    public static final String ID_DOT_MATRIX = "dot_matrix";
    public static final String ID_GAMEBOY = "gameboy";
    public static final String ID_SEPIA = "sepia";
    public static final String ID_GRAYSCALE = "grayscale";

    /** Ordered catalog for the Settings UI. */
    public static final String[] IDS = {
            ID_NONE, ID_SCANLINES, ID_LCD_GRID, ID_CRT,
            ID_DOT_MATRIX, ID_GAMEBOY, ID_SEPIA, ID_GRAYSCALE,
    };

    private GameFilters() {
    }

    /** Returns the filter for the given id, or null for none/unknown. */
    public static GameFilter create(String id) {
        if (id == null) {
            return null;
        }
        switch (id) {
            case ID_SCANLINES:
                return new ScanlinesFilter();
            case ID_LCD_GRID:
                return new LcdGridFilter();
            case ID_CRT:
                return new CrtFilter();
            case ID_DOT_MATRIX:
                return new DotMatrixFilter();
            case ID_GAMEBOY:
                return new LuminanceFilter(ID_GAMEBOY, gameboyLut());
            case ID_SEPIA:
                return new LuminanceFilter(ID_SEPIA, sepiaLut());
            case ID_GRAYSCALE:
                return new LuminanceFilter(ID_GRAYSCALE, grayscaleLut());
            default:
                return null;
        }
    }

    /** Builds an 8-bit multiply LUT: lut[v] = clamp(v * factor). */
    static int[] channelLut(float factor) {
        int[] lut = new int[256];
        for (int v = 0; v < 256; v++) {
            int scaled = Math.round(v * factor);
            lut[v] = Math.max(0, Math.min(255, scaled));
        }
        return lut;
    }

    private static int[] gameboyLut() {
        // Nintendo DMG-style four-shade green palette.
        int[] palette = {0xFF0F380F, 0xFF306230, 0xFF8BAC0F, 0xFF9BBC0F};
        int[] lut = new int[256];
        for (int v = 0; v < 256; v++) {
            lut[v] = palette[Math.min(3, (v * palette.length) >> 8)];
        }
        return lut;
    }

    private static int[] sepiaLut() {
        int[] lut = new int[256];
        for (int v = 0; v < 256; v++) {
            int r = Math.min(255, (v * 393) >> 8);
            int g = Math.min(255, (v * 349) >> 8);
            int b = Math.min(255, (v * 272) >> 8);
            lut[v] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return lut;
    }

    private static int[] grayscaleLut() {
        int[] lut = new int[256];
        for (int v = 0; v < 256; v++) {
            lut[v] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        return lut;
    }

    /** Maps each pixel's luminance through a precomputed color LUT. */
    private static final class LuminanceFilter extends GameFilter {
        private final int[] lut;

        LuminanceFilter(String id, int[] lut) {
            super(id);
            this.lut = lut;
        }

        @Override
        void apply(int[] pixels, int width, int height) {
            int length = width * height;
            for (int i = 0; i < length; i++) {
                int p = pixels[i];
                int lum = (((p >> 16) & 0xFF) * 77 + ((p >> 8) & 0xFF) * 150 + (p & 0xFF) * 29) >> 8;
                pixels[i] = lut[lum];
            }
        }
    }

    /**
     * Classic CRT scanlines: every other row is darkened, matching the
     * "scanlines" slang preset.
     */
    private static final class ScanlinesFilter extends GameFilter {
        private static final int[] DARK = channelLut(0.6f);

        ScanlinesFilter() {
            super(ID_SCANLINES);
        }

        @Override
        void apply(int[] pixels, int width, int height) {
            for (int y = 1; y < height; y += 2) {
                int end = (y + 1) * width;
                for (int i = y * width; i < end; i++) {
                    int p = pixels[i];
                    pixels[i] = (p & 0xFF000000)
                            | (DARK[(p >> 16) & 0xFF] << 16)
                            | (DARK[(p >> 8) & 0xFF] << 8)
                            | DARK[p & 0xFF];
                }
            }
        }
    }

    /**
     * LCD subpixel grid: vertical RGB stripes with the matching channel
     * boosted, plus a dark gap every third row ("lcd-grid" slang preset).
     */
    private static final class LcdGridFilter extends GameFilter {
        // Per-column channel LUTs, indexed by column (x % 3).
        private static final int[][] R_LUTS = {channelLut(1.15f), channelLut(0.8f), channelLut(0.8f)};
        private static final int[][] G_LUTS = {channelLut(0.8f), channelLut(1.15f), channelLut(0.8f)};
        private static final int[][] B_LUTS = {channelLut(0.8f), channelLut(0.8f), channelLut(1.15f)};
        private static final int[] GAP = channelLut(0.85f);

        LcdGridFilter() {
            super(ID_LCD_GRID);
        }

        @Override
        void apply(int[] pixels, int width, int height) {
            for (int y = 0; y < height; y++) {
                int row = y * width;
                int[] gap = (y % 3 == 2) ? GAP : null;
                int col = 0;
                for (int x = 0; x < width; x++) {
                    int i = row + x;
                    int p = pixels[i];
                    int r = R_LUTS[col][(p >> 16) & 0xFF];
                    int g = G_LUTS[col][(p >> 8) & 0xFF];
                    int b = B_LUTS[col][p & 0xFF];
                    if (gap != null) {
                        r = gap[r];
                        g = gap[g];
                        b = gap[b];
                    }
                    pixels[i] = (p & 0xFF000000) | (r << 16) | (g << 8) | b;
                    col = (col == 2) ? 0 : col + 1;
                }
            }
        }
    }

    /**
     * Soft CRT look: barrel curvature, per-row scanlines and a corner
     * vignette, precomputed as look-up tables ("crt-geom" style slang preset).
     */
    private static final class CrtFilter extends GameFilter {
        private static final int SCANLINE_FACTOR = 160; // out of 256
        private int[] srcMap;   // destination pixel -> source pixel (curvature)
        private int[] vignette; // per-pixel 0..256 multiplier
        private int[] scratch;
        private int lutWidth = -1;
        private int lutHeight = -1;

        CrtFilter() {
            super(ID_CRT);
        }

        @Override
        void apply(int[] pixels, int width, int height) {
            ensureLuts(width, height);
            int length = width * height;
            System.arraycopy(pixels, 0, scratch, 0, length);
            int[] map = srcMap;
            int[] vig = vignette;
            for (int y = 0; y < height; y++) {
                int row = y * width;
                int scan = (y & 1) == 0 ? 256 : SCANLINE_FACTOR;
                for (int x = 0; x < width; x++) {
                    int i = row + x;
                    int p = scratch[map[i]];
                    int factor = (vig[i] * scan) >> 8; // 0..256
                    int r = (((p >> 16) & 0xFF) * factor) >> 8;
                    int g = (((p >> 8) & 0xFF) * factor) >> 8;
                    int b = ((p & 0xFF) * factor) >> 8;
                    pixels[i] = (p & 0xFF000000) | (r << 16) | (g << 8) | b;
                }
            }
        }

        private void ensureLuts(int width, int height) {
            if (lutWidth == width && lutHeight == height) {
                return;
            }
            lutWidth = width;
            lutHeight = height;
            int length = width * height;
            srcMap = new int[length];
            vignette = new int[length];
            scratch = new int[length];
            float cx = (width - 1) * 0.5f;
            float cy = (height - 1) * 0.5f;
            float radius = Math.max(cx, cy);
            for (int y = 0; y < height; y++) {
                int row = y * width;
                float ny = (y - cy) / radius;
                for (int x = 0; x < width; x++) {
                    float nx = (x - cx) / radius;
                    float distSq = nx * nx + ny * ny;
                    // Barrel distortion: pull the image in near the edges, then
                    // scale up so the corners stay filled.
                    float curve = 1f + 0.12f * distSq;
                    float sx = cx + nx * curve * radius;
                    float sy = cy + ny * curve * radius;
                    int mapX = Math.max(0, Math.min(width - 1, (int) (sx + 0.5f)));
                    int mapY = Math.max(0, Math.min(height - 1, (int) (sy + 0.5f)));
                    int i = row + x;
                    srcMap[i] = mapY * width + mapX;
                    vignette[i] = (int) (256f * Math.max(0.25f, 1f - 0.9f * distSq));
                }
            }
        }
    }

    /**
     * GBA LCD style
     */
    private static final class DotMatrixFilter extends GameFilter {
        private int[] columnFactor; // 4 or 5 per column (dimmed grid lines)
        private int factorWidth = -1;

        DotMatrixFilter() {
            super(ID_DOT_MATRIX);
        }

        @Override
        void apply(int[] pixels, int width, int height) {
            if (factorWidth != width) {
                columnFactor = new int[width];
                for (int x = 0; x < width; x++) {
                    columnFactor[x] = (x % 3 == 2) ? 4 : 5;
                }
                factorWidth = width;
            }
            for (int y = 0; y < height; y++) {
                int rowFactor = (y % 3 == 2) ? 4 : 5;
                int row = y * width;
                for (int x = 0; x < width; x++) {
                    int i = row + x;
                    int p = pixels[i];
                    int mul = columnFactor[x] * rowFactor; // 16, 20 or 25
                    int r = (((p >> 16) & 0xFF) * mul) / 25;
                    int g = (((p >> 8) & 0xFF) * mul) / 25;
                    int b = ((p & 0xFF) * mul) / 25;
                    pixels[i] = (p & 0xFF000000) | (r << 16) | (g << 8) | b;
                }
            }
        }
    }
}
