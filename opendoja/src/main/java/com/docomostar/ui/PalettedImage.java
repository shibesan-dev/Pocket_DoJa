package com.docomostar.ui;

import com.nttdocomo.lang.UnsupportedOperationException;
import com.nttdocomo.ui.UIException;

import opendoja.compat.imageio.ImageIO;
import opendoja.compat.awt.image.BufferedImage;
import opendoja.compat.awt.image.IndexColorModel;
import opendoja.compat.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Defines an image whose pixel indices and palette are stored separately so
 * callers can swap palettes after creation.
 */
public class PalettedImage extends Image {
    private int width;
    private int height;
    private byte[] pixels;
    private Palette palette;
    private int alpha = 255;
    private int transparentIndex = -1;
    private int appliedTransparentIndex = -1;
    private boolean transparentEnabled;
    private int mutationVersion = 1;
    private BufferedImage displayCache;
    private Palette displayCachePalette;
    private int displayCacheMutationVersion = -1;
    private int displayCachePaletteVersion = -1;

    /**
     * Applications cannot create this class directly.
     */
    protected PalettedImage() {
    }

    public static PalettedImage createPalettedImage(byte[] data) {
        if (data == null) {
            throw new NullPointerException("data");
        }
        try {
            return createPalettedImage(new ByteArrayInputStream(data));
        } catch (IOException e) {
            throw new UIException(UIException.UNSUPPORTED_FORMAT, e.getMessage());
        }
    }

    public static PalettedImage createPalettedImage(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            throw new NullPointerException("inputStream");
        }
        BufferedImage image = ImageIO.read(inputStream);
        if (image == null) {
            throw new IOException("Unsupported paletted image format");
        }
        if (image.getColorModel() instanceof IndexColorModel colorModel) {
            return fromIndexedImage(image, colorModel);
        }
        return fromArgbImage(image);
    }

    public static PalettedImage createPalettedImage(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid dimensions");
        }
        int[] colors = new int[256];
        PalettedImage image = new PalettedImage();
        image.width = width;
        image.height = height;
        image.pixels = new byte[width * height];
        image.palette = new Palette(colors);
        return image;
    }

    public void changeData(InputStream inputStream) {
        ensureActive();
        try {
            PalettedImage updated = createPalettedImage(inputStream);
            replaceWith(updated);
        } catch (IOException e) {
            throw new UIException(UIException.UNSUPPORTED_FORMAT, e.getMessage());
        }
    }

    public void changeData(byte[] data) {
        ensureActive();
        replaceWith(createPalettedImage(data));
    }

    public void setPalette(Palette palette) {
        ensureActive();
        if (palette == null) {
            throw new NullPointerException("palette");
        }
        if (this.palette != null && this.palette.getEntryCount() != palette.getEntryCount()) {
            throw new IllegalArgumentException("Palette size mismatch");
        }
        this.palette = palette;
        mutationVersion++;
    }

    public Palette getPalette() {
        ensureActive();
        if (palette == null) {
            throw new UIException(UIException.ILLEGAL_STATE);
        }
        return palette;
    }

    @Override
    public final com.docomostar.ui.Graphics getGraphics() {
        throw new UnsupportedOperationException("PalettedImage graphics are not supported");
    }

    @Override
    public final void setTransparentEnabled(boolean enabled) {
        ensureActive();
        this.transparentEnabled = enabled;
        this.appliedTransparentIndex = enabled ? getTransparentIndex() : -1;
        mutationVersion++;
    }

    public void setTransparentIndex(int index) {
        ensureActive();
        if (index < 0 || index >= 256) {
            throw new ArrayIndexOutOfBoundsException(index);
        }
        this.transparentIndex = index;
        this.transparentEnabled = true;
        this.appliedTransparentIndex = index;
        mutationVersion++;
    }

    public int getTransparentIndex() {
        ensureActive();
        return transparentIndex < 0 ? 0 : transparentIndex;
    }

    @Override
    public void setAlpha(int alpha) {
        ensureActive();
        if (alpha < 0 || alpha > 255) {
            throw new IllegalArgumentException("alpha out of range: " + alpha);
        }
        this.alpha = alpha;
    }

    @Override
    public int getAlpha() {
        ensureActive();
        return alpha;
    }

    @Override
    public final int getTransparentColor() {
        throw new UnsupportedOperationException("PalettedImage uses transparent indices");
    }

    @Override
    public final void setTransparentColor(int color) {
        throw new UnsupportedOperationException("PalettedImage uses transparent indices");
    }

    @Override
    public void dispose() {
        pixels = null;
        palette = null;
        alpha = 255;
        appliedTransparentIndex = -1;
        displayCache = null;
        displayCachePalette = null;
        mutationVersion++;
    }

    @Override
    public int getWidth() {
        ensureActive();
        return width;
    }

    @Override
    public int getHeight() {
        ensureActive();
        return height;
    }

    @Override
    protected BufferedImage renderForDisplayImpl() {
        if (pixels == null || palette == null) {
            return null;
        }
        int paletteVersion = palette.getVersion();
        if (displayCache != null
                && displayCacheMutationVersion == mutationVersion
                && displayCachePalette == palette
                && displayCachePaletteVersion == paletteVersion) {
            return displayCache;
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] argbPixels = new int[width * height];
        for (int y = 0, pos = 0; y < height; y++) {
            for (int x = 0; x < width; x++, pos++) {
                int index = pixels[pos] & 0xFF;
                int argb;
                if (index < palette.getEntryCount()) {
                    argb = palette.getArgbEntry(index);
                } else {
                    argb = 0;
                }
                if (transparentEnabled && appliedTransparentIndex >= 0
                        && appliedTransparentIndex < palette.getEntryCount()
                        && index == appliedTransparentIndex) {
                    argb &= 0x00FFFFFF;
                }
                argbPixels[pos] = argb;
            }
        }
        image.setRGB(0, 0, width, height, argbPixels, 0, width);
        displayCache = image;
        displayCachePalette = palette;
        displayCacheMutationVersion = mutationVersion;
        displayCachePaletteVersion = paletteVersion;
        return displayCache;
    }

    private void replaceWith(PalettedImage updated) {
        this.width = updated.width;
        this.height = updated.height;
        this.pixels = updated.pixels;
        this.palette = updated.palette;
        this.alpha = updated.alpha;
        this.transparentIndex = updated.transparentIndex;
        this.appliedTransparentIndex = updated.appliedTransparentIndex;
        this.transparentEnabled = updated.transparentEnabled;
        this.displayCache = null;
        this.displayCachePalette = null;
        this.mutationVersion++;
    }

    private static PalettedImage fromIndexedImage(BufferedImage image, IndexColorModel colorModel) {
        int paletteSize = colorModel.getMapSize();
        int[] colors = new int[paletteSize];
        for (int i = 0; i < paletteSize; i++) {
            colors[i] = colorModel.getRGB(i);
        }
        byte[] pixels = new byte[image.getWidth() * image.getHeight()];
        Raster raster = image.getRaster();
        for (int y = 0, pos = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++, pos++) {
                pixels[pos] = (byte) raster.getSample(x, y, 0);
            }
        }
        PalettedImage result = new PalettedImage();
        result.width = image.getWidth();
        result.height = image.getHeight();
        result.pixels = pixels;
        result.palette = new Palette(colors);
        result.transparentIndex = colorModel.getTransparentPixel();
        if (result.transparentIndex >= 0) {
            result.transparentEnabled = true;
            result.appliedTransparentIndex = result.transparentIndex;
        } else {
            result.appliedTransparentIndex = -1;
        }
        return result;
    }

    private static PalettedImage fromArgbImage(BufferedImage image) {
        int[] colors = new int[256];
        java.util.Map<Integer, Integer> indices = new java.util.LinkedHashMap<>();
        byte[] pixels = new byte[image.getWidth() * image.getHeight()];
        int nextIndex = 0;
        for (int y = 0, pos = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++, pos++) {
                int argb = image.getRGB(x, y);
                Integer existing = indices.get(argb);
                if (existing == null) {
                    if (nextIndex >= 256) {
                        existing = 0;
                    } else {
                        existing = nextIndex;
                        indices.put(argb, existing);
                        colors[nextIndex++] = argb;
                    }
                }
                pixels[pos] = existing.byteValue();
            }
        }
        int[] paletteColors = new int[java.lang.Math.max(1, indices.size())];
        for (java.util.Map.Entry<Integer, Integer> entry : indices.entrySet()) {
            paletteColors[entry.getValue()] = entry.getKey();
        }
        PalettedImage result = new PalettedImage();
        result.width = image.getWidth();
        result.height = image.getHeight();
        result.pixels = pixels;
        result.palette = new Palette(paletteColors);
        return result;
    }

    private void ensureActive() {
        if (pixels == null || palette == null) {
            throw new UIException(UIException.ILLEGAL_STATE, "PalettedImage is disposed");
        }
    }
}
