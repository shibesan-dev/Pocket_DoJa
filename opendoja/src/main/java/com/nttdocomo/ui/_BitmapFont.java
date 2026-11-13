package com.nttdocomo.ui;

import com.nttdocomo.lang.XString;

import opendoja.compat.awt.*;
import opendoja.compat.awt.image.BufferedImage;
import opendoja.host.compat.CompatFeature;
import opendoja.host.compat.GameCompatibility;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bitmap font backend loaded from extracted handset glyph dumps.
 *
 * <p>The bundled dump combines several handset sources and is treated as the authoritative source
 * for this renderer: size 8 was extracted from an N503i, size 10 from an N504i, and sizes 12/16/
 * 20/24/30 from a P902i. Thanks to Unabandonware (LNRC) for dumping the NOR, kagekiyo for
 * extracting the fonts (https://github.com/ZiplineGun/k/blob/main/extract_n900i_font.py),
 * GuyPerfect for the initial version of this file, and PoogieCharger for creating the updated
 * bitmap font set that fixed earlier issues and added the 8px and 10px strikes.</p>
 *
 * <p>Each size has a {@code code-points.dat} table plus one raw glyph-bitmap file. The code-point
 * table is a list of 16-bit little-endian Unicode values in the same order as the glyphs appear in
 * the bitmap file. Some entries are {@code U+FFFD} placeholders because the original handset table
 * contains glyphs that do not map cleanly to Unicode, and some smaller strikes can truncate the
 * tail of the shared table. The glyph file name encodes the bitmap height, and the stored bitmap
 * width is the smallest multiple of eight that is at least that height. Every bit is one pixel
 * with the left-most pixel in the MSB; set bits are foreground pixels and cleared bits are
 * transparent.</p>
 *
 * <p>The dump also distinguishes half-width code points from full-width ones, so layout here keeps
 * the handset width rules while blitting only the visible half-width columns. Baseline/ascent
 * metrics are not stored separately in the source files, so the vertical metrics below are derived
 * from each raw strike's line-cell height.</p>
 */
class _BitmapFont extends Font {
    private static final String RESOURCE_ROOT = "/opendoja/fonts/bitmap/";
    private static final int[] SUPPORTED_SIZES = {8, 10, 12, 16, 20, 24, 30};
    private static final int SPACE = 0x0020;
    private static final int IDEOGRAPHIC_SPACE = 0x3000;
    // Optional text enlargement: taller cells, snapped to the nearest native strike so
    // glyphs stay crisp. Small UI sizes get the biggest boost that still fits a 16px
    // line; dialogue and title text only move a little so their boxes stay intact.
    // Advances widen because the wide canvas is squeezed into the portrait opening.
    private static final float ENLARGED_TEXT_BASE_WIDTH_SCALE = 1.2f;
    private static final float ENLARGED_TEXT_WIDTH_PER_SQUEEZE = 0.95f;
    private static final float ENLARGED_TEXT_MAX_WIDTH_SCALE = 1.45f;
    private static final float ENLARGED_TEXT_SMALL_HEIGHT_SCALE = 1.0f;
    private static final float ENLARGED_TEXT_MEDIUM_HEIGHT_SCALE = 1.25f;
    private static final float ENLARGED_TEXT_DIALOGUE_HEIGHT_SCALE = 1.2f;
    private static final float ENLARGED_TEXT_LARGE_HEIGHT_SCALE = 1.15f;
    // The skin bezel overlaps the edge of the game aperture, so keep enlarged text a
    // little further inside than the nominal game boundary.
    private static final int ENLARGED_TEXT_RIGHT_MARGIN = 24;
    private static final int ENLARGED_TEXT_MIN_X = 4;
    // Space-padded centred lines imply their window from the padding; leave room for
    // the outline so it stays inside the drawn frame.
    private static final int ENLARGED_TEXT_BOX_PADDING = 4;
    private static final int TEXT_OUTLINE_COLOR = 0xB0000000;
    private static final opendoja.compat.awt.Font PLACEHOLDER_FONT = new opendoja.compat.awt.Font(opendoja.compat.awt.Font.DIALOG, opendoja.compat.awt.Font.PLAIN, 12);
    private static final Map<Integer, Strike> STRIKES = loadStrikes();
    private static final int MAX_RENDER_CACHE_ENTRIES = opendoja.host.OpenDoJaLaunchArgs.getInt(opendoja.host.OpenDoJaLaunchArgs.BITMAP_FONT_CACHE_ENTRIES);
    // UI-heavy titles redraw the same handset strings every frame. Cache the rasterized result so
    // repeated dialog/menu paints do not allocate and blit glyph bitmaps from scratch each time.
    private static final Map<RenderKey, BufferedImage> RENDER_CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<RenderKey, BufferedImage> eldest) {
            return size() > MAX_RENDER_CACHE_ENTRIES;
        }
    };
    // Some title screens build one line from several drawString calls positioned with the
    // native advance. Track the previous run so the next one is appended after the widened
    // glyphs instead of overlapping them. Reset every frame: later screens reuse the same
    // coordinates for unrelated text.
    private static final ThreadLocal<TextLineState> LAST_TEXT_LINE = new ThreadLocal<>();

    private final Strike strike;
    private final float heightScale;
    private final boolean enlarged;
    private final boolean bold;
    private final boolean outline;
    private volatile float cachedWidthScale = ENLARGED_TEXT_BASE_WIDTH_SCALE;
    private volatile int cachedHostWidth = -1;
    private volatile int cachedHostHeight = -1;

    private _BitmapFont(Strike strike, float heightScale, boolean enlarged, boolean bold) {
        super(PLACEHOLDER_FONT);
        this.strike = strike;
        this.heightScale = heightScale;
        this.enlarged = enlarged;
        this.bold = bold;
        this.outline = bold;
    }

    static Font create(int face, int style, int size) {
        boolean enlarge = GameCompatibility.isFeatureEnabled(CompatFeature.FONT_ENLARGE_AND_WIDEN);
        // Bold does not have to come with enlargement. A title can thicken the native
        // glyphs without changing their advance, keeping fixed-width dialogs intact.
        boolean bold = GameCompatibility.isFeatureEnabled(CompatFeature.FONT_PIXEL_BOLD);
        int targetHeight = enlarge
                ? Math.max(1, Math.round(size * enlargedHeightScale(size)))
                : size;
        Strike strike = STRIKES.get(selectSize(targetHeight));
        if (strike == null) {
            return null;
        }
        float heightScale = targetHeight / (float) strike.height;
        // The extracted dump is a single handset font face. Preserve the public API shape, but all
        // DoJa faces/styles resolve to the same source glyphs until additional handset dumps exist.
        return new _BitmapFont(strike, heightScale, enlarge, bold);
    }

    public static int[] getSupportedFontSizes() {
        return SUPPORTED_SIZES.clone();
    }

    /** Clears the cross-session rasterized string cache; called on game session reset. */
    static void clearRenderCache() {
        synchronized (RENDER_CACHE) {
            RENDER_CACHE.clear();
        }
    }

    /** Drops the multi-part line state at frame lock boundaries. */
    static void resetTextLineState() {
        LAST_TEXT_LINE.remove();
    }

    @Override
    public int getAscent() {
        return scaledHeight();
    }

    @Override
    public int getDescent() {
        return 0;
    }

    @Override
    public int getHeight() {
        return scaledHeight();
    }

    @Override
    public int getBBoxHeight(String text) {
        String value = Font.metricString(text);
        if (value.isEmpty()) {
            return 0;
        }
        return value.split("\\n", -1).length * scaledHeight();
    }

    @Override
    public int getBBoxHeight(XString text) {
        return getBBoxHeight(Font.requireXString(text, "text"));
    }

    @Override
    public int getBBoxWidth(String text) {
        String value = Font.metricString(text);
        float scale = widthScale();
        int width = 0;
        for (String line : value.split("\\r?\\n", -1)) {
            width = Math.max(width, lineWidth(line, scale));
        }
        return width;
    }

    @Override
    public int getBBoxWidth(XString text) {
        return getBBoxWidth(Font.requireXString(text, "text"));
    }

    @Override
    public int getBBoxWidth(XString text, int offset, int length) {
        return getBBoxWidth(Font.slice(Font.requireXString(text, "text"), offset, length));
    }

    @Override
    public int stringWidth(String text) {
        return getBBoxWidth(text);
    }

    @Override
    public int stringWidth(XString text) {
        return getBBoxWidth(text);
    }

    @Override
    public int stringWidth(XString text, int offset, int length) {
        return getBBoxWidth(text, offset, length);
    }

    @Override
    public void drawString(Graphics2D graphics, String text, int x, int y, int argbColor) {
        float scale = widthScale();
        BufferedImage image = rendered(text, argbColor, scale);
        if (image == null) {
            return;
        }
        String value = Font.metricString(text);
        int padding = outline ? 1 : 0;
        int textWidth = Math.max(0, image.getWidth() - padding * 2);
        int nativeWidth = lineWidth(value, 1.0f);
        int targetWidth = graphics.getTargetWidth();
        int maxRight = targetWidth - ENLARGED_TEXT_RIGHT_MARGIN;

        // Some screens build a line from several drawString calls positioned with the native
        // advance (x + 16 * length). Once glyphs are widened those positions overlap, so treat
        // a call starting at the previous segment's native end as a continuation and append it.
        TextLineState last = LAST_TEXT_LINE.get();
        boolean continuation = enlarged
                && last != null
                && last.graphics == graphics
                && last.y == y
                && value.indexOf('\n') < 0
                && x >= last.nativeX + last.nativeWidth - 1
                && x <= last.nativeX + last.nativeWidth + 2;
        int drawX;
        if (continuation) {
            int gap = Math.max(0, x - (last.nativeX + last.nativeWidth));
            drawX = last.cursorX + gap;
            if (maxRight > drawX && drawX + image.getWidth() > maxRight) {
                // Ran into the bezel. Compress only this tail; the earlier runs are
                // already drawn and must stay overlap-free.
                int fitWidth = Math.max(1, maxRight - drawX - padding);
                float fitScale = largestScaleThatFits(value, scale, fitWidth);
                if (fitScale > 0f && fitScale < scale - 0.005f) {
                    BufferedImage fitted = rendered(value, argbColor, fitScale);
                    if (fitted != null) {
                        image = fitted;
                        textWidth = Math.max(0, image.getWidth() - padding * 2);
                    }
                }
            }
        } else {
            drawX = Math.max(0, x);
            if (enlarged && value.indexOf('\n') < 0) {
                // Some centred lines are positioned as 427 - 16 * length / 2. If x implies a
                // longer line, this is the first part of a multi-part line; reserve room for
                // the rest so the completed line ends up centred.
                int centreDx = 427 - x;
                int chars = value.length();
                if (centreDx > 0 && centreDx % 8 == 0) {
                    int totalChars = centreDx / 8;
                    if (totalChars == chars) {
                        drawX = Math.max(ENLARGED_TEXT_MIN_X, 427 - textWidth / 2);
                    } else if (totalChars > chars && x > 32 && totalChars <= 200) {
                        int estimatedTotal = textWidth + Math.round((totalChars - chars) * 16f * scale);
                        drawX = Math.max(ENLARGED_TEXT_MIN_X, 427 - estimatedTotal / 2);
                    }
                }
            }
            if (enlarged && targetWidth > 0 && image.getWidth() > 0) {
                // Keep enlarged text inside the bezel. Space-padded lines also imply their
                // right edge from the left padding; recover that edge so the text stays inside
                // the window the game drew.
                int boxRight = maxRight;
                int leadingPadding = leadingWhitespaceWidth(value);
                if (x >= 0 && leadingPadding > 0 && leadingPadding < nativeWidth) {
                    int impliedRight = x + nativeWidth + leadingPadding * 2;
                    if (impliedRight < boxRight) {
                        boxRight = Math.max(x + 1, impliedRight - ENLARGED_TEXT_BOX_PADDING);
                    }
                }
                if (drawX + image.getWidth() > boxRight) {
                    // Text on the right half of the canvas (narration columns, status panels)
                    // keeps its left edge; moving it left would collide with the left column.
                    // Compress it in place instead.
                    boolean rightAnchored = (long) x * 2 + nativeWidth > targetWidth;
                    if (rightAnchored) {
                        drawX = Math.max(0, x);
                    } else if (drawX > ENLARGED_TEXT_MIN_X) {
                        drawX = Math.max(ENLARGED_TEXT_MIN_X, maxRight - image.getWidth());
                    }
                    if (drawX + image.getWidth() > maxRight) {
                        int fitWidth = maxRight - drawX;
                        float fitScale = largestScaleThatFits(value, scale, fitWidth);
                        if (fitScale > 0f && fitScale < scale - 0.005f) {
                            BufferedImage fitted = rendered(value, argbColor, fitScale);
                            if (fitted != null) {
                                image = fitted;
                                textWidth = Math.max(0, image.getWidth() - padding * 2);
                            }
                        }
                    }
                }
            }
        }
        LAST_TEXT_LINE.set(new TextLineState(graphics, y, x, nativeWidth, drawX + textWidth));
        graphics.drawImage(image, drawX - padding, y - scaledHeight() - padding, null);
    }

    private static final class TextLineState {
        final Graphics2D graphics;
        final int y;
        final int nativeX;
        final int nativeWidth;
        final int cursorX;

        TextLineState(Graphics2D graphics, int y, int nativeX, int nativeWidth, int cursorX) {
            this.graphics = graphics;
            this.y = y;
            this.nativeX = nativeX;
            this.nativeWidth = nativeWidth;
            this.cursorX = cursorX;
        }
    }

    private float widthScale() {
        if (!enlarged) {
            return 1.0f;
        }
        int hostWidth = opendoja.host.HostDisplayTarget.width();
        int hostHeight = opendoja.host.HostDisplayTarget.height();
        if (hostWidth == cachedHostWidth && hostHeight == cachedHostHeight) {
            return cachedWidthScale;
        }
        cachedHostWidth = hostWidth;
        cachedHostHeight = hostHeight;
        cachedWidthScale = computeEnlargedWidthScale(hostWidth, hostHeight);
        return cachedWidthScale;
    }

    private static float computeEnlargedWidthScale(int hostWidth, int hostHeight) {
        int canvasWidth = Display.getWidth();
        int canvasHeight = Display.getHeight();
        if (hostWidth <= 0 || hostHeight <= 0 || canvasWidth <= 0 || canvasHeight <= 0) {
            return ENLARGED_TEXT_BASE_WIDTH_SCALE;
        }
        float horizontalScale = hostWidth / (float) canvasWidth;
        float verticalScale = hostHeight / (float) canvasHeight;
        if (horizontalScale <= 0.01f || verticalScale <= 0.01f) {
            return ENLARGED_TEXT_BASE_WIDTH_SCALE;
        }
        // The host squeezes the wide canvas into a narrower opening, which makes glyphs tall
        // and thin. Widen advances based on the squeeze so text stays readable on any viewport.
        float squeeze = verticalScale / horizontalScale;
        float scale = ENLARGED_TEXT_BASE_WIDTH_SCALE
                + Math.max(0f, squeeze - 1f) * ENLARGED_TEXT_WIDTH_PER_SQUEEZE;
        return Math.min(ENLARGED_TEXT_MAX_WIDTH_SCALE, scale);
    }

    private float largestScaleThatFits(String text, float maxScale, int maxWidth) {
        if (maxWidth <= 0) {
            return 0f;
        }
        String value = Font.metricString(text);
        if (maxLineWidth(value, maxScale) <= maxWidth) {
            return maxScale;
        }
        float low = 0.05f;
        float high = maxScale;
        for (int i = 0; i < 20; i++) {
            float mid = (low + high) * 0.5f;
            if (maxLineWidth(value, mid) <= maxWidth) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return low;
    }

    private int maxLineWidth(String text, float scale) {
        int width = 0;
        for (String line : text.split("\\r?\\n", -1)) {
            width = Math.max(width, lineWidth(line, scale));
        }
        return width;
    }

    private int scaledHeight() {
        return Math.max(1, Math.round(strike.height * heightScale));
    }

    private BufferedImage rendered(String value, int argbColor) {
        return rendered(value, argbColor, widthScale());
    }

    private BufferedImage rendered(String value, int argbColor, float scale) {
        String text = Font.metricString(value);
        RenderKey key = new RenderKey(strike, scale, heightScale, bold, argbColor, text);
        synchronized (RENDER_CACHE) {
            BufferedImage cached = RENDER_CACHE.get(key);
            if (cached != null) {
                return cached;
            }
        }
        BufferedImage image = draw(text, argbColor, scale);
        if (image == null) {
            return null;
        }
        synchronized (RENDER_CACHE) {
            RENDER_CACHE.put(key, image);
        }
        return image;
    }

    private BufferedImage draw(String text, int argbColor) {
        return draw(text, argbColor, widthScale());
    }

    private BufferedImage draw(String text, int argbColor, float scale) {
        String[] lines = text.split("\\r?\\n", -1);
        int lineHeight = scaledHeight();
        int padding = outline ? 1 : 0;
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, lineWidth(line, scale));
        }
        int height = lines.length * lineHeight;
        if (width <= 0 || height <= 0) {
            return null;
        }
        width += padding * 2;
        height += padding * 2;

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            int cursorX = padding;
            int cursorY = padding + lineIndex * lineHeight;
            int[] codePoints = lines[lineIndex].codePoints().toArray();
            for (int codePoint : codePoints) {
                int advance = advanceFor(codePoint, scale);
                int glyphIndex = glyphIndexFor(codePoint);
                if (glyphIndex >= 0) {
                    blitGlyph(pixels, width, height, cursorX, cursorY, glyphIndex, argbColor,
                            nativeAdvanceFor(codePoint), advance, lineHeight);
                }
                cursorX += advance;
            }
        }
        if (outline) {
            applyOutline(pixels, width, height);
        }
        image.setRGB(0, 0, width, height, pixels, 0, width);
        return image;
    }

    private void blitGlyph(int[] target, int targetWidth, int targetHeight, int dx, int dy,
                           int glyphIndex, int argbColor, int sourceVisibleWidth,
                           int destWidth, int destHeight) {
        int glyphOffset = glyphIndex * strike.bytesPerGlyph;
        for (int row = 0; row < destHeight; row++) {
            int targetY = dy + row;
            if (targetY < 0 || targetY >= targetHeight) {
                continue;
            }
            int sourceRow = Math.min(strike.height - 1, row * strike.height / destHeight);
            int rowOffset = glyphOffset + sourceRow * strike.bytesPerRow;
            for (int column = 0; column < destWidth; column++) {
                int sourceColumn = column * sourceVisibleWidth / destWidth;
                if (sourceColumn >= sourceVisibleWidth) {
                    continue;
                }
                int bits = strike.glyphData[rowOffset + (sourceColumn >> 3)] & 0xFF;
                if ((bits & (0x80 >>> (sourceColumn & 7))) == 0) {
                    continue;
                }
                int targetX = dx + column;
                if (targetX < 0 || targetX >= targetWidth) {
                    continue;
                }
                target[targetY * targetWidth + targetX] = argbColor;
                // Add a pixel to the stem, but stay inside the glyph's own advance.
                if (bold && column + 1 < destWidth && targetX + 1 < targetWidth) {
                    target[targetY * targetWidth + targetX + 1] = argbColor;
                }
            }
        }
    }

    private static void applyOutline(int[] pixels, int width, int height) {
        int[] glyphPixels = pixels.clone();
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int index = row + x;
                if (glyphPixels[index] != 0) {
                    continue;
                }
                boolean besideGlyph = (x > 0 && glyphPixels[index - 1] != 0)
                        || (x + 1 < width && glyphPixels[index + 1] != 0)
                        || (y > 0 && glyphPixels[index - width] != 0)
                        || (y + 1 < height && glyphPixels[index + width] != 0);
                if (besideGlyph) {
                    pixels[index] = TEXT_OUTLINE_COLOR;
                }
            }
        }
    }

    private int lineWidth(String line) {
        return lineWidth(line, widthScale());
    }

    private int lineWidth(String line, float scale) {
        int width = 0;
        for (int codePoint : line.codePoints().toArray()) {
            width += advanceFor(codePoint, scale);
        }
        return width;
    }

    /**
     * Native advance of the leading spaces in {@code value}. Space-padded centred
     * lines use that padding to imply the window width.
     */
    private int leadingWhitespaceWidth(String value) {
        int width = 0;
        for (int index = 0; index < value.length(); ) {
            int codePoint = value.codePointAt(index);
            if (codePoint != SPACE && codePoint != IDEOGRAPHIC_SPACE) {
                break;
            }
            width += nativeAdvanceFor(codePoint);
            index += Character.charCount(codePoint);
        }
        return width;
    }

    private int advanceFor(int codePoint) {
        return advanceFor(codePoint, widthScale());
    }

    private int advanceFor(int codePoint, float scale) {
        return Math.max(1, Math.round(nativeAdvanceFor(codePoint) * scale));
    }

    private int nativeAdvanceFor(int codePoint) {
        int effectiveCodePoint = effectiveCodePoint(codePoint);
        if (effectiveCodePoint == SPACE) {
            return strike.halfAdvance();
        }
        if (effectiveCodePoint == IDEOGRAPHIC_SPACE) {
            return strike.fullAdvance();
        }
        return isHalfWidth(effectiveCodePoint) ? strike.halfAdvance() : strike.fullAdvance();
    }

    private int glyphIndexFor(int codePoint) {
        int effectiveCodePoint = effectiveCodePoint(codePoint);
        if (effectiveCodePoint == SPACE || effectiveCodePoint == IDEOGRAPHIC_SPACE) {
            return -1;
        }
        return strike.codePointToGlyph.getOrDefault(effectiveCodePoint, strike.questionMarkIndex);
    }

    private int effectiveCodePoint(int codePoint) {
        if (codePoint == SPACE || codePoint == IDEOGRAPHIC_SPACE) {
            return codePoint;
        }
        if (strike.codePointToGlyph.containsKey(codePoint)) {
            return codePoint;
        }
        return REPLACEMENT_CHARACTER;
    }

    private static boolean isHalfWidth(int codePoint) {
        return codePoint <= 0x00FF || (codePoint >= 0xFF61 && codePoint <= 0xFFDC);
    }

    private static float enlargedHeightScale(int size) {
        if (size <= 16) {
            return ENLARGED_TEXT_SMALL_HEIGHT_SCALE;
        }
        if (size <= 20) {
            return ENLARGED_TEXT_MEDIUM_HEIGHT_SCALE;
        }
        if (size <= 24) {
            return ENLARGED_TEXT_DIALOGUE_HEIGHT_SCALE;
        }
        return ENLARGED_TEXT_LARGE_HEIGHT_SCALE;
    }

    private static int selectSize(int requestedSize) {
        if (requestedSize <= SUPPORTED_SIZES[0]) {
            return SUPPORTED_SIZES[0];
        }
        int selected = SUPPORTED_SIZES[0];
        for (int size : SUPPORTED_SIZES) {
            if (requestedSize < size) {
                break;
            }
            selected = size;
        }
        return selected;
    }

    private static Map<Integer, Strike> loadStrikes() {
        try {
            int[] codePoints = loadCodePoints();
            Map<Integer, Strike> strikes = new HashMap<>();
            for (int height : SUPPORTED_SIZES) {
                byte[] glyphData = readResource("glyphs-" + height + ".dat");
                int width = deriveWidth(height);
                int bytesPerRow = width / 8;
                int bytesPerGlyph = bytesPerRow * height;
                if ((glyphData.length % bytesPerGlyph) != 0) {
                    throw new IOException("Unexpected glyph file length for height " + height);
                }
                int glyphCount = glyphData.length / bytesPerGlyph;
                if (glyphCount > codePoints.length) {
                    throw new IOException("Glyph table for height " + height + " exceeds code-point table");
                }
                Map<Integer, Integer> codePointToGlyph = new HashMap<>(glyphCount * 2);
                for (int i = 0; i < glyphCount; i++) {
                    codePointToGlyph.put(codePoints[i], i);
                }
                int questionMarkIndex = codePointToGlyph.getOrDefault(REPLACEMENT_CHARACTER, -1);
                // The dump gives us raw glyph cells, not separate ascent/descent metrics. Use the
                // full strike height as the DoJa line cell and keep rendering aligned to that cell.
                strikes.put(height, new Strike(
                        height,
                        width,
                        bytesPerRow,
                        bytesPerGlyph,
                        glyphData,
                        codePointToGlyph,
                        questionMarkIndex
                ));
            }
            return strikes;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load handset bitmap fonts", e);
        }
    }

    private static int[] loadCodePoints() throws IOException {
        byte[] data = readResource("code-points.dat");
        if ((data.length & 1) != 0) {
            throw new IOException("Invalid code-point table length");
        }
        int[] codePoints = new int[data.length / 2];
        for (int i = 0, source = 0; i < codePoints.length; i++) {
            int low = data[source++] & 0xFF;
            int high = data[source++] & 0xFF;
            codePoints[i] = low | (high << 8);
        }
        return codePoints;
    }

    private static byte[] readResource(String name) throws IOException {
        try (InputStream in = _BitmapFont.class.getResourceAsStream(RESOURCE_ROOT + name)) {
            if (in == null) {
                throw new IOException("Missing bitmap font resource: " + name);
            }
            return opendoja.host.ByteStreams.readAll(in);
        }
    }

    private static int deriveWidth(int height) {
        return ((height + 7) / 8) * 8;
    }

    private record Strike(
            int height,
            int width,
            int bytesPerRow,
            int bytesPerGlyph,
            byte[] glyphData,
            Map<Integer, Integer> codePointToGlyph,
            int questionMarkIndex
    ) {
        private Strike {
            glyphData = Arrays.copyOf(glyphData, glyphData.length);
            codePointToGlyph = Map.copyOf(codePointToGlyph);
        }

        int fullAdvance() {
            return height;
        }

        int halfAdvance() {
            return height / 2;
        }
    }

    private record RenderKey(
            Strike strike,
            float widthScale,
            float heightScale,
            boolean bold,
            int argbColor,
            String text
    ) {
    }
}
