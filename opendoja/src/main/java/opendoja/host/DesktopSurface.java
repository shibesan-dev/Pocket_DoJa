package opendoja.host;

import opendoja.compat.awt.*;
import opendoja.compat.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;

public final class DesktopSurface {
    // DoJa/i-appli titles are handset-bound (~10-30 fps); capping the software present path
    // at ~30 fps prevents fast loops from burning CPU/heat without hurting the games.
    private static final long PRESENT_SYNC_INTERVAL_NANOS = 33_000_000L;
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
    private boolean openGlesSeen;
    private int activeGraphicsLocks;
    /**
     * Side framebuffer the OGL software rasterizer writes into (ARGB ints). The surface image
     * itself is bitmap-backed and cannot expose a raster, so software GL frames live here and
     * are published back into the bitmap at2D-mutation and present boundaries.
     */
    private int[] oglFramePixels;
    private volatile boolean oglFrameDirty;
    private int[] presentationPixels;
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
        this.openGlesSeen = false;
        this.activeGraphicsLocks = 0;
        this.oglFramePixels = null;
        this.oglFrameDirty = false;
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
     */
    public synchronized int[] acquireOglFramePixels() {
        int width = image.getWidth();
        int height = image.getHeight();
        int pixelCount = width * height;
        if (oglFramePixels == null || oglFramePixels.length != pixelCount) {
            oglFramePixels = new int[pixelCount];
            oglFrameDirty = false;
        }
        if (!oglFrameDirty) {
            image.getBitmap().getPixels(oglFramePixels, 0, width, 0, 0, width, height);
        }
        return oglFramePixels;
    }

    public void markOglFrameDirty() {
        oglFrameDirty = true;
    }

    /** Publishes pending OGL software framebuffer content into the bitmap-backed image. */
    public void flushOglFrameToBitmap() {
        if (!oglFrameDirty) {
            return;
        }
        synchronized (this) {
            if (!oglFrameDirty || oglFramePixels == null) {
                return;
            }
            int width = image.getWidth();
            int height = image.getHeight();
            // The handset LCD has no alpha channel. The GL framebuffer alpha is not part of the
            // displayed picture, and Android bitmaps store premultiplied colors, so publishing
            // RGB with alpha zero would erase pixels that are visible on real hardware.
            for (int i = 0; i < oglFramePixels.length; i++) {
                oglFramePixels[i] |= 0xFF000000;
            }
            image.getBitmap().setPixels(oglFramePixels, 0, width, 0, 0, width, height);
            oglFrameDirty = false;
        }
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
            // The handset LCD has no alpha channel: RGB is displayed as-is. Software GL blending can
            // leave framebuffer alpha at zero (e.g. mask passes using GL_ZERO/GL_ONE_MINUS_SRC_COLOR),
            // and compositing that snapshot as transparent would erase the drawn RGB on the host.
            forceOpaquePresentation(copy, width, height);
            return copy;
        }
    }

    private void forceOpaquePresentation(BufferedImage frame, int width, int height) {
        int[] pixels = presentationPixels;
        if (pixels == null || pixels.length != width * height) {
            pixels = new int[width * height];
            presentationPixels = pixels;
        }
        frame.getBitmap().getPixels(pixels, 0, width, 0, 0, width, height);
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] |= 0xFF000000;
        }
        frame.getBitmap().setPixels(pixels, 0, width, 0, 0, width, height);
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
        if (!paced) {
            return true;
        }
        // Cap the on-screen present rate by COALESCING, never by sleeping the game
        // thread. Blocking here made titles that present many times while building their
        // startup display list take minutes to leave "Booting...". If a present arrives
        // before the interval, drop it (the surface keeps the newest content and the next
        // present after the interval shows it); the game itself runs at full speed.
        long now = System.nanoTime();
        if (nextRenderSyncNanos != 0L && now < nextRenderSyncNanos) {
            return false;
        }
        nextRenderSyncNanos = now + PRESENT_SYNC_INTERVAL_NANOS;
        return true;
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
