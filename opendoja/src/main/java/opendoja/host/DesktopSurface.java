package opendoja.host;

import opendoja.compat.awt.*;
import opendoja.compat.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;

public final class DesktopSurface {
    // capped at 30fps to avoid abnormal speed. Native game is 30fps.
    private static final double PRESENT_SYNC_TOKENS_PER_NANOSECOND = 30.5 / 1_000_000_000.0;
    private static final double PRESENT_SYNC_MAX_TOKENS = 2.0;
    private static final int PRESENTATION_BUFFER_COUNT = 3;
    /**
     * Serializes snapshot writers (copyForPresentation) against readers
     * (the Android view copying a frame for its own stable display buffer).
     * The rotating presentation buffers can wrap while a reader is copying,
     * which shows up as horizontal seams of old/new frame content.
     */
    public static final Object PRESENTATION_COPY_LOCK = new Object();
    private BufferedImage image;
    private final BufferedImage[] presentationBuffers = new BufferedImage[PRESENTATION_BUFFER_COUNT];
    private int presentationBufferIndex;
    private int backgroundColor = 0xFF000000;
    /**
     * Repaint hook receiving the frame snapshot and whether it was produced at an
     * application frame boundary (unlock(true)/flushGraphics/3D pass end). {@code false}
     * marks opportunistic outside-lock presents of individual draw primitives, which the
     * host may coalesce to avoid exposing partially drawn frames.
     */
    private BiConsumer<BufferedImage, Boolean> repaintHook;
    private float[] depthBuffer;
    private boolean depthFrameActive;
    private long nextRenderSyncNanos;
    private long presentTokenNanos;
    private double presentTokens = PRESENT_SYNC_MAX_TOKENS;
    private long presentWindowStartNanos;
    private int presentAccepted;
    private int presentDropped;
    private boolean openGlesSeen;
    private int activeGraphicsLocks;
    /**
     * Side framebuffer the OGL software rasterizer writes into (ARGB ints). The surface image
     * itself is bitmap-backed and cannot expose a raster, so software GL frames live here and
     * are published back into the bitmap at2D-mutation and present boundaries.
     */
    private int[] oglFramePixels;
    private boolean oglFrameInitialized;
    private int oglRenderWidth;
    private int oglRenderHeight;
    private volatile float oglRenderScaleX = 1f;
    private volatile float oglRenderScaleY = 1f;
    private float[] oglDepthBuffer;
    private boolean oglDepthFrameActive;
    private int oglDirtyLeft;
    private int oglDirtyTop;
    private int oglDirtyRight;
    private int oglDirtyBottom;
    private int[] oglSeedPixels;
    private int[] oglUpscalePixels;
    private int[] oglUpscaleX0;
    private int[] oglUpscaleX1;
    private int[] oglUpscaleXFrac;
    private int[] oglUpscaleY0;
    private int[] oglUpscaleY1;
    private int[] oglUpscaleYFrac;
    private int oglUpscaleTableWidth;
    private int oglUpscaleTableHeight;
    private int oglUpscaleTableRenderWidth;
    private int oglUpscaleTableRenderHeight;

    /** Render pixels are never allowed to shrink below this, even in tiny skins. */
    private static final int OGL_MIN_RENDER_SIZE = 32;
    /** Below this visible size a downscaled target is not worth the softness. */
    private static final float OGL_DOWNSCALE_THRESHOLD = 0.85f;
    public DesktopSurface(int width, int height) {
        this.image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    }

    public void resize(int width, int height) {
        if (image.getWidth() == width && image.getHeight() == height) {
            return;
        }
        OpenDoJaLog.info(DesktopSurface.class, "RESIZE " + image.getWidth() + "x" + image.getHeight()
                + "->" + width + "x" + height + " old=" + System.identityHashCode(image));
        BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = resized.createGraphics();
        g2.drawImage(image, 0, 0, null);
        g2.dispose();
        this.image = resized;
        OpenDoJaLog.info(DesktopSurface.class, "RESIZE done new=" + System.identityHashCode(resized));
        Arrays.fill(this.presentationBuffers, null);
        this.presentationBufferIndex = 0;
        this.depthBuffer = null;
        this.depthFrameActive = false;
        this.nextRenderSyncNanos = 0L;
        this.presentTokenNanos = 0L;
        this.presentTokens = PRESENT_SYNC_MAX_TOKENS;
        this.presentWindowStartNanos = 0L;
        this.presentAccepted = 0;
        this.presentDropped = 0;
        this.openGlesSeen = false;
        this.activeGraphicsLocks = 0;
        this.oglFramePixels = null;
        this.oglFrameInitialized = false;
        this.oglRenderWidth = 0;
        this.oglRenderHeight = 0;
        this.oglRenderScaleX = 1f;
        this.oglRenderScaleY = 1f;
        this.oglDepthBuffer = null;
        this.oglDepthFrameActive = false;
        clearOglDirty();
        this.oglSeedPixels = null;
        this.oglUpscalePixels = null;
        this.oglUpscaleX0 = null;
        this.oglUpscaleX1 = null;
        this.oglUpscaleXFrac = null;
        this.oglUpscaleY0 = null;
        this.oglUpscaleY1 = null;
        this.oglUpscaleYFrac = null;
        this.oglUpscaleTableWidth = 0;
        this.oglUpscaleTableHeight = 0;
        this.oglUpscaleTableRenderWidth = 0;
        this.oglUpscaleTableRenderHeight = 0;
    }

    public BufferedImage image() {
        // OGL software frames rasterize into oglFramePixels; anyone reading the bitmap-backed
        // surface image must see that content first.
        flushOglFrameToBitmap();
        return image;
    }

    /**
     * Returns the OGL software framebuffer. When the bitmap holds newer content (2D drew or
     * the frame was cleared since the last GL pass) it is fetched first, so GL blends against
     * the current picture; otherwise the side buffer already is the authoritative frame.
     *
     * <p>The buffer may be smaller than the logical surface when the game is displayed in a
     * small skin opening. It is scaled back up in {@link #flushOglFrameToBitmap()}.</p>
     */
    public synchronized int[] acquireOglFramePixels() {
        updateOglRenderTarget();
        if (!oglFrameInitialized) {
            seedOglFrameFromBitmap();
        }
        return oglFramePixels;
    }

    public synchronized float[] oglDepthBufferForFrame() {
        int pixelCount = Math.max(1, oglRenderWidth * oglRenderHeight);
        if (oglDepthBuffer == null || oglDepthBuffer.length != pixelCount) {
            oglDepthBuffer = new float[pixelCount];
            oglDepthFrameActive = false;
        }
        if (!oglDepthFrameActive) {
            Arrays.fill(oglDepthBuffer, Float.NEGATIVE_INFINITY);
            oglDepthFrameActive = true;
        }
        return oglDepthBuffer;
    }

    public int oglRenderWidth() {
        return oglRenderWidth;
    }

    public int oglRenderHeight() {
        return oglRenderHeight;
    }

    public float oglRenderScaleX() {
        return oglRenderScaleX;
    }

    public float oglRenderScaleY() {
        return oglRenderScaleY;
    }

    public synchronized void markOglFrameDirty() {
        if (oglFramePixels == null) {
            return;
        }
        expandOglDirtyRect(0, 0, oglRenderWidth - 1, oglRenderHeight - 1);
    }

    /** Refreshes the OGL render-target size without seeding it from the bitmap. */
    public synchronized void ensureOglRenderTarget() {
        updateOglRenderTarget();
    }

    /**
     * Returns the side framebuffer for a hardware full-frame readback. Unlike
     * {@link #acquireOglFramePixels()} this does not copy the bitmap first: the
     * caller overwrites every pixel with the GPU result.
     */
    public synchronized int[] acquireOglFramePixelsForOverwrite() {
        updateOglRenderTarget();
        return oglFramePixels;
    }

    /** Publishes a full-frame hardware readback so it can reach the bitmap. */
    public synchronized void completeOglFrameReadback() {
        if (oglFramePixels == null) {
            return;
        }
        oglFrameInitialized = true;
        markOglFrameDirty();
    }

    /** Records the render-target bounds touched by a rasterized triangle. */
    public synchronized void expandOglDirtyRect(int minX, int minY, int maxX, int maxY) {
        if (oglFramePixels == null) {
            return;
        }
        if (maxX < 0 || maxY < 0 || minX >= oglRenderWidth || minY >= oglRenderHeight) {
            return;
        }
        int left = Math.max(0, minX);
        int top = Math.max(0, minY);
        int right = Math.min(oglRenderWidth - 1, maxX);
        int bottom = Math.min(oglRenderHeight - 1, maxY);
        if (left > right || top > bottom) {
            return;
        }
        oglDirtyLeft = Math.min(oglDirtyLeft, left);
        oglDirtyTop = Math.min(oglDirtyTop, top);
        oglDirtyRight = Math.max(oglDirtyRight, right);
        oglDirtyBottom = Math.max(oglDirtyBottom, bottom);
    }

    /**
     * Mirrors a full-surface glClear into the render target so the following 3D pass does not
     * have to downsample the freshly cleared bitmap. Partial clears fall back to a normal seed.
     */
    public synchronized void onOglColorClear(int x, int y, int width, int height, int argb) {
        if (width <= 0 || height <= 0) {
            return;
        }
        updateOglRenderTarget();
        int surfaceWidth = image.getWidth();
        int surfaceHeight = image.getHeight();
        boolean full = x <= 0 && y <= 0
                && x + width >= surfaceWidth && y + height >= surfaceHeight;
        if (full) {
            Arrays.fill(oglFramePixels, argb);
            oglFrameInitialized = true;
            clearOglDirty();
        } else {
            oglFrameInitialized = false;
            clearOglDirty();
        }
    }

    public synchronized void clearOglDepthFrame() {
        oglDepthFrameActive = false;
    }

    /** Publishes pending OGL software framebuffer content into the bitmap-backed image. */
    public synchronized void flushOglFrameToBitmap() {
        if (oglFrameInitialized && oglFramePixels != null
                && oglDirtyLeft <= oglDirtyRight && oglDirtyTop <= oglDirtyBottom) {
            int surfaceWidth = image.getWidth();
            int surfaceHeight = image.getHeight();
            int renderWidth = oglRenderWidth;
            int renderHeight = oglRenderHeight;
            if (renderWidth > 0 && renderHeight > 0) {
                int dstLeft = (int) Math.floor((double) oglDirtyLeft * surfaceWidth / renderWidth);
                int dstTop = (int) Math.floor((double) oglDirtyTop * surfaceHeight / renderHeight);
                int dstRight = (int) Math.ceil((double) (oglDirtyRight + 1) * surfaceWidth / renderWidth) - 1;
                int dstBottom = (int) Math.ceil((double) (oglDirtyBottom + 1) * surfaceHeight / renderHeight) - 1;
                dstLeft = clamp(dstLeft, 0, surfaceWidth - 1);
                dstTop = clamp(dstTop, 0, surfaceHeight - 1);
                dstRight = clamp(dstRight, dstLeft, surfaceWidth - 1);
                dstBottom = clamp(dstBottom, dstTop, surfaceHeight - 1);
                ensureOglUpscaleTables(surfaceWidth, surfaceHeight, renderWidth, renderHeight);
                int[] output = ensureOglUpscalePixels(surfaceWidth * surfaceHeight);
                if (renderWidth == surfaceWidth && renderHeight == surfaceHeight) {
                    for (int y = dstTop; y <= dstBottom; y++) {
                        int row = y * surfaceWidth;
                        for (int x = dstLeft; x <= dstRight; x++) {
                            output[row + x] = oglFramePixels[row + x] | 0xFF000000;
                        }
                    }
                } else {
                    int[] x0 = oglUpscaleX0;
                    int[] x1 = oglUpscaleX1;
                    int[] xf = oglUpscaleXFrac;
                    int[] y0 = oglUpscaleY0;
                    int[] y1 = oglUpscaleY1;
                    int[] yf = oglUpscaleYFrac;
                    for (int y = dstTop; y <= dstBottom; y++) {
                        int row0 = y0[y] * renderWidth;
                        int row1 = y1[y] * renderWidth;
                        int fy = yf[y];
                        int inverseFy = 256 - fy;
                        int outputRow = y * surfaceWidth;
                        for (int x = dstLeft; x <= dstRight; x++) {
                            int col0 = x0[x];
                            int col1 = x1[x];
                            int fx = xf[x];
                            int inverseFx = 256 - fx;
                            int c00 = oglFramePixels[row0 + col0];
                            int c10 = oglFramePixels[row0 + col1];
                            int c01 = oglFramePixels[row1 + col0];
                            int c11 = oglFramePixels[row1 + col1];
                            int w00 = inverseFx * inverseFy;
                            int w10 = fx * inverseFy;
                            int w01 = inverseFx * fy;
                            int w11 = fx * fy;
                            int red = ((((c00 >>> 16) & 0xFF) * w00)
                                    + (((c10 >>> 16) & 0xFF) * w10)
                                    + (((c01 >>> 16) & 0xFF) * w01)
                                    + (((c11 >>> 16) & 0xFF) * w11) + 32768) >>> 16;
                            int green = ((((c00 >>> 8) & 0xFF) * w00)
                                    + (((c10 >>> 8) & 0xFF) * w10)
                                    + (((c01 >>> 8) & 0xFF) * w01)
                                    + (((c11 >>> 8) & 0xFF) * w11) + 32768) >>> 16;
                            int blue = (((c00 & 0xFF) * w00)
                                    + ((c10 & 0xFF) * w10)
                                    + ((c01 & 0xFF) * w01)
                                    + ((c11 & 0xFF) * w11) + 32768) >>> 16;
                            output[outputRow + x] = 0xFF000000 | (red << 16) | (green << 8) | blue;
                        }
                    }
                }
                image.getBitmap().setPixels(output, dstTop * surfaceWidth + dstLeft,
                        surfaceWidth, dstLeft, dstTop, dstRight - dstLeft + 1, dstBottom - dstTop + 1);
            }
        }
        // A flushed buffer is stale for any later 2D mutation; the next GL pass seeds from
        // the bitmap again, matching the previous full-size framebuffer behaviour.
        oglFrameInitialized = false;
        oglDepthFrameActive = false;
        clearOglDirty();
    }

    private void updateOglRenderTarget() {
        int surfaceWidth = image.getWidth();
        int surfaceHeight = image.getHeight();
        int targetWidth = HostDisplayTarget.width();
        int targetHeight = HostDisplayTarget.height();
        int renderWidth = surfaceWidth;
        int renderHeight = surfaceHeight;
        if (targetWidth > 0 && targetWidth < surfaceWidth * OGL_DOWNSCALE_THRESHOLD) {
            renderWidth = Math.max(OGL_MIN_RENDER_SIZE, Math.min(surfaceWidth, targetWidth));
        }
        if (targetHeight > 0 && targetHeight < surfaceHeight * OGL_DOWNSCALE_THRESHOLD) {
            renderHeight = Math.max(OGL_MIN_RENDER_SIZE, Math.min(surfaceHeight, targetHeight));
        }
        if (renderWidth == oglRenderWidth && renderHeight == oglRenderHeight
                && oglFramePixels != null) {
            return;
        }
        oglRenderWidth = renderWidth;
        oglRenderHeight = renderHeight;
        oglFramePixels = new int[renderWidth * renderHeight];
        oglDepthBuffer = new float[renderWidth * renderHeight];
        oglDepthFrameActive = false;
        oglFrameInitialized = false;
        clearOglDirty();
        oglRenderScaleX = renderWidth / (float) surfaceWidth;
        oglRenderScaleY = renderHeight / (float) surfaceHeight;
        oglUpscaleTableWidth = 0;
        oglUpscaleTableHeight = 0;
        oglUpscaleTableRenderWidth = 0;
        oglUpscaleTableRenderHeight = 0;
        OpenDoJaLog.info(DesktopSurface.class, "OGL target " + surfaceWidth + "x" + surfaceHeight
                + " -> " + renderWidth + "x" + renderHeight
                + " display=" + targetWidth + "x" + targetHeight);
    }

    private void seedOglFrameFromBitmap() {
        int surfaceWidth = image.getWidth();
        int surfaceHeight = image.getHeight();
        int renderWidth = oglRenderWidth;
        int renderHeight = oglRenderHeight;
        if (renderWidth <= 0 || renderHeight <= 0) {
            return;
        }
        int surfacePixels = surfaceWidth * surfaceHeight;
        if (oglSeedPixels == null || oglSeedPixels.length != surfacePixels) {
            oglSeedPixels = new int[surfacePixels];
        }
        image.getBitmap().getPixels(oglSeedPixels, 0, surfaceWidth, 0, 0, surfaceWidth, surfaceHeight);
        if (renderWidth == surfaceWidth && renderHeight == surfaceHeight) {
            System.arraycopy(oglSeedPixels, 0, oglFramePixels, 0, surfacePixels);
        } else {
            for (int y = 0; y < renderHeight; y++) {
                int sourceRow = (int) ((long) y * surfaceHeight / renderHeight) * surfaceWidth;
                int targetRow = y * renderWidth;
                for (int x = 0; x < renderWidth; x++) {
                    int sourceColumn = (int) ((long) x * surfaceWidth / renderWidth);
                    oglFramePixels[targetRow + x] = oglSeedPixels[sourceRow + sourceColumn];
                }
            }
        }
        oglFrameInitialized = true;
        oglDepthFrameActive = false;
        clearOglDirty();
    }

    private void ensureOglUpscaleTables(int surfaceWidth, int surfaceHeight,
                                        int renderWidth, int renderHeight) {
        if (oglUpscaleTableWidth == surfaceWidth && oglUpscaleTableHeight == surfaceHeight
                && oglUpscaleTableRenderWidth == renderWidth
                && oglUpscaleTableRenderHeight == renderHeight) {
            return;
        }
        oglUpscaleX0 = new int[surfaceWidth];
        oglUpscaleX1 = new int[surfaceWidth];
        oglUpscaleXFrac = new int[surfaceWidth];
        for (int x = 0; x < surfaceWidth; x++) {
            float source = ((x + 0.5f) * renderWidth / surfaceWidth) - 0.5f;
            int base = (int) Math.floor(source);
            float fraction = source - base;
            if (base < 0) {
                base = 0;
                fraction = 0f;
            } else if (base >= renderWidth - 1) {
                base = Math.max(0, renderWidth - 1);
                oglUpscaleX0[x] = base;
                oglUpscaleX1[x] = base;
                oglUpscaleXFrac[x] = 0;
                continue;
            }
            oglUpscaleX0[x] = base;
            oglUpscaleX1[x] = Math.min(renderWidth - 1, base + 1);
            oglUpscaleXFrac[x] = clamp((int) (fraction * 256f + 0.5f), 0, 255);
        }
        oglUpscaleY0 = new int[surfaceHeight];
        oglUpscaleY1 = new int[surfaceHeight];
        oglUpscaleYFrac = new int[surfaceHeight];
        for (int y = 0; y < surfaceHeight; y++) {
            float source = ((y + 0.5f) * renderHeight / surfaceHeight) - 0.5f;
            int base = (int) Math.floor(source);
            float fraction = source - base;
            if (base < 0) {
                base = 0;
                fraction = 0f;
            } else if (base >= renderHeight - 1) {
                base = Math.max(0, renderHeight - 1);
                oglUpscaleY0[y] = base;
                oglUpscaleY1[y] = base;
                oglUpscaleYFrac[y] = 0;
                continue;
            }
            oglUpscaleY0[y] = base;
            oglUpscaleY1[y] = Math.min(renderHeight - 1, base + 1);
            oglUpscaleYFrac[y] = clamp((int) (fraction * 256f + 0.5f), 0, 255);
        }
        oglUpscaleTableWidth = surfaceWidth;
        oglUpscaleTableHeight = surfaceHeight;
        oglUpscaleTableRenderWidth = renderWidth;
        oglUpscaleTableRenderHeight = renderHeight;
    }

    private int[] ensureOglUpscalePixels(int pixelCount) {
        if (oglUpscalePixels == null || oglUpscalePixels.length != pixelCount) {
            oglUpscalePixels = new int[pixelCount];
        }
        return oglUpscalePixels;
    }

    private void clearOglDirty() {
        oglDirtyLeft = Integer.MAX_VALUE;
        oglDirtyTop = Integer.MAX_VALUE;
        oglDirtyRight = Integer.MIN_VALUE;
        oglDirtyBottom = Integer.MIN_VALUE;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return value < minimum ? minimum : Math.min(value, maximum);
    }

    public int width() {
        return image.getWidth();
    }

    public int height() {
        return image.getHeight();
    }

    public int backgroundColor() {
        return backgroundColor;
    }

    public void setBackgroundColor(int backgroundColor) {
        this.backgroundColor = backgroundColor;
    }

    public void setRepaintHook(BiConsumer<BufferedImage, Boolean> repaintHook) {
        this.repaintHook = repaintHook;
    }

    public boolean hasRepaintHook() {
        return repaintHook != null;
    }

    public synchronized float[] depthBufferForFrame() {
        int pixelCount = image.getWidth() * image.getHeight();
        if (depthBuffer == null || depthBuffer.length != pixelCount) {
            depthBuffer = new float[pixelCount];
            depthFrameActive = false;
        }
        if (!depthFrameActive) {
            Arrays.fill(depthBuffer, Float.NEGATIVE_INFINITY);
            depthFrameActive = true;
        }
        return depthBuffer;
    }

    public synchronized void endDepthFrame() {
        depthFrameActive = false;
        oglDepthFrameActive = false;
    }

    public synchronized void markOpenGlesActivity() {
        openGlesSeen = true;
    }

    public synchronized boolean hasOpenGlesActivity() {
        return openGlesSeen;
    }

    public synchronized void beginGraphicsLock() {
        activeGraphicsLocks++;
    }

    public synchronized void endGraphicsLock() {
        if (activeGraphicsLocks > 0) {
            activeGraphicsLocks--;
        }
    }

    public synchronized boolean hasActiveGraphicsLock() {
        return activeGraphicsLocks > 0;
    }

    /**
     * Clears the canvas framebuffer to {@code argb} and discards any pending software-GL frame.
     *
     * <p>Used only by the opt-in "clear canvas on lock" compatibility path. The next GL pass
     * seeds from the cleared bitmap instead of publishing stale triangles from the prior frame.</p>
     */
    public synchronized void clearCanvasFrame(int argb) {
        image.getBitmap().eraseColor(argb);
        oglFrameInitialized = false;
        oglDepthFrameActive = false;
        clearOglDirty();
    }

    public synchronized BufferedImage copyForPresentation() {
        synchronized (PRESENTATION_COPY_LOCK) {
            flushOglFrameToBitmap();
            int width = image.getWidth();
            int height = image.getHeight();
            presentationBufferIndex = (presentationBufferIndex + 1) % PRESENTATION_BUFFER_COUNT;
            BufferedImage copy = presentationBuffers[presentationBufferIndex];
            if (copy == null || copy.getWidth() != width || copy.getHeight() != height) {
                copy = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                presentationBuffers[presentationBufferIndex] = copy;
            }
            // Presentation must remain stable while the app keeps drawing and while the EDT may still
            // be blitting one of the previous snapshots into the host window.
            Graphics2D g2 = copy.createGraphics();
            try {
                // The handset LCD has no alpha channel: the surface's RGB is the picture even where
                // the software GL mask passes left alpha at zero. Composite with Src, not SrcOver, so
                // transparent surface pixels still replace (rather than preserve) the reused snapshot.
                // Otherwise stale pixels from an earlier frame - e.g. the boot white screen - reappear
                // as white boxes wherever a GL mask pass zeroed the framebuffer alpha.
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC, 1.0f));
                g2.drawImage(image, 0, 0, null);
            } finally {
                g2.dispose();
            }
            // The handset LCD has no alpha channel: RGB is displayed as-is. Marking the snapshot
            // opaque is enough; the previous getPixels/OR/setPixels round-trip copied the whole
            // frame through the JNI and hwui color converters on every presented frame.
            copy.getBitmap().setHasAlpha(false);
            return copy;
        }
    }

    public synchronized void waitForRenderSync(long intervalNanos) {
        if (intervalNanos <= 0L || repaintHook == null) {
            return;
        }
        long now = System.nanoTime();
        long target = nextRenderSyncNanos == 0L ? now + intervalNanos : nextRenderSyncNanos + intervalNanos;
        if (target > now) {
            LockSupport.parkNanos(target - now);
            nextRenderSyncNanos = target;
            return;
        }
        nextRenderSyncNanos = now;
    }

    public void flush(BufferedImage presentedFrame) {
        flush(presentedFrame, true, true);
    }

    public void flush(BufferedImage presentedFrame, boolean paced) {
        flush(presentedFrame, paced, true);
    }

    public void flush(BufferedImage presentedFrame, boolean paced, boolean frameBoundary) {
        if (!shouldPresentNow(paced)) {
            return;
        }
        present(presentedFrame, frameBoundary);
    }

    /**
     * Presents the current surface, taking the snapshot only after the pacing
     * check. Callers that snapshot eagerly (once per unlock) otherwise rotate
     * the presentation buffers even for frames that are never displayed, letting
     * a buffer be overwritten while a reader is still copying it.
     */
    public void flushSnapshot(boolean paced, boolean frameBoundary) {
        if (!shouldPresentNow(paced)) {
            return;
        }
        present(copyForPresentation(), frameBoundary);
    }

    private boolean shouldPresentNow(boolean paced) {
        long now = System.nanoTime();
        // Every present goes through the cap, including direct-canvas and
        // per-pass flushes. Coalescing is instant (frames are dropped, never
        // slept), so a burst of intermediate presents cannot stall the game;
        // the next accepted present carries the latest surface content.
        if (presentTokenNanos == 0L) {
            presentTokenNanos = now;
        }
        double refill = (now - presentTokenNanos) * PRESENT_SYNC_TOKENS_PER_NANOSECOND;
        presentTokenNanos = now;
        if (refill > 0.0) {
            presentTokens = Math.min(PRESENT_SYNC_MAX_TOKENS, presentTokens + refill);
        }
        if (presentTokens < 1.0) {
            presentDropped++;
            logPresentStatsIfDue(now);
            return false;
        }
        presentTokens -= 1.0;
        presentAccepted++;
        logPresentStatsIfDue(now);
        return true;
    }

    private void logPresentStatsIfDue(long now) {
        if (presentWindowStartNanos == 0L) {
            presentWindowStartNanos = now;
            return;
        }
        long windowNanos = now - presentWindowStartNanos;
        if (windowNanos < 2_000_000_000L) {
            return;
        }
        if (OpenDoJaLog.isDebugEnabled()) {
            double seconds = windowNanos / 1_000_000_000.0;
            OpenDoJaLog.debug(DesktopSurface.class, String.format(
                    java.util.Locale.US,
                    "PRESENT stats %.1fs fps=%.1f accepted=%d dropped=%d",
                    seconds, presentAccepted / seconds, presentAccepted, presentDropped));
        }
        presentWindowStartNanos = now;
        presentAccepted = 0;
        presentDropped = 0;
    }

    private void present(BufferedImage presentedFrame, boolean frameBoundary) {
        endDepthFrame();
        if (repaintHook != null) {
            repaintHook.accept(presentedFrame, frameBoundary);
        }
    }

    @Override
    public String toString() {
        return "DesktopSurface{" + image.getWidth() + "x" + image.getHeight() + ", background=" + backgroundColor + "}";
    }
}
