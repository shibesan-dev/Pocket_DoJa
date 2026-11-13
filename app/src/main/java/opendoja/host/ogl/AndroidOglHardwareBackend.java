package opendoja.host.ogl;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES10;
import android.opengl.GLES11;

import com.nttdocomo.ui.ogl.ByteBuffer;
import com.nttdocomo.ui.ogl.DirectBuffer;
import com.nttdocomo.ui.ogl.DirectBufferFactory;
import com.nttdocomo.ui.ogl.FloatBuffer;
import com.nttdocomo.ui.ogl.GraphicsOGL;
import com.nttdocomo.ui.ogl.ShortBuffer;

import java.util.IdentityHashMap;
import java.util.Map;

import opendoja.compat.awt.Rectangle;
import opendoja.host.DesktopSurface;
import opendoja.host.OpenDoJaLog;

/**
 * Android EGL/GLES1 scene backend.
 *
 * <p>The guest still talks to {@link OglRenderer}; this class replays the mirrored
 * fixed-function state into an offscreen pbuffer and reads the finished frame back
 * into {@link DesktopSurface}. The software rasterizer remains the reference and
 * handles any draw or driver feature this backend cannot reproduce.</p>
 *
 * <p>Vertex skinning and per-vertex color-material lighting are evaluated on the CPU
 * because Android's GLES1 bindings do not expose {@code glColorMaterial}, while the
 * DoJa software renderer treats the current color or color array as the material
 * ambient/diffuse terms.</p>
 */
final class AndroidOglHardwareBackend extends OglHardwareBackend {
    private static final int EGL_OPENGL_ES_API = EGL14.EGL_OPENGL_ES_API;
    private static final int EGL_OPENGL_ES_BIT = EGL14.EGL_OPENGL_ES_BIT;
    private static final float[] IDENTITY = {
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
    };

    private static final int CAP_BLEND = 0;
    private static final int CAP_DEPTH = 1;
    private static final int CAP_LIGHTING = 2;
    private static final int CAP_ALPHA = 3;
    private static final int CAP_CULL = 4;
    private static final int CAP_NORMALIZE = 5;
    private static final int CAP_RESCALE = 6;
    private static final int CAP_TEXTURE0 = 7;
    private static final int CAP_TEXTURE1 = 8;
    private static final int CAP_COUNT = 9;

    private static final int UNIT_COUNT = 2;
    private static final int MAX_LIGHTS = 8;

    static OglHardwareBackend create(OglRenderer owner) {
        try {
            return new AndroidOglHardwareBackend(owner);
        } catch (Throwable throwable) {
            OpenDoJaLog.warn(AndroidOglHardwareBackend.class,
                    "Hardware OpenGL ES backend unavailable: "
                            + OpenDoJaLog.throwableToString(throwable));
            return null;
        }
    }

    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private EGLConfig eglConfig;
    private boolean initialized;
    private boolean failed;
    private boolean npotSupported;
    private int maxTextureSize = 2048;
    private int targetWidth;
    private int targetHeight;
    private Thread ownerThread;

    // Framebuffer authority flags.
    private boolean frameColorValid;
    private boolean frameColorFresh;
    private boolean frameDepthValid;
    private boolean colorDirty = true;
    private boolean presentedAnyFrame;

    // State caches.
    private final boolean[] capValues = new boolean[CAP_COUNT];
    private final boolean[] capKnown = new boolean[CAP_COUNT];
    private final float[] loadedProjection = new float[16];
    private final float[] loadedModelView = new float[16];
    private boolean projectionLoaded;
    private boolean modelViewLoaded;
    private int activeTextureUnit = -1;
    private final int[] boundTextureIds = {-1, -1};
    private final float[][] loadedTextureMatrices = {new float[16], new float[16]};
    private final boolean[] textureMatrixLoaded = new boolean[UNIT_COUNT];
    private final TextureEnvCache[] textureEnvCaches = {
            new TextureEnvCache(), new TextureEnvCache()
    };
    private int shadeModel = -1;
    private boolean depthMaskState;
    private boolean depthMaskKnown;
    private int blendSrc = -1;
    private int blendDst = -1;
    private int depthFunc = -1;
    private int alphaFunc = -1;
    private float alphaRef = Float.NaN;
    private int cullFace = -1;
    private int frontFace = -1;
    private int currentColor = Integer.MIN_VALUE;
    private final float[] currentNormal = new float[3];
    private boolean currentNormalKnown;
    private int viewportX = Integer.MIN_VALUE;
    private int viewportY;
    private int viewportWidth;
    private int viewportHeight;
    private float depthRangeNear = Float.NaN;
    private float depthRangeFar = Float.NaN;
    private boolean scissorEnabled;
    private int scissorX;
    private int scissorY;
    private int scissorWidth;
    private int scissorHeight;
    private boolean scissorKnown;
    private int clientTextureUnit = -1;
    private boolean textureStateUnrepresentable;

    // Lighting/material cache. Uploaded only for native lit draws.
    private final float[][] lightAmbient = new float[MAX_LIGHTS][4];
    private final float[][] lightDiffuse = new float[MAX_LIGHTS][4];
    private final float[][] lightSpecular = new float[MAX_LIGHTS][4];
    private final float[][] lightPosition = new float[MAX_LIGHTS][4];
    private final float[][] lightSpotDirection = new float[MAX_LIGHTS][3];
    private final float[][] lightScalars = new float[MAX_LIGHTS][5];
    private final boolean[] lightDataKnown = new boolean[MAX_LIGHTS];
    private final boolean[] lightEnabled = new boolean[MAX_LIGHTS];
    private final boolean[] lightEnabledKnown = new boolean[MAX_LIGHTS];
    private final float[] lightModelAmbient = new float[4];
    private boolean lightModelAmbientKnown;
    private boolean lightModelTwoSide;
    private boolean lightModelTwoSideKnown;
    private final float[] materialAmbient = new float[4];
    private final float[] materialDiffuse = new float[4];
    private final float[] materialSpecular = new float[4];
    private final float[] materialEmission = new float[4];
    private float materialShininess = Float.NaN;

    // Texture objects.
    private final Map<OglRenderer.OglTexture, GpuTexture> gpuTextures = new IdentityHashMap<>();
    private final Map<Integer, GpuTexture> gpuTexturesByDojaId = new java.util.HashMap<>();
    private int seedTextureId;

    // Direct-buffer scratch space for client arrays.
    private final Scratch vertexScratch = new Scratch();
    private final Scratch normalScratch = new Scratch();
    private final Scratch colorScratch = new Scratch();
    private final Scratch[] texCoordScratch = {new Scratch(), new Scratch()};
    private final Scratch indexScratch = new Scratch();
    private final Scratch cpuPositionScratch = new Scratch();
    private final Scratch cpuColorScratch = new Scratch();
    private final Scratch quadPositionScratch = new Scratch();
    private final Scratch quadTexCoordScratch = new Scratch();

    // CPU vertex/lighting scratch.
    private float[] cpuPositions = new float[0];
    private byte[] cpuColors = new byte[0];
    private final float[][] cpuTexCoords = {new float[0], new float[0]};
    private int[] seedPixels = new int[0];
    private java.nio.ByteBuffer readbackBytes;
    private java.nio.ByteBuffer seedBytes;
    private final OglRenderer.ClipVector clipScratch = new OglRenderer.ClipVector();
    private final OglRenderer.ClipVector eyeScratch = new OglRenderer.ClipVector();
    private final OglRenderer.ClipVector cpuNormalScratch = new OglRenderer.ClipVector();
    private final float[] matrixScratch = new float[16];

    // Frame-budget counters, reported at debug level every couple of seconds.
    private long statWindowStartNanos = System.nanoTime();
    private long statDrawNanos;
    private long statCpuDrawNanos;
    private long statNativeDrawNanos;
    private long statCpuLoopNanos;
    private long statCpuApplyNanos;
    private long statTextureUploadNanos;
    private int statTextureUploads;
    private long statReadbackNanos;
    private long statClearNanos;
    private long statSeedNanos;
    private int statDraws;
    private int statCpuDraws;
    private int statNativeDraws;
    private int statCpuVertices;
    private int statNativeVertices;
    private int statFallbackDraws;
    private int statVertices;
    private int statFrames;
    private int statSeeds;
    private int statReadbacks;
    private final java.util.Set<String> loggedDrawFallbacks = new java.util.HashSet<>();

    private void noteDrawFallback(String reason) {
        if (!OpenDoJaLog.isDebugEnabled() || loggedDrawFallbacks.size() >= 32
                || !loggedDrawFallbacks.add(reason)) {
            return;
        }
        OpenDoJaLog.debug(AndroidOglHardwareBackend.class,
                "HW draw fallback: " + reason + " size=" + targetWidth + "x" + targetHeight);
    }

    private AndroidOglHardwareBackend(OglRenderer owner) {
        super(owner);
    }

    // ------------------------------------------------------------------
    // Lifecycle and EGL setup
    // ------------------------------------------------------------------

    @Override
    void prepareForSoftwareMutation() {
        if (!ensureUsable()) {
            return;
        }
        if (frameColorValid && frameColorFresh) {
            readbackToSurface();
        }
        // Publish software-side pixels before the caller writes the bitmap.
        owner.host().surface().flushOglFrameToBitmap();
        // The caller is about to mutate the bitmap behind the GPU frame's back.
        // Re-seed from the bitmap before the next hardware draw.
        colorDirty = true;
        frameColorValid = false;
    }

    @Override
    void flush() {
        if (ensureUsable()) {
            GLES10.glFlush();
        }
    }

    @Override
    void flushForReadback() {
        if (!ensureUsable()) {
            return;
        }
        if (frameColorValid && frameColorFresh) {
            readbackToSurface();
        }
    }

    @Override
    void flushForPresentation() {
        flushForReadback();
        if (!ensureUsable()) {
            return;
        }
        // DesktopSurface.present() ends the software depth frame at the same
        // boundaries; keep the GPU depth buffer in sync with that convention.
        frameDepthValid = false;
    }

    @Override
    boolean draw(int mode, int first, int count, OglRenderer.OglIndexSource indexSource) {
        if (!ensureUsable() || count <= 0 || owner.oglState().vertexPointer == null) {
            return false;
        }
        if (!isSupportedMode(mode)) {
            return false;
        }
        OglRenderer.OglState ogl = owner.oglState();
        if (indexSource != null) {
            int available = indexSource.elementCount();
            if (available <= 0) {
                return false;
            }
            count = Math.min(count, available);
        }
        if (ogl.usesExtensionMatrices()) {
            // The Acrodea world/camera extension is not representable with the
            // standard GLES1 matrices; let the software renderer keep it.
            return false;
        }
        boolean cpuPath = ogl.usesMatrixPalette()
                || (ogl.lightingEnabled()
                && ogl.colorMaterialTracksAmbientAndDiffuse());
        if (cpuPath && ogl.lightingEnabled() && ogl.lightModelTwoSide) {
            // CPU lighting computes one color per vertex; GLES1 cannot supply
            // separate front/back colors without color material.
            return false;
        }
        if (!ensureFrameTarget()) {
            return false;
        }
        if (!ensureColorReady()) {
            return false;
        }
        if (ogl.depthTestEnabled && !frameDepthValid) {
            clearDepthBuffer();
        }
        statDraws++;
        statVertices += count;
        long startNanos = System.nanoTime();
        if (cpuPath) {
            boolean handled = drawCpu(mode, first, count, indexSource);
            long elapsed = System.nanoTime() - startNanos;
            statDrawNanos += elapsed;
            statCpuDrawNanos += elapsed;
            if (handled) {
                statCpuDraws++;
                statCpuVertices += count;
            } else {
                statFallbackDraws++;
            }
            logStatsIfDue();
            return handled;
        }
        boolean handled = drawNative(mode, first, count, indexSource);
        long elapsed = System.nanoTime() - startNanos;
        statDrawNanos += elapsed;
        statNativeDrawNanos += elapsed;
        if (handled) {
            statNativeDraws++;
            statNativeVertices += count;
        } else {
            statFallbackDraws++;
        }
        logStatsIfDue();
        return handled;
    }

    @Override
    boolean clear(int mask) {
        if (!ensureUsable()) {
            return false;
        }
        int colorBit = GraphicsOGL.GL_COLOR_BUFFER_BIT;
        int depthBit = GraphicsOGL.GL_DEPTH_BUFFER_BIT;
        boolean clearColor = (mask & colorBit) != 0;
        boolean clearDepth = (mask & depthBit) != 0;
        if (!clearColor && !clearDepth) {
            // Stencil-only clears have no software meaning; ignore them.
            return true;
        }
        if (!ensureFrameTarget()) {
            return false;
        }
        // OglRenderer resets the software depth frame on every glClear. Mirror
        // that lazily: a color-only clear also starts a fresh depth frame whose
        // contents are cleared before the next depth-tested draw.
        frameDepthValid = false;
        if (clearColor) {
            Rectangle clip = scaledClip();
            boolean full = coversWholeTarget(clip);
            if (!full && (!frameColorValid || colorDirty)) {
                if (!seedFromSurface()) {
                    return false;
                }
            }
            applyScissor(clip);
            int color = owner.oglClearColor();
            GLES10.glClearColor(
                    ((color >>> 16) & 0xFF) / 255f,
                    ((color >>> 8) & 0xFF) / 255f,
                    (color & 0xFF) / 255f,
                    ((color >>> 24) & 0xFF) / 255f);
            GLES10.glClear(colorBit);
            frameColorValid = true;
            frameColorFresh = true;
            colorDirty = false;
            presentedAnyFrame = true;
        }
        if (clearDepth) {
            clearDepthBuffer();
        }
        return true;
    }

    @Override
    void endDrawing() {
        if (ensureUsable()) {
            GLES10.glFlush();
        }
    }

    @Override
    void onTextureDeleted(int textureId) {
        GpuTexture texture = gpuTexturesByDojaId.remove(textureId);
        if (texture == null) {
            return;
        }
        gpuTextures.remove(texture.texture);
        for (int i = 0; i < UNIT_COUNT; i++) {
            if (boundTextureIds[i] == texture.glId) {
                boundTextureIds[i] = -1;
            }
        }
        if (ensureUsable()) {
            int[] ids = {texture.glId};
            GLES10.glDeleteTextures(1, ids, 0);
        }
    }

    @Override
    void onPresentedSoftwareOverlay(Rectangle bounds) {
        markSurfaceDirty();
    }

    @Override
    void onSoftwareSurfaceMutation() {
        markSurfaceDirty();
    }

    @Override
    void onHostDelegateRecreated() {
        markSurfaceDirty();
    }

    @Override
    void close() {
        destroyEgl();
        initialized = false;
    }

    @Override
    boolean hasBufferedHardwarePresentation() {
        return presentedAnyFrame;
    }

    private void markSurfaceDirty() {
        if (!initialized || failed) {
            return;
        }
        colorDirty = true;
        frameColorValid = false;
    }

    private boolean ensureUsable() {
        if (failed) {
            return false;
        }
        if (!initialized && !initializeEgl()) {
            return false;
        }
        // Several backends (display, offscreen images) can share one thread. EGL
        // allows only one current context per thread, so another backend may have
        // replaced ours since the last call. Always verify, not just the thread.
        if (ownerThread != Thread.currentThread() || !isCurrentContext()) {
            if (eglDisplay == EGL14.EGL_NO_DISPLAY
                    || !EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                markFailed("eglMakeCurrent failed on " + Thread.currentThread().getName());
                return false;
            }
            ownerThread = Thread.currentThread();
        }
        return true;
    }

    private boolean isCurrentContext() {
        try {
            return eglContext != EGL14.EGL_NO_CONTEXT
                    && eglContext.equals(EGL14.eglGetCurrentContext());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean initializeEgl() {
        try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
                markFailed("no EGL display");
                return false;
            }
            int[] version = new int[2];
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
                markFailed("eglInitialize failed");
                return false;
            }
            if (!EGL14.eglBindAPI(EGL_OPENGL_ES_API)) {
                markFailed("eglBindAPI(GLES1) failed");
                return false;
            }
            DesktopSurface surface = owner.host().surface();
            surface.ensureOglRenderTarget();
            targetWidth = surface.oglRenderWidth();
            targetHeight = surface.oglRenderHeight();
            if (targetWidth <= 0 || targetHeight <= 0) {
                markFailed("invalid OGL render target " + targetWidth + "x" + targetHeight);
                return false;
            }
            if (!createConfigAndContext()) {
                return false;
            }
            if (!createPbuffer(targetWidth, targetHeight)) {
                return false;
            }
            ownerThread = Thread.currentThread();
            String extensions = GLES10.glGetString(GLES10.GL_EXTENSIONS);
            npotSupported = extensions != null && extensions.contains("GL_OES_texture_npot");
            int[] maxSize = new int[1];
            GLES10.glGetIntegerv(GLES10.GL_MAX_TEXTURE_SIZE, maxSize, 0);
            maxTextureSize = maxSize[0] > 0 ? maxSize[0] : 2048;
            initialized = true;
            OpenDoJaLog.info(AndroidOglHardwareBackend.class,
                    "Hardware GLES1 renderer active "
                            + targetWidth + "x" + targetHeight
                            + " vendor=" + GLES10.glGetString(GLES10.GL_VENDOR)
                            + " renderer=" + GLES10.glGetString(GLES10.GL_RENDERER)
                            + " version=" + GLES10.glGetString(GLES10.GL_VERSION)
                            + " npot=" + npotSupported);
            resetGlCaches();
            return true;
        } catch (Throwable throwable) {
            markFailed("EGL setup failed: " + OpenDoJaLog.throwableToString(throwable));
            return false;
        }
    }

    private boolean createConfigAndContext() {
        int[][] attempts = {
                {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                        EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                        EGL14.EGL_DEPTH_SIZE, 24},
                {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                        EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                        EGL14.EGL_DEPTH_SIZE, 16},
                {EGL14.EGL_RED_SIZE, 5, EGL14.EGL_GREEN_SIZE, 6,
                        EGL14.EGL_BLUE_SIZE, 5, EGL14.EGL_ALPHA_SIZE, 0,
                        EGL14.EGL_DEPTH_SIZE, 16},
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        for (int[] extra : attempts) {
            int[] attributes = new int[extra.length + 5];
            attributes[0] = EGL14.EGL_RENDERABLE_TYPE;
            attributes[1] = EGL_OPENGL_ES_BIT;
            attributes[2] = EGL14.EGL_SURFACE_TYPE;
            attributes[3] = EGL14.EGL_PBUFFER_BIT;
            System.arraycopy(extra, 0, attributes, 4, extra.length);
            attributes[attributes.length - 1] = EGL14.EGL_NONE;
            if (!EGL14.eglChooseConfig(eglDisplay, attributes, 0, configs, 0, 1, count, 0)
                    || count[0] <= 0) {
                continue;
            }
            eglConfig = configs[0];
            int[] contextAttributes = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 1, EGL14.EGL_NONE};
            eglContext = EGL14.eglCreateContext(
                    eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttributes, 0);
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                return true;
            }
        }
        markFailed("no EGL config/GLES1 context");
        return false;
    }

    private boolean createPbuffer(int width, int height) {
        destroyPbuffer();
        int[] attributes = {
                EGL14.EGL_WIDTH, width,
                EGL14.EGL_HEIGHT, height,
                EGL14.EGL_NONE
        };
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, attributes, 0);
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            markFailed("eglCreatePbufferSurface(" + width + "x" + height + ") failed");
            return false;
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            markFailed("eglMakeCurrent failed");
            return false;
        }
        return true;
    }

    private void destroyPbuffer() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglSurface != EGL14.EGL_NO_SURFACE) {
            releaseCurrentContext();
            EGL14.eglDestroySurface(eglDisplay, eglSurface);
        }
        eglSurface = EGL14.EGL_NO_SURFACE;
    }

    private void destroyEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            releaseCurrentContext();
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface);
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext);
            }
            EGL14.eglTerminate(eglDisplay);
        }
        eglSurface = EGL14.EGL_NO_SURFACE;
        eglContext = EGL14.EGL_NO_CONTEXT;
        eglDisplay = EGL14.EGL_NO_DISPLAY;
    }

    /** Unbinds only our own context; another backend on this thread may be current. */
    private void releaseCurrentContext() {
        try {
            if (eglContext != EGL14.EGL_NO_CONTEXT
                    && eglContext.equals(EGL14.eglGetCurrentContext())) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            }
        } catch (Throwable ignored) {
        }
    }

    private void markFailed(String reason) {
        if (!failed) {
            OpenDoJaLog.warn(AndroidOglHardwareBackend.class,
                    "Hardware GLES1 disabled, falling back to software: " + reason);
        }
        failed = true;
        initialized = false;
        destroyEgl();
    }

    private boolean ensureFrameTarget() {
        if (!ensureUsable()) {
            return false;
        }
        DesktopSurface surface = owner.host().surface();
        surface.ensureOglRenderTarget();
        int width = surface.oglRenderWidth();
        int height = surface.oglRenderHeight();
        if (width <= 0 || height <= 0) {
            return false;
        }
        if (width == targetWidth && height == targetHeight) {
            return true;
        }
        targetWidth = width;
        targetHeight = height;
        if (!createPbuffer(width, height)) {
            return false;
        }
        gpuTextures.clear();
        gpuTexturesByDojaId.clear();
        seedTextureId = 0;
        resetGlCaches();
        frameColorValid = false;
        frameColorFresh = false;
        frameDepthValid = false;
        colorDirty = true;
        return true;
    }

    private void resetGlCaches() {
        for (int i = 0; i < CAP_COUNT; i++) {
            capKnown[i] = false;
        }
        projectionLoaded = false;
        modelViewLoaded = false;
        activeTextureUnit = -1;
        boundTextureIds[0] = -1;
        boundTextureIds[1] = -1;
        textureMatrixLoaded[0] = false;
        textureMatrixLoaded[1] = false;
        textureEnvCaches[0].known = false;
        textureEnvCaches[1].known = false;
        shadeModel = -1;
        depthMaskKnown = false;
        blendSrc = -1;
        blendDst = -1;
        depthFunc = -1;
        alphaFunc = -1;
        alphaRef = Float.NaN;
        cullFace = -1;
        frontFace = -1;
        currentColor = Integer.MIN_VALUE;
        currentNormalKnown = false;
        viewportX = Integer.MIN_VALUE;
        depthRangeNear = Float.NaN;
        depthRangeFar = Float.NaN;
        scissorKnown = false;
        clientTextureUnit = -1;
        for (int i = 0; i < MAX_LIGHTS; i++) {
            lightDataKnown[i] = false;
            lightEnabledKnown[i] = false;
        }
        lightModelAmbientKnown = false;
        lightModelTwoSideKnown = false;
        materialShininess = Float.NaN;
    }

    private boolean isSupportedMode(int mode) {
        return switch (mode) {
            case GraphicsOGL.GL_POINTS,
                    GraphicsOGL.GL_LINES,
                    GraphicsOGL.GL_LINE_LOOP,
                    GraphicsOGL.GL_LINE_STRIP,
                    GraphicsOGL.GL_TRIANGLES,
                    GraphicsOGL.GL_TRIANGLE_STRIP,
                    GraphicsOGL.GL_TRIANGLE_FAN -> true;
            default -> false;
        };
    }

    // ------------------------------------------------------------------
    // Framebuffer seeding, clearing and readback
    // ------------------------------------------------------------------

    private boolean ensureColorReady() {
        if (frameColorValid && !colorDirty) {
            return true;
        }
        long startNanos = System.nanoTime();
        boolean ready = seedFromSurface();
        if (ready) {
            statSeedNanos += System.nanoTime() - startNanos;
            statSeeds++;
        }
        return ready;
    }

    private boolean seedFromSurface() {
        DesktopSurface surface = owner.host().surface();
        // Software draws (2D and fallback triangles) live in the side buffer until
        // this publish point. Make the bitmap authoritative before uploading it.
        surface.flushOglFrameToBitmap();
        opendoja.compat.awt.image.BufferedImage image = surface.image();
        int surfaceWidth = image.getWidth();
        int surfaceHeight = image.getHeight();
        if (surfaceWidth <= 0 || surfaceHeight <= 0) {
            return false;
        }
        int pixelCount = surfaceWidth * surfaceHeight;
        if (seedPixels.length != pixelCount) {
            seedPixels = new int[pixelCount];
        }
        image.getBitmap().getPixels(seedPixels, 0, surfaceWidth, 0, 0, surfaceWidth, surfaceHeight);

        int width = targetWidth;
        int height = targetHeight;
        int bytesRequired = width * height * 4;
        if (seedBytes == null || seedBytes.capacity() < bytesRequired) {
            seedBytes = java.nio.ByteBuffer.allocateDirect(bytesRequired)
                    .order(java.nio.ByteOrder.nativeOrder());
        }
        seedBytes.clear();
        // Pack four bytes per pixel through an int view: writing each channel
        // separately costs noticeably more than a few bit operations.
        java.nio.IntBuffer packed = seedBytes.asIntBuffer();
        for (int y = 0; y < height; y++) {
            int sourceRow = (int) ((long) y * surfaceHeight / height) * surfaceWidth;
            for (int x = 0; x < width; x++) {
                int sourceColumn = (int) ((long) x * surfaceWidth / width);
                int argb = seedPixels[sourceRow + sourceColumn];
                // GL wants RGBA byte order: A<<24|B<<16|G<<8|R on little-endian.
                packed.put((argb & 0xFF00FF00)
                        | ((argb & 0x00FF0000) >>> 16)
                        | ((argb & 0x000000FF) << 16));
            }
        }
        seedBytes.position(0);
        seedBytes.limit(bytesRequired);

        if (seedTextureId == 0) {
            int[] ids = new int[1];
            GLES10.glGenTextures(1, ids, 0);
            seedTextureId = ids[0];
        }
        GLES10.glPixelStorei(GLES10.GL_UNPACK_ALIGNMENT, 1);
        GLES10.glBindTexture(GLES10.GL_TEXTURE_2D, seedTextureId);
        GLES11.glTexParameteri(GLES10.GL_TEXTURE_2D, GraphicsOGL.GL_TEXTURE_MIN_FILTER, GraphicsOGL.GL_NEAREST);
        GLES11.glTexParameteri(GLES10.GL_TEXTURE_2D, GraphicsOGL.GL_TEXTURE_MAG_FILTER, GraphicsOGL.GL_NEAREST);
        GLES11.glTexParameteri(GLES10.GL_TEXTURE_2D, GraphicsOGL.GL_TEXTURE_WRAP_S, GraphicsOGL.GL_CLAMP_TO_EDGE);
        GLES11.glTexParameteri(GLES10.GL_TEXTURE_2D, GraphicsOGL.GL_TEXTURE_WRAP_T, GraphicsOGL.GL_CLAMP_TO_EDGE);
        GLES10.glTexImage2D(GLES10.GL_TEXTURE_2D, 0, GraphicsOGL.GL_RGBA,
                width, height, 0, GraphicsOGL.GL_RGBA, GLES10.GL_UNSIGNED_BYTE, seedBytes);

        drawSeedQuad(width, height);

        frameColorValid = true;
        frameColorFresh = false;
        colorDirty = false;
        resetGlCaches();
        return true;
    }

    private void drawSeedQuad(int width, int height) {
        for (int i = CAP_BLEND; i <= CAP_TEXTURE1; i++) {
            capKnown[i] = false;
        }
        setCap(CAP_BLEND, false);
        setCap(CAP_DEPTH, false);
        setCap(CAP_LIGHTING, false);
        setCap(CAP_ALPHA, false);
        setCap(CAP_CULL, false);
        setCap(CAP_TEXTURE0, true);
        setCap(CAP_TEXTURE1, false);
        GLES10.glDepthMask(false);
        depthMaskState = false;
        depthMaskKnown = true;

        GLES10.glMatrixMode(GraphicsOGL.GL_PROJECTION);
        GLES10.glLoadMatrixf(IDENTITY, 0);
        projectionLoaded = false;
        GLES10.glMatrixMode(GraphicsOGL.GL_MODELVIEW);
        GLES10.glLoadMatrixf(IDENTITY, 0);
        modelViewLoaded = false;
        GLES10.glViewport(0, 0, width, height);
        viewportX = 0;
        viewportY = 0;
        viewportWidth = width;
        viewportHeight = height;
        GLES10.glDisable(GLES10.GL_SCISSOR_TEST);
        scissorKnown = false;

        applyActiveTexture(0);
        boundTextureIds[0] = seedTextureId;
        GLES10.glBindTexture(GLES10.GL_TEXTURE_2D, seedTextureId);
        GLES11.glTexEnvi(GraphicsOGL.GL_TEXTURE_ENV, GraphicsOGL.GL_TEXTURE_ENV_MODE, GraphicsOGL.GL_REPLACE);
        textureEnvCaches[0].known = false;
        GLES10.glColor4f(1f, 1f, 1f, 1f);
        currentColor = 0xFFFFFFFF;

        float[] positions = {
                -1f, -1f,
                1f, -1f,
                -1f, 1f,
                1f, 1f
        };
        float[] texCoords = {
                0f, 1f,
                1f, 1f,
                0f, 0f,
                1f, 0f
        };
        quadPositionScratch.putFloats(positions, 0, positions.length);
        quadTexCoordScratch.putFloats(texCoords, 0, texCoords.length);

        GLES10.glEnableClientState(GraphicsOGL.GL_VERTEX_ARRAY);
        GLES10.glVertexPointer(2, GraphicsOGL.GL_FLOAT, 0, quadPositionScratch.bytes);
        GLES11.glClientActiveTexture(GraphicsOGL.GL_TEXTURE0 + 0);
        clientTextureUnit = 0;
        GLES10.glEnableClientState(GraphicsOGL.GL_TEXTURE_COORD_ARRAY);
        GLES10.glTexCoordPointer(2, GraphicsOGL.GL_FLOAT, 0, quadTexCoordScratch.bytes);
        GLES10.glDisableClientState(GraphicsOGL.GL_COLOR_ARRAY);
        GLES10.glDisableClientState(GraphicsOGL.GL_NORMAL_ARRAY);
        GLES10.glDrawArrays(GraphicsOGL.GL_TRIANGLE_STRIP, 0, 4);
    }

    private void clearDepthBuffer() {
        if (!ensureUsable()) {
            return;
        }
        GLES10.glDisable(GLES10.GL_SCISSOR_TEST);
        scissorKnown = false;
        boolean depthMask = depthMaskKnown ? depthMaskState : true;
        GLES10.glDepthMask(true);
        GLES10.glClearDepthf(1f);
        GLES10.glClear(GraphicsOGL.GL_DEPTH_BUFFER_BIT);
        GLES10.glDepthMask(depthMask);
        depthMaskState = depthMask;
        depthMaskKnown = true;
        frameDepthValid = true;
    }

    private void readbackToSurface() {
        long startNanos = System.nanoTime();
        DesktopSurface surface = owner.host().surface();
        if (!ensureFrameTarget()) {
            return;
        }
        int width = surface.oglRenderWidth();
        int height = surface.oglRenderHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        int required = width * height * 4;
        if (readbackBytes == null || readbackBytes.capacity() < required) {
            readbackBytes = java.nio.ByteBuffer.allocateDirect(required)
                    .order(java.nio.ByteOrder.nativeOrder());
        }
        readbackBytes.clear();
        readbackBytes.limit(required);
        GLES10.glPixelStorei(GLES10.GL_PACK_ALIGNMENT, 1);
        GLES10.glReadPixels(0, 0, width, height,
                GraphicsOGL.GL_RGBA, GLES10.GL_UNSIGNED_BYTE, readbackBytes);
        int[] pixels = surface.acquireOglFramePixelsForOverwrite();
        if (pixels == null || pixels.length < width * height) {
            return;
        }
        readbackBytes.position(0);
        java.nio.IntBuffer packed = readbackBytes.asIntBuffer();
        int targetRow = 0;
        for (int y = height - 1; y >= 0; y--) {
            int source = y * width;
            int destination = targetRow * width;
            for (int x = 0; x < width; x++) {
                int value = packed.get(source + x);
                // Source is GL RGBA; the surface wants ARGB.
                pixels[destination + x] = (value & 0xFF00FF00)
                        | ((value & 0x00FF0000) >>> 16)
                        | ((value & 0x000000FF) << 16);
            }
            targetRow++;
        }
        surface.completeOglFrameReadback();
        frameColorValid = true;
        frameColorFresh = false;
        presentedAnyFrame = true;
        statReadbackNanos += System.nanoTime() - startNanos;
        statFrames++;
        statReadbacks++;
        logStatsIfDue();
    }

    private void logStatsIfDue() {
        long now = System.nanoTime();
        long windowNanos = now - statWindowStartNanos;
        if (windowNanos < 2_000_000_000L) {
            return;
        }
        if (OpenDoJaLog.isDebugEnabled()) {
            double seconds = windowNanos / 1_000_000_000.0;
            double frames = Math.max(1, statFrames);
            OpenDoJaLog.debug(AndroidOglHardwareBackend.class, String.format(
                    java.util.Locale.US,
                    "HW stats %.1fs size=%dx%d thread=%s fps=%.1f draws=%d"
                            + " cpuDraws=%d nativeDraws=%d fallbacks=%d"
                            + " cpuVerts=%d nativeVerts=%d"
                            + " drawMs/frame=%.1f cpuMs/frame=%.1f nativeMs/frame=%.1f"
                            + " cpuLoopMs=%.1f cpuApplyMs=%.1f texUploadMs=%.1f texUploads=%.2f"
                            + " seedMs/frame=%.1f readbackMs/frame=%.1f"
                            + " seeds/frame=%.2f readbacks/frame=%.2f",
                    seconds, targetWidth, targetHeight, Thread.currentThread().getName(),
                    statFrames / seconds, statDraws, statCpuDraws, statNativeDraws,
                    statFallbackDraws, statCpuVertices, statNativeVertices,
                    statDrawNanos / 1_000_000.0 / frames,
                    statCpuDrawNanos / 1_000_000.0 / frames,
                    statNativeDrawNanos / 1_000_000.0 / frames,
                    statCpuLoopNanos / 1_000_000.0 / frames,
                    statCpuApplyNanos / 1_000_000.0 / frames,
                    statTextureUploadNanos / 1_000_000.0 / frames,
                    statTextureUploads / frames,
                    statSeedNanos / 1_000_000.0 / frames,
                    statReadbackNanos / 1_000_000.0 / frames,
                    statSeeds / frames,
                    statReadbacks / frames));
        }
        statWindowStartNanos = now;
        statDrawNanos = 0L;
        statCpuDrawNanos = 0L;
        statNativeDrawNanos = 0L;
        statCpuLoopNanos = 0L;
        statCpuApplyNanos = 0L;
        statTextureUploadNanos = 0L;
        statTextureUploads = 0;
        statReadbackNanos = 0L;
        statClearNanos = 0L;
        statSeedNanos = 0L;
        statDraws = 0;
        statCpuDraws = 0;
        statNativeDraws = 0;
        statCpuVertices = 0;
        statNativeVertices = 0;
        statFallbackDraws = 0;
        statVertices = 0;
        statFrames = 0;
        statSeeds = 0;
        statReadbacks = 0;
    }

    // ------------------------------------------------------------------
    // Native fixed-function draw path
    // ------------------------------------------------------------------

    private boolean drawNative(int mode, int first, int count,
                               OglRenderer.OglIndexSource indexSource) {
        OglRenderer.OglState ogl = owner.oglState();
        int maxIndex = first + count - 1;
        if (indexSource != null) {
            maxIndex = 0;
            for (int i = 0; i < count; i++) {
                int index = indexSource.indexAt(i);
                if (index < 0) {
                    return false;
                }
                if (index > maxIndex) {
                    maxIndex = index;
                }
            }
        }
        if (maxIndex < 0) {
            return false;
        }
        OglRenderer.OglPointer vertexPointer = ogl.vertexPointer;
        if (vertexPointer == null || !copyPointer(vertexPointer, maxIndex, vertexScratch)) {
            return false;
        }
        OglRenderer.OglPointer normalPointer = ogl.normalArrayEnabled ? ogl.normalPointer : null;
        if (normalPointer != null && !copyPointer(normalPointer, maxIndex, normalScratch)) {
            return false;
        }
        OglRenderer.OglPointer colorPointer = ogl.colorArrayEnabled ? ogl.colorPointer : null;
        if (colorPointer != null && !copyPointer(colorPointer, maxIndex, colorScratch)) {
            return false;
        }
        for (int unit = 0; unit < UNIT_COUNT; unit++) {
            OglRenderer.OglTextureUnit textureUnit = ogl.textureUnit(unit);
            if (textureUnit != null && textureUnit.texCoordArrayEnabled
                    && textureUnit.texCoordPointer != null) {
                if (!copyPointer(textureUnit.texCoordPointer, maxIndex, texCoordScratch[unit])) {
                    return false;
                }
            }
        }
        if (indexSource != null && !copyIndices(indexSource, count)) {
            return false;
        }

        applyState(false);
        if (textureStateUnrepresentable) {
            return false;
        }
        applyNativeClientArrays(ogl, vertexPointer, normalPointer, colorPointer, indexSource);

        if (indexSource != null) {
            GLES10.glDrawElements(mode, count, GraphicsOGL.GL_UNSIGNED_SHORT, indexScratch.bytes);
        } else {
            GLES10.glDrawArrays(mode, first, count);
        }
        frameColorValid = true;
        frameColorFresh = true;
        colorDirty = false;
        presentedAnyFrame = true;
        return true;
    }

    private void applyNativeClientArrays(OglRenderer.OglState ogl,
                                         OglRenderer.OglPointer vertexPointer,
                                         OglRenderer.OglPointer normalPointer,
                                         OglRenderer.OglPointer colorPointer,
                                         OglRenderer.OglIndexSource indexSource) {
        GLES11.glBindBuffer(GLES11.GL_ARRAY_BUFFER, 0);
        GLES11.glBindBuffer(GLES11.GL_ELEMENT_ARRAY_BUFFER, 0);

        GLES10.glEnableClientState(GraphicsOGL.GL_VERTEX_ARRAY);
        GLES10.glVertexPointer(vertexPointer.size(), vertexPointer.type(),
                vertexPointer.stride(), vertexScratch.bytes);

        if (normalPointer != null) {
            GLES10.glEnableClientState(GraphicsOGL.GL_NORMAL_ARRAY);
            GLES10.glNormalPointer(normalPointer.type(), normalPointer.stride(), normalScratch.bytes);
        } else {
            GLES10.glDisableClientState(GraphicsOGL.GL_NORMAL_ARRAY);
            setCurrentNormal(ogl.currentNormal[0], ogl.currentNormal[1], ogl.currentNormal[2]);
        }

        if (colorPointer != null) {
            GLES10.glEnableClientState(GraphicsOGL.GL_COLOR_ARRAY);
            GLES10.glColorPointer(colorPointer.size(), colorPointer.type(),
                    colorPointer.stride(), colorScratch.bytes);
        } else {
            GLES10.glDisableClientState(GraphicsOGL.GL_COLOR_ARRAY);
        }

        for (int unit = 0; unit < UNIT_COUNT; unit++) {
            OglRenderer.OglTextureUnit textureUnit = ogl.textureUnit(unit);
            applyClientTextureUnit(unit, textureUnit);
        }
    }

    private void applyClientTextureUnit(int unit, OglRenderer.OglTextureUnit textureUnit) {
        GLES11.glClientActiveTexture(GraphicsOGL.GL_TEXTURE0 + unit);
        clientTextureUnit = unit;
        if (textureUnit == null) {
            GLES10.glDisableClientState(GraphicsOGL.GL_TEXTURE_COORD_ARRAY);
            return;
        }
        if (textureUnit.texCoordArrayEnabled && textureUnit.texCoordPointer != null) {
            OglRenderer.OglPointer pointer = textureUnit.texCoordPointer;
            GLES10.glEnableClientState(GraphicsOGL.GL_TEXTURE_COORD_ARRAY);
            GLES10.glTexCoordPointer(pointer.size(), pointer.type(),
                    pointer.stride(), texCoordScratch[unit].bytes);
        } else {
            GLES10.glDisableClientState(GraphicsOGL.GL_TEXTURE_COORD_ARRAY);
            GLES11.glMultiTexCoord4f(GraphicsOGL.GL_TEXTURE0 + unit,
                    textureUnit.currentS, textureUnit.currentT,
                    textureUnit.currentR, textureUnit.currentQ);
        }
    }

    private boolean copyIndices(OglRenderer.OglIndexSource source, int count) {
        short[] destination = new short[count];
        if (source.directIndices() != null) {
            if (source.directOffset() < 0
                    || source.directOffset() + count > source.directIndices().length) {
                return false;
            }
            System.arraycopy(source.directIndices(), source.directOffset(), destination, 0, count);
        } else if (source.bufferObject() != null) {
            byte[] data = source.bufferObject().data();
            int offset = source.byteOffset();
            if (offset < 0 || count < 0 || offset + count * 2 > data.length) {
                return false;
            }
            for (int i = 0; i < count; i++) {
                int sourceOffset = offset + i * 2;
                destination[i] = (short) ((data[sourceOffset] & 0xFF)
                        | ((data[sourceOffset + 1] & 0xFF) << 8));
            }
        } else {
            return false;
        }
        indexScratch.putShorts(destination, 0, count);
        return true;
    }

    // ------------------------------------------------------------------
    // CPU skinning / lighting draw path
    // ------------------------------------------------------------------

    private boolean drawCpu(int mode, int first, int count,
                            OglRenderer.OglIndexSource indexSource) {
        OglRenderer.OglState ogl = owner.oglState();
        int vertexCount = count;
        if (cpuPositions.length < vertexCount * 3) {
            cpuPositions = new float[vertexCount * 3];
            cpuColors = new byte[vertexCount * 4];
            cpuTexCoords[0] = new float[vertexCount * 4];
            cpuTexCoords[1] = new float[vertexCount * 4];
        } else if (cpuColors.length < vertexCount * 4) {
            cpuColors = new byte[vertexCount * 4];
            cpuTexCoords[0] = new float[vertexCount * 4];
            cpuTexCoords[1] = new float[vertexCount * 4];
        }
        boolean lighting = ogl.lightingEnabled();
        boolean colorArray = ogl.colorArrayEnabled && ogl.colorPointer != null;
        boolean[] unitHasTexCoords = new boolean[UNIT_COUNT];
        long loopStartNanos = System.nanoTime();

        for (int i = 0; i < vertexCount; i++) {
            int vertexIndex = indexSource == null ? first + i : indexSource.indexAt(i);
            if (vertexIndex < 0) {
                noteDrawFallback("cpu negative vertex index=" + vertexIndex + " i=" + i + " count=" + count);
                return false;
            }
            float x = owner.readArrayComponent(ogl.vertexPointer, vertexIndex, 0);
            int positionSize = Math.max(1, ogl.vertexPointer.size());
            float y = positionSize > 1 ? owner.readArrayComponent(ogl.vertexPointer, vertexIndex, 1) : 0f;
            float z = positionSize > 2 ? owner.readArrayComponent(ogl.vertexPointer, vertexIndex, 2) : 0f;
            if (ogl.lastError != GraphicsOGL.GL_NO_ERROR) {
                noteDrawFallback("cpu vertex read error=0x" + Integer.toHexString(ogl.lastError)
                        + " vertex=" + vertexIndex + " type=" + ogl.vertexPointer.type()
                        + " size=" + ogl.vertexPointer.size());
                return false;
            }
            owner.transformVertex(clipScratch, eyeScratch, x, y, z, vertexIndex, false);
            int positionOffset = i * 3;
            cpuPositions[positionOffset] = eyeScratch.x;
            cpuPositions[positionOffset + 1] = eyeScratch.y;
            cpuPositions[positionOffset + 2] = eyeScratch.z;

            int sourceColor = readPrimaryColor(vertexIndex);
            if (sourceColor == Integer.MIN_VALUE) {
                noteDrawFallback("cpu primary color error=0x" + Integer.toHexString(ogl.lastError)
                        + " vertex=" + vertexIndex + " colorArray=" + ogl.colorArrayEnabled
                        + (ogl.colorPointer != null ? " colorType=" + ogl.colorPointer.type()
                        + " colorSize=" + ogl.colorPointer.size() : " colorPointer=null"));
                return false;
            }
            int outColor = sourceColor;
            if (lighting) {
                owner.resolveEyeNormal(cpuNormalScratch, vertexIndex, false);
                if (ogl.lastError != GraphicsOGL.GL_NO_ERROR) {
                    noteDrawFallback("cpu normal read error=0x" + Integer.toHexString(ogl.lastError)
                            + " vertex=" + vertexIndex
                            + " normalArray=" + ogl.normalArrayEnabled);
                    return false;
                }
                outColor = litColor(sourceColor, cpuNormalScratch);
                if (outColor == Integer.MIN_VALUE) {
                    noteDrawFallback("cpu lighting error=0x" + Integer.toHexString(ogl.lastError)
                            + " vertex=" + vertexIndex);
                    return false;
                }
            }
            int colorOffset = i * 4;
            cpuColors[colorOffset] = (byte) ((outColor >>> 16) & 0xFF);
            cpuColors[colorOffset + 1] = (byte) ((outColor >>> 8) & 0xFF);
            cpuColors[colorOffset + 2] = (byte) (outColor & 0xFF);
            cpuColors[colorOffset + 3] = (byte) ((outColor >>> 24) & 0xFF);

            for (int unit = 0; unit < UNIT_COUNT; unit++) {
                OglRenderer.OglTextureUnit textureUnit = ogl.textureUnit(unit);
                if (textureUnit == null) {
                    continue;
                }
                float s = textureUnit.currentS;
                float t = textureUnit.currentT;
                float r = textureUnit.currentR;
                float q = textureUnit.currentQ;
                OglRenderer.OglPointer pointer = textureUnit.texCoordArrayEnabled
                        ? textureUnit.texCoordPointer : null;
                if (pointer != null) {
                    unitHasTexCoords[unit] = true;
                    int size = Math.max(1, pointer.size());
                    s = owner.readArrayComponent(pointer, vertexIndex, 0);
                    t = size > 1 ? owner.readArrayComponent(pointer, vertexIndex, 1) : 0f;
                    r = size > 2 ? owner.readArrayComponent(pointer, vertexIndex, 2) : 0f;
                    q = size > 3 ? owner.readArrayComponent(pointer, vertexIndex, 3) : 1f;
                    if (ogl.lastError != GraphicsOGL.GL_NO_ERROR) {
                        noteDrawFallback("cpu texcoord read error=0x"
                                + Integer.toHexString(ogl.lastError) + " unit=" + unit
                                + " vertex=" + vertexIndex + " size=" + size
                                + " type=" + pointer.type());
                        return false;
                    }
                }
                int texOffset = i * 4;
                cpuTexCoords[unit][texOffset] = s;
                cpuTexCoords[unit][texOffset + 1] = t;
                cpuTexCoords[unit][texOffset + 2] = r;
                cpuTexCoords[unit][texOffset + 3] = q;
            }
        }

        cpuPositionScratch.putFloats(cpuPositions, 0, vertexCount * 3);
        cpuColorScratch.putBytes(cpuColors, 0, vertexCount * 4);
        long loopNanos = System.nanoTime() - loopStartNanos;
        statCpuLoopNanos += loopNanos;
        long applyStartNanos = System.nanoTime();
        for (int unit = 0; unit < UNIT_COUNT; unit++) {
            if (unitHasTexCoords[unit]) {
                texCoordScratch[unit].putFloats(cpuTexCoords[unit], 0, vertexCount * 4);
            }
        }

        applyState(true);
        if (textureStateUnrepresentable) {
            return false;
        }
        GLES11.glBindBuffer(GLES11.GL_ARRAY_BUFFER, 0);
        GLES11.glBindBuffer(GLES11.GL_ELEMENT_ARRAY_BUFFER, 0);
        GLES10.glEnableClientState(GraphicsOGL.GL_VERTEX_ARRAY);
        GLES10.glVertexPointer(3, GraphicsOGL.GL_FLOAT, 0, cpuPositionScratch.bytes);
        GLES10.glDisableClientState(GraphicsOGL.GL_NORMAL_ARRAY);
        GLES10.glEnableClientState(GraphicsOGL.GL_COLOR_ARRAY);
        GLES10.glColorPointer(4, GraphicsOGL.GL_UNSIGNED_BYTE, 0, cpuColorScratch.bytes);
        for (int unit = 0; unit < UNIT_COUNT; unit++) {
            OglRenderer.OglTextureUnit textureUnit = ogl.textureUnit(unit);
            GLES11.glClientActiveTexture(GraphicsOGL.GL_TEXTURE0 + unit);
            clientTextureUnit = unit;
            if (textureUnit != null && unitHasTexCoords[unit]) {
                GLES10.glEnableClientState(GraphicsOGL.GL_TEXTURE_COORD_ARRAY);
                GLES10.glTexCoordPointer(4, GraphicsOGL.GL_FLOAT, 0, texCoordScratch[unit].bytes);
            } else if (textureUnit != null) {
                GLES10.glDisableClientState(GraphicsOGL.GL_TEXTURE_COORD_ARRAY);
                GLES11.glMultiTexCoord4f(GraphicsOGL.GL_TEXTURE0 + unit,
                        textureUnit.currentS, textureUnit.currentT,
                        textureUnit.currentR, textureUnit.currentQ);
            } else {
                GLES10.glDisableClientState(GraphicsOGL.GL_TEXTURE_COORD_ARRAY);
            }
        }
        GLES10.glDrawArrays(mode, 0, vertexCount);
        statCpuApplyNanos += System.nanoTime() - applyStartNanos;
        frameColorValid = true;
        frameColorFresh = true;
        colorDirty = false;
        presentedAnyFrame = true;
        return true;
    }

    private int readPrimaryColor(int vertexIndex) {
        OglRenderer.OglState ogl = owner.oglState();
        if (!ogl.colorArrayEnabled || ogl.colorPointer == null) {
            return ogl.color;
        }
        OglRenderer.OglPointer pointer = ogl.colorPointer;
        int size = pointer.size();
        int red;
        int green;
        int blue;
        int alpha;
        switch (pointer.type()) {
            case GraphicsOGL.GL_UNSIGNED_BYTE -> {
                red = size > 0 ? owner.readUnsignedByteComponent(pointer, vertexIndex, 0) : 255;
                green = size > 1 ? owner.readUnsignedByteComponent(pointer, vertexIndex, 1) : red;
                blue = size > 2 ? owner.readUnsignedByteComponent(pointer, vertexIndex, 2) : red;
                alpha = size > 3 ? owner.readUnsignedByteComponent(pointer, vertexIndex, 3) : 255;
            }
            case GraphicsOGL.GL_FLOAT -> {
                red = size > 0 ? floatColorChannel(owner.readArrayComponent(pointer, vertexIndex, 0)) : 255;
                green = size > 1 ? floatColorChannel(owner.readArrayComponent(pointer, vertexIndex, 1)) : red;
                blue = size > 2 ? floatColorChannel(owner.readArrayComponent(pointer, vertexIndex, 2)) : red;
                alpha = size > 3 ? floatColorChannel(owner.readArrayComponent(pointer, vertexIndex, 3)) : 255;
            }
            default -> {
                ogl.lastError = GraphicsOGL.GL_INVALID_ENUM;
                return Integer.MIN_VALUE;
            }
        }
        if (ogl.lastError != GraphicsOGL.GL_NO_ERROR) {
            return Integer.MIN_VALUE;
        }
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private static int floatColorChannel(float value) {
        if (!Float.isFinite(value)) {
            return 0;
        }
        float scaled = value * 255f;
        if (scaled <= 0f) {
            return 0;
        }
        if (scaled >= 255f) {
            return 255;
        }
        return (int) (scaled + 0.5f);
    }

    private int litColor(int sourceColor, OglRenderer.ClipVector normal) {
        OglRenderer.OglState ogl = owner.oglState();
        float sourceRed = OglRenderer.packedComponent(sourceColor, 0);
        float sourceGreen = OglRenderer.packedComponent(sourceColor, 1);
        float sourceBlue = OglRenderer.packedComponent(sourceColor, 2);
        float sourceAlpha = OglRenderer.packedComponent(sourceColor, 3);
        boolean colorMaterial = ogl.colorMaterialTracksAmbientAndDiffuse();
        float ambientRed = colorMaterial ? sourceRed : ogl.materialAmbient[0];
        float ambientGreen = colorMaterial ? sourceGreen : ogl.materialAmbient[1];
        float ambientBlue = colorMaterial ? sourceBlue : ogl.materialAmbient[2];
        float diffuseRed = colorMaterial ? sourceRed : ogl.materialDiffuse[0];
        float diffuseGreen = colorMaterial ? sourceGreen : ogl.materialDiffuse[1];
        float diffuseBlue = colorMaterial ? sourceBlue : ogl.materialDiffuse[2];
        float diffuseAlpha = colorMaterial ? sourceAlpha : ogl.materialDiffuse[3];
        return owner.computeLightingColor(eyeScratch, normal.x, normal.y, normal.z,
                ambientRed, ambientGreen, ambientBlue,
                diffuseRed, diffuseGreen, diffuseBlue, diffuseAlpha,
                ogl.materialSpecular[0], ogl.materialSpecular[1], ogl.materialSpecular[2],
                ogl.materialEmission[0], ogl.materialEmission[1], ogl.materialEmission[2],
                ogl.materialShininess);
    }

    // ------------------------------------------------------------------
    // GL state replay
    // ------------------------------------------------------------------

    private void applyState(boolean cpuPath) {
        OglRenderer.OglState ogl = owner.oglState();
        textureStateUnrepresentable = false;
        applyViewport(ogl);
        applyScissor(scaledClip());

        GLES10.glMatrixMode(GraphicsOGL.GL_PROJECTION);
        loadMatrixIfChanged(loadedProjection, projectionLoaded, ogl.projectionMatrix);
        projectionLoaded = true;

        GLES10.glMatrixMode(GraphicsOGL.GL_MODELVIEW);
        boolean nativeLighting = !cpuPath && ogl.lightingEnabled();
        if (nativeLighting) {
            GLES10.glLoadIdentity();
            modelViewLoaded = false;
            syncLights(ogl);
        }
        float[] modelView = cpuPath ? IDENTITY : ogl.modelViewMatrix;
        loadMatrixIfChanged(loadedModelView, modelViewLoaded, modelView);
        modelViewLoaded = true;

        setCap(CAP_BLEND, ogl.blendCapEnabled);
        setCap(CAP_DEPTH, ogl.depthTestEnabled);
        setCap(CAP_LIGHTING, nativeLighting);
        setCap(CAP_ALPHA, ogl.alphaTestEnabled);
        setCap(CAP_CULL, ogl.cullFaceEnabled);
        setCap(CAP_NORMALIZE, ogl.normalizeEnabled);
        setCap(CAP_RESCALE, ogl.rescaleNormalEnabled);

        GLES10.glDepthMask(ogl.depthMask);
        depthMaskState = ogl.depthMask;
        depthMaskKnown = true;
        if (depthFunc != ogl.depthFunc) {
            GLES10.glDepthFunc(ogl.depthFunc);
            depthFunc = ogl.depthFunc;
        }
        if (blendSrc != ogl.blendSrcFactor || blendDst != ogl.blendDstFactor) {
            GLES10.glBlendFunc(ogl.blendSrcFactor, ogl.blendDstFactor);
            blendSrc = ogl.blendSrcFactor;
            blendDst = ogl.blendDstFactor;
        }
        if (alphaFunc != ogl.alphaFunc || Float.compare(alphaRef, ogl.alphaRef) != 0) {
            GLES10.glAlphaFunc(ogl.alphaFunc, ogl.alphaRef);
            alphaFunc = ogl.alphaFunc;
            alphaRef = ogl.alphaRef;
        }
        if (cullFace != ogl.cullFace) {
            GLES10.glCullFace(ogl.cullFace);
            cullFace = ogl.cullFace;
        }
        if (frontFace != ogl.frontFace) {
            GLES10.glFrontFace(ogl.frontFace);
            frontFace = ogl.frontFace;
        }
        if (shadeModel != ogl.shadeModel) {
            GLES10.glShadeModel(ogl.shadeModel);
            shadeModel = ogl.shadeModel;
        }
        if (currentColor != ogl.color) {
            GLES10.glColor4f(
                    ((ogl.color >>> 16) & 0xFF) / 255f,
                    ((ogl.color >>> 8) & 0xFF) / 255f,
                    (ogl.color & 0xFF) / 255f,
                    ((ogl.color >>> 24) & 0xFF) / 255f);
            currentColor = ogl.color;
        }
        if (nativeLighting) {
            syncMaterials(ogl);
        }

        for (int unit = 0; unit < UNIT_COUNT; unit++) {
            applyTextureUnit(unit, ogl.textureUnit(unit));
        }
        applyActiveTexture(0);
    }

    private void applyViewport(OglRenderer.OglState ogl) {
        float scaleX = owner.host().surface().oglRenderScaleX();
        float scaleY = owner.host().surface().oglRenderScaleY();
        int x = Math.round(ogl.viewportX * scaleX);
        int width = Math.max(0, Math.round(ogl.viewportWidth * scaleX));
        int height = Math.max(0, Math.round(ogl.viewportHeight * scaleY));
        int y = Math.round(owner.host().surface().oglRenderHeight()
                - (ogl.viewportY + ogl.viewportHeight) * scaleY);
        if (x != viewportX || y != viewportY
                || width != viewportWidth || height != viewportHeight) {
            GLES10.glViewport(x, y, width, height);
            viewportX = x;
            viewportY = y;
            viewportWidth = width;
            viewportHeight = height;
        }
        // The software rasterizer stores 1 - windowDepth but also mirrors the
        // GL comparison (GL_LESS passes when the new stored depth is larger).
        // Native GL with the plain window depth and the game's own depth
        // function produces the same visibility result, so the range is not
        // inverted here.
        float near = ogl.depthRangeNear;
        float far = ogl.depthRangeFar;
        if (Float.compare(near, depthRangeNear) != 0 || Float.compare(far, depthRangeFar) != 0) {
            GLES10.glDepthRangef(near, far);
            depthRangeNear = near;
            depthRangeFar = far;
        }
    }

    private Rectangle scaledClip() {
        Rectangle clip = owner.host().delegate().getClipBounds();
        if (clip == null) {
            return null;
        }
        DesktopSurface surface = owner.host().surface();
        int surfaceWidth = surface.width();
        int surfaceHeight = surface.height();
        int renderWidth = targetWidth;
        int renderHeight = targetHeight;
        if (surfaceWidth <= 0 || surfaceHeight <= 0) {
            return null;
        }
        Rectangle scaled = new Rectangle();
        int left = (int) Math.floor((double) clip.x * renderWidth / surfaceWidth);
        int top = (int) Math.floor((double) clip.y * renderHeight / surfaceHeight);
        int right = (int) Math.ceil((double) (clip.x + clip.width) * renderWidth / surfaceWidth);
        int bottom = (int) Math.ceil((double) (clip.y + clip.height) * renderHeight / surfaceHeight);
        scaled.x = Math.max(0, left);
        scaled.y = Math.max(0, top);
        scaled.width = Math.max(0, Math.min(renderWidth, right) - scaled.x);
        scaled.height = Math.max(0, Math.min(renderHeight, bottom) - scaled.y);
        return scaled;
    }

    private boolean coversWholeTarget(Rectangle clip) {
        return clip == null
                || (clip.x <= 0 && clip.y <= 0
                && clip.x + clip.width >= targetWidth
                && clip.y + clip.height >= targetHeight);
    }

    private void applyScissor(Rectangle clip) {
        if (coversWholeTarget(clip)) {
            if (!scissorKnown || scissorEnabled) {
                GLES10.glDisable(GLES10.GL_SCISSOR_TEST);
                scissorEnabled = false;
                scissorKnown = true;
            }
            return;
        }
        int x = Math.max(0, Math.min(targetWidth, clip.x));
        int top = Math.max(0, Math.min(targetHeight, clip.y));
        int width = Math.max(0, Math.min(targetWidth - x, clip.width));
        int height = Math.max(0, Math.min(targetHeight - top, clip.height));
        int y = Math.max(0, targetHeight - (top + height));
        if (!scissorKnown || !scissorEnabled
                || x != scissorX || y != scissorY
                || width != scissorWidth || height != scissorHeight) {
            GLES10.glEnable(GLES10.GL_SCISSOR_TEST);
            GLES10.glScissor(x, y, width, height);
            scissorEnabled = true;
            scissorKnown = true;
            scissorX = x;
            scissorY = y;
            scissorWidth = width;
            scissorHeight = height;
        }
    }

    private void applyActiveTexture(int unit) {
        if (activeTextureUnit != unit) {
            GLES11.glActiveTexture(GraphicsOGL.GL_TEXTURE0 + unit);
            activeTextureUnit = unit;
        }
    }

    private void applyTextureUnit(int unit, OglRenderer.OglTextureUnit textureUnit) {
        if (textureUnit == null) {
            return;
        }
        applyActiveTexture(unit);
        OglRenderer.OglTexture texture = owner.oglState().boundTexture(unit);
        GpuTexture gpu = texture == null ? null : uploadTexture(texture, unit);
        boolean enabled = textureUnit.texture2DEnabled && gpu != null;
        if (textureUnit.texture2DEnabled && gpu == null) {
            // The software renderer must own this draw if an enabled texture
            // cannot be represented (NPOT+REPEAT, oversized, empty pixels).
            textureStateUnrepresentable = true;
            noteDrawFallback("texture unit=" + unit + " unrepresentable"
                    + (texture != null ? " id=" + System.identityHashCode(texture)
                    + " w=" + texture.width
                    + " h=" + texture.height + " format=" + texture.baseFormat()
                    + " wrap=" + texture.wrapS + "/" + texture.wrapT
                    + " pixels=" + texture.pixels.length
                    + " revision=" + texture.uploadRevision() : " texture=null"));
        }
        setCap(unit == 0 ? CAP_TEXTURE0 : CAP_TEXTURE1, enabled);
        if (gpu != null) {
            if (boundTextureIds[unit] != gpu.glId) {
                GLES10.glBindTexture(GLES10.GL_TEXTURE_2D, gpu.glId);
                boundTextureIds[unit] = gpu.glId;
            }
            applyTextureEnv(unit, textureUnit, gpu);
            applyTextureMatrix(unit, textureUnit, gpu);
        }
    }

    private void applyTextureEnv(int unit, OglRenderer.OglTextureUnit textureUnit, GpuTexture texture) {
        TextureEnvCache cache = textureEnvCaches[unit];
        GLES11.glTexEnvi(GraphicsOGL.GL_TEXTURE_ENV, GraphicsOGL.GL_TEXTURE_ENV_MODE,
                textureUnit.textureEnvMode);
        if (textureUnit.textureEnvMode == GraphicsOGL.GL_COMBINE) {
            setTexEnvInt(cache, 0, GraphicsOGL.GL_COMBINE_RGB, textureUnit.combineRgb);
            setTexEnvInt(cache, 1, GraphicsOGL.GL_COMBINE_ALPHA, textureUnit.combineAlpha);
            setTexEnvInt(cache, 2, GraphicsOGL.GL_SRC0_RGB, textureUnit.srcRgb[0]);
            setTexEnvInt(cache, 3, GraphicsOGL.GL_SRC1_RGB, textureUnit.srcRgb[1]);
            setTexEnvInt(cache, 4, GraphicsOGL.GL_SRC2_RGB, textureUnit.srcRgb[2]);
            setTexEnvInt(cache, 5, GraphicsOGL.GL_SRC0_ALPHA, textureUnit.srcAlpha[0]);
            setTexEnvInt(cache, 6, GraphicsOGL.GL_SRC1_ALPHA, textureUnit.srcAlpha[1]);
            setTexEnvInt(cache, 7, GraphicsOGL.GL_SRC2_ALPHA, textureUnit.srcAlpha[2]);
            setTexEnvInt(cache, 8, GraphicsOGL.GL_OPERAND0_RGB, textureUnit.operandRgb[0]);
            setTexEnvInt(cache, 9, GraphicsOGL.GL_OPERAND1_RGB, textureUnit.operandRgb[1]);
            setTexEnvInt(cache, 10, GraphicsOGL.GL_OPERAND2_RGB, textureUnit.operandRgb[2]);
            setTexEnvInt(cache, 11, GraphicsOGL.GL_OPERAND0_ALPHA, textureUnit.operandAlpha[0]);
            setTexEnvInt(cache, 12, GraphicsOGL.GL_OPERAND1_ALPHA, textureUnit.operandAlpha[1]);
            setTexEnvInt(cache, 13, GraphicsOGL.GL_OPERAND2_ALPHA, textureUnit.operandAlpha[2]);
            setTexEnvInt(cache, 14, GraphicsOGL.GL_RGB_SCALE, textureUnit.rgbScale);
            setTexEnvInt(cache, 15, GraphicsOGL.GL_ALPHA_SCALE, textureUnit.alphaScale);
        }
        if (cache.envColor != textureUnit.textureEnvColor) {
            float[] color = {
                    ((textureUnit.textureEnvColor >>> 16) & 0xFF) / 255f,
                    ((textureUnit.textureEnvColor >>> 8) & 0xFF) / 255f,
                    (textureUnit.textureEnvColor & 0xFF) / 255f,
                    ((textureUnit.textureEnvColor >>> 24) & 0xFF) / 255f
            };
            GLES10.glTexEnvfv(GraphicsOGL.GL_TEXTURE_ENV,
                    GraphicsOGL.GL_TEXTURE_ENV_COLOR, color, 0);
            cache.envColor = textureUnit.textureEnvColor;
        }
        cache.known = true;
    }

    private static void setTexEnvInt(TextureEnvCache cache, int index, int pname, int value) {
        if (!cache.known || cache.values[index] != value) {
            GLES11.glTexEnvi(GraphicsOGL.GL_TEXTURE_ENV, pname, value);
            cache.values[index] = value;
        }
    }

    private void applyTextureMatrix(int unit, OglRenderer.OglTextureUnit textureUnit, GpuTexture texture) {
        float[] matrix = textureUnit.textureMatrix;
        if (matrix == null) {
            matrix = IDENTITY;
        }
        float scaleS = (texture.texture.width - 1f) / texture.glWidth;
        float scaleT = (texture.texture.height - 1f) / texture.glHeight;
        float translateS = 0.5f / texture.glWidth;
        float translateT = 0.5f / texture.glHeight;
        float[] corrected = matrixScratch;
        for (int column = 0; column < 4; column++) {
            int offset = column * 4;
            corrected[offset] = scaleS * matrix[offset] + translateS * matrix[offset + 3];
            corrected[offset + 1] = scaleT * matrix[offset + 1] + translateT * matrix[offset + 3];
            corrected[offset + 2] = matrix[offset + 2];
            corrected[offset + 3] = matrix[offset + 3];
        }
        if (!textureMatrixLoaded[unit]
                || !java.util.Arrays.equals(loadedTextureMatrices[unit], corrected)) {
            applyActiveTexture(unit);
            GLES10.glMatrixMode(GraphicsOGL.GL_TEXTURE);
            GLES10.glLoadMatrixf(corrected, 0);
            System.arraycopy(corrected, 0, loadedTextureMatrices[unit], 0, 16);
            textureMatrixLoaded[unit] = true;
            GLES10.glMatrixMode(GraphicsOGL.GL_MODELVIEW);
        }
    }

    private void loadMatrixIfChanged(float[] cache, boolean cacheValid, float[] matrix) {
        if (cacheValid && java.util.Arrays.equals(cache, matrix)) {
            return;
        }
        GLES10.glLoadMatrixf(matrix, 0);
        System.arraycopy(matrix, 0, cache, 0, 16);
    }

    private void setCap(int index, boolean enabled) {
        if (!capKnown[index] || capValues[index] != enabled) {
            int cap = switch (index) {
                case CAP_BLEND -> GraphicsOGL.GL_BLEND;
                case CAP_DEPTH -> GraphicsOGL.GL_DEPTH_TEST;
                case CAP_LIGHTING -> GraphicsOGL.GL_LIGHTING;
                case CAP_ALPHA -> GraphicsOGL.GL_ALPHA_TEST;
                case CAP_CULL -> GraphicsOGL.GL_CULL_FACE;
                case CAP_NORMALIZE -> GraphicsOGL.GL_NORMALIZE;
                case CAP_RESCALE -> GraphicsOGL.GL_RESCALE_NORMAL;
                case CAP_TEXTURE0 -> GraphicsOGL.GL_TEXTURE_2D;
                case CAP_TEXTURE1 -> GraphicsOGL.GL_TEXTURE_2D;
                default -> -1;
            };
            if (cap >= 0) {
                if (index == CAP_TEXTURE1) {
                    applyActiveTexture(1);
                } else if (index == CAP_TEXTURE0) {
                    applyActiveTexture(0);
                }
                if (enabled) {
                    GLES10.glEnable(cap);
                } else {
                    GLES10.glDisable(cap);
                }
            }
            capValues[index] = enabled;
            capKnown[index] = true;
        }
    }

    private void setCurrentNormal(float x, float y, float z) {
        if (!currentNormalKnown
                || Float.compare(currentNormal[0], x) != 0
                || Float.compare(currentNormal[1], y) != 0
                || Float.compare(currentNormal[2], z) != 0) {
            GLES10.glNormal3f(x, y, z);
            currentNormal[0] = x;
            currentNormal[1] = y;
            currentNormal[2] = z;
            currentNormalKnown = true;
        }
    }

    private void syncLights(OglRenderer.OglState ogl) {
        float[] ambient = ogl.lightModelAmbient;
        if (!lightModelAmbientKnown || !same4(lightModelAmbient, ambient)) {
            GLES10.glLightModelfv(GraphicsOGL.GL_LIGHT_MODEL_AMBIENT, ambient, 0);
            System.arraycopy(ambient, 0, lightModelAmbient, 0, 4);
            lightModelAmbientKnown = true;
        }
        if (!lightModelTwoSideKnown || lightModelTwoSide != ogl.lightModelTwoSide) {
            GLES10.glLightModelf(GraphicsOGL.GL_LIGHT_MODEL_TWO_SIDE, ogl.lightModelTwoSide ? 1f : 0f);
            lightModelTwoSide = ogl.lightModelTwoSide;
            lightModelTwoSideKnown = true;
        }
        for (int i = 0; i < MAX_LIGHTS; i++) {
            OglRenderer.OglLight light = ogl.lights[i];
            int lightEnum = GraphicsOGL.GL_LIGHT0 + i;
            boolean enabled = ogl.lightEnabled[i];
            if (!lightEnabledKnown[i] || lightEnabled[i] != enabled) {
                if (enabled) {
                    GLES10.glEnable(lightEnum);
                } else {
                    GLES10.glDisable(lightEnum);
                }
                lightEnabled[i] = enabled;
                lightEnabledKnown[i] = true;
            }
            if (!enabled) {
                continue;
            }
            if (!lightDataKnown[i]
                    || !same4(lightAmbient[i], light.ambient)
                    || !same4(lightDiffuse[i], light.diffuse)
                    || !same4(lightSpecular[i], light.specular)
                    || !same4(lightPosition[i], light.position)
                    || !same3(lightSpotDirection[i], light.spotDirection)
                    || !sameScalars(lightScalars[i], light)) {
                GLES10.glLightfv(lightEnum, GraphicsOGL.GL_AMBIENT, light.ambient, 0);
                GLES10.glLightfv(lightEnum, GraphicsOGL.GL_DIFFUSE, light.diffuse, 0);
                GLES10.glLightfv(lightEnum, GraphicsOGL.GL_SPECULAR, light.specular, 0);
                GLES10.glLightfv(lightEnum, GraphicsOGL.GL_POSITION, light.position, 0);
                GLES10.glLightfv(lightEnum, GraphicsOGL.GL_SPOT_DIRECTION, light.spotDirection, 0);
                GLES10.glLightf(lightEnum, GraphicsOGL.GL_SPOT_EXPONENT, light.spotExponent);
                GLES10.glLightf(lightEnum, GraphicsOGL.GL_SPOT_CUTOFF, light.spotCutoff);
                GLES10.glLightf(lightEnum, GraphicsOGL.GL_CONSTANT_ATTENUATION, light.constantAttenuation);
                GLES10.glLightf(lightEnum, GraphicsOGL.GL_LINEAR_ATTENUATION, light.linearAttenuation);
                GLES10.glLightf(lightEnum, GraphicsOGL.GL_QUADRATIC_ATTENUATION, light.quadraticAttenuation);
                System.arraycopy(light.ambient, 0, lightAmbient[i], 0, 4);
                System.arraycopy(light.diffuse, 0, lightDiffuse[i], 0, 4);
                System.arraycopy(light.specular, 0, lightSpecular[i], 0, 4);
                System.arraycopy(light.position, 0, lightPosition[i], 0, 4);
                System.arraycopy(light.spotDirection, 0, lightSpotDirection[i], 0, 3);
                lightScalars[i][0] = light.spotExponent;
                lightScalars[i][1] = light.spotCutoff;
                lightScalars[i][2] = light.constantAttenuation;
                lightScalars[i][3] = light.linearAttenuation;
                lightScalars[i][4] = light.quadraticAttenuation;
                lightDataKnown[i] = true;
            }
        }
    }

    private static boolean same4(float[] left, float[] right) {
        return Float.compare(left[0], right[0]) == 0
                && Float.compare(left[1], right[1]) == 0
                && Float.compare(left[2], right[2]) == 0
                && Float.compare(left[3], right[3]) == 0;
    }

    private static boolean same3(float[] left, float[] right) {
        return Float.compare(left[0], right[0]) == 0
                && Float.compare(left[1], right[1]) == 0
                && Float.compare(left[2], right[2]) == 0;
    }

    private static boolean sameScalars(float[] cache, OglRenderer.OglLight light) {
        return Float.compare(cache[0], light.spotExponent) == 0
                && Float.compare(cache[1], light.spotCutoff) == 0
                && Float.compare(cache[2], light.constantAttenuation) == 0
                && Float.compare(cache[3], light.linearAttenuation) == 0
                && Float.compare(cache[4], light.quadraticAttenuation) == 0;
    }

    private void syncMaterials(OglRenderer.OglState ogl) {
        if (!same4(materialAmbient, ogl.materialAmbient)) {
            GLES10.glMaterialfv(GraphicsOGL.GL_FRONT_AND_BACK, GraphicsOGL.GL_AMBIENT,
                    ogl.materialAmbient, 0);
            System.arraycopy(ogl.materialAmbient, 0, materialAmbient, 0, 4);
        }
        if (!same4(materialDiffuse, ogl.materialDiffuse)) {
            GLES10.glMaterialfv(GraphicsOGL.GL_FRONT_AND_BACK, GraphicsOGL.GL_DIFFUSE,
                    ogl.materialDiffuse, 0);
            System.arraycopy(ogl.materialDiffuse, 0, materialDiffuse, 0, 4);
        }
        if (!same4(materialSpecular, ogl.materialSpecular)) {
            GLES10.glMaterialfv(GraphicsOGL.GL_FRONT_AND_BACK, GraphicsOGL.GL_SPECULAR,
                    ogl.materialSpecular, 0);
            System.arraycopy(ogl.materialSpecular, 0, materialSpecular, 0, 4);
        }
        if (!same4(materialEmission, ogl.materialEmission)) {
            GLES10.glMaterialfv(GraphicsOGL.GL_FRONT_AND_BACK, GraphicsOGL.GL_EMISSION,
                    ogl.materialEmission, 0);
            System.arraycopy(ogl.materialEmission, 0, materialEmission, 0, 4);
        }
        if (Float.compare(materialShininess, ogl.materialShininess) != 0) {
            GLES10.glMaterialf(GraphicsOGL.GL_FRONT_AND_BACK, GraphicsOGL.GL_SHININESS,
                    ogl.materialShininess);
            materialShininess = ogl.materialShininess;
        }
    }

    // ------------------------------------------------------------------
    // Texture upload
    // ------------------------------------------------------------------

    private GpuTexture uploadTexture(OglRenderer.OglTexture texture, int unit) {
        GpuTexture gpu = gpuTextures.get(texture);
        if (gpu == null) {
            int[] ids = new int[1];
            GLES10.glGenTextures(1, ids, 0);
            if (ids[0] == 0) {
                noteDrawFallback("texture glGenTextures returned 0 glError=0x"
                        + Integer.toHexString(GLES10.glGetError()));
                return null;
            }
            gpu = new GpuTexture(texture, ids[0]);
            gpuTextures.put(texture, gpu);
            for (Map.Entry<Integer, OglRenderer.OglTexture> entry : owner.oglState().textures.entrySet()) {
                if (entry.getValue() == texture) {
                    gpuTexturesByDojaId.put(entry.getKey(), gpu);
                    break;
                }
            }
        }
        int wrapS = effectiveWrap(texture.wrapS);
        int wrapT = effectiveWrap(texture.wrapT);
        boolean padded = gpu.glWidth != 0 && gpu.glHeight != 0
                && (gpu.glWidth != texture.width || gpu.glHeight != texture.height);
        if (padded && (wrapS == GraphicsOGL.GL_REPEAT || wrapT == GraphicsOGL.GL_REPEAT)) {
            // A POT-padded copy cannot tile; let the software renderer draw it.
            return null;
        }
        if (gpu.revision == texture.uploadRevision()
                && gpu.width == texture.width && gpu.height == texture.height
                && gpu.baseFormat == texture.baseFormat()) {
            applyTextureParameters(gpu, texture, unit, wrapS, wrapT);
            return gpu;
        }
        long uploadStartNanos = System.nanoTime();
        if (texture.pixels.length == 0 || texture.width <= 0 || texture.height <= 0) {
            return null;
        }
        int glWidth = texture.width;
        int glHeight = texture.height;
        if (!npotSupported && (!isPowerOfTwo(glWidth) || !isPowerOfTwo(glHeight))) {
            glWidth = nextPowerOfTwo(glWidth);
            glHeight = nextPowerOfTwo(glHeight);
            if (wrapS == GraphicsOGL.GL_REPEAT || wrapT == GraphicsOGL.GL_REPEAT) {
                return null;
            }
        }
        if (glWidth > maxTextureSize || glHeight > maxTextureSize) {
            return null;
        }
        int format = texture.baseFormat();
        int bytesPerPixel = textureBytesPerPixel(format);
        if (bytesPerPixel <= 0) {
            return null;
        }
        int required = glWidth * glHeight * bytesPerPixel;
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocateDirect(required)
                .order(java.nio.ByteOrder.nativeOrder());
        int[] pixels = texture.pixels;
        int sourceWidth = texture.width;
        int sourceHeight = texture.height;
        for (int y = 0; y < sourceHeight; y++) {
            int row = y * sourceWidth;
            for (int x = 0; x < sourceWidth; x++) {
                int argb = pixels[row + x];
                int red = (argb >>> 16) & 0xFF;
                int green = (argb >>> 8) & 0xFF;
                int blue = argb & 0xFF;
                int alpha = (argb >>> 24) & 0xFF;
                switch (format) {
                    case GraphicsOGL.GL_RGBA -> {
                        buffer.put((byte) red);
                        buffer.put((byte) green);
                        buffer.put((byte) blue);
                        buffer.put((byte) alpha);
                    }
                    case GraphicsOGL.GL_RGB -> {
                        buffer.put((byte) red);
                        buffer.put((byte) green);
                        buffer.put((byte) blue);
                    }
                    case GraphicsOGL.GL_ALPHA -> buffer.put((byte) alpha);
                    case GraphicsOGL.GL_LUMINANCE -> buffer.put((byte) red);
                    case GraphicsOGL.GL_LUMINANCE_ALPHA -> {
                        buffer.put((byte) red);
                        buffer.put((byte) alpha);
                    }
                    default -> {
                        return null;
                    }
                }
            }
            for (int x = sourceWidth; x < glWidth; x++) {
                for (int i = 0; i < bytesPerPixel; i++) {
                    buffer.put((byte) 0);
                }
            }
        }
        for (int y = sourceHeight; y < glHeight; y++) {
            for (int i = 0; i < glWidth * bytesPerPixel; i++) {
                buffer.put((byte) 0);
            }
        }
        buffer.position(0);
        buffer.limit(required);
        applyActiveTexture(unit);
        GLES10.glBindTexture(GLES10.GL_TEXTURE_2D, gpu.glId);
        boundTextureIds[unit] = gpu.glId;
        GLES10.glPixelStorei(GLES10.GL_UNPACK_ALIGNMENT, 1);
        GLES10.glTexImage2D(GLES10.GL_TEXTURE_2D, 0, format, glWidth, glHeight, 0,
                format, GLES10.GL_UNSIGNED_BYTE, buffer);
        gpu.revision = texture.uploadRevision();
        gpu.width = texture.width;
        gpu.height = texture.height;
        gpu.glWidth = glWidth;
        gpu.glHeight = glHeight;
        gpu.baseFormat = format;
        applyTextureParameters(gpu, texture, unit, wrapS, wrapT);
        statTextureUploadNanos += System.nanoTime() - uploadStartNanos;
        statTextureUploads++;
        return gpu;
    }

    private void applyTextureParameters(GpuTexture gpu, OglRenderer.OglTexture texture, int unit,
                                        int wrapS, int wrapT) {
        int minFilter = effectiveMinFilter(texture.minFilter);
        int magFilter = effectiveMagFilter(texture.magFilter);
        if (gpu.minFilter == minFilter && gpu.magFilter == magFilter
                && gpu.wrapS == wrapS && gpu.wrapT == wrapT) {
            return;
        }
        applyActiveTexture(unit);
        if (boundTextureIds[unit] != gpu.glId) {
            GLES10.glBindTexture(GLES10.GL_TEXTURE_2D, gpu.glId);
            boundTextureIds[unit] = gpu.glId;
        }
        if (gpu.minFilter != minFilter) {
            GLES11.glTexParameteri(GLES10.GL_TEXTURE_2D, GraphicsOGL.GL_TEXTURE_MIN_FILTER, minFilter);
            gpu.minFilter = minFilter;
        }
        if (gpu.magFilter != magFilter) {
            GLES11.glTexParameteri(GLES10.GL_TEXTURE_2D, GraphicsOGL.GL_TEXTURE_MAG_FILTER, magFilter);
            gpu.magFilter = magFilter;
        }
        if (gpu.wrapS != wrapS) {
            GLES11.glTexParameteri(GLES10.GL_TEXTURE_2D, GraphicsOGL.GL_TEXTURE_WRAP_S, wrapS);
            gpu.wrapS = wrapS;
        }
        if (gpu.wrapT != wrapT) {
            GLES11.glTexParameteri(GLES10.GL_TEXTURE_2D, GraphicsOGL.GL_TEXTURE_WRAP_T, wrapT);
            gpu.wrapT = wrapT;
        }
    }

    private static int effectiveMinFilter(int filter) {
        return switch (filter) {
            case GraphicsOGL.GL_NEAREST,
                    GraphicsOGL.GL_LINEAR -> filter;
            default -> GraphicsOGL.GL_NEAREST;
        };
    }

    private static int effectiveMagFilter(int filter) {
        return filter == GraphicsOGL.GL_LINEAR ? GraphicsOGL.GL_LINEAR : GraphicsOGL.GL_NEAREST;
    }

    private static int effectiveWrap(int wrap) {
        // The software sampler treats anything but CLAMP_TO_EDGE as REPEAT.
        return wrap == GraphicsOGL.GL_CLAMP_TO_EDGE
                ? GraphicsOGL.GL_CLAMP_TO_EDGE : GraphicsOGL.GL_REPEAT;
    }

    private static int textureBytesPerPixel(int format) {
        return switch (format) {
            case GraphicsOGL.GL_RGBA -> 4;
            case GraphicsOGL.GL_RGB -> 3;
            case GraphicsOGL.GL_ALPHA, GraphicsOGL.GL_LUMINANCE -> 1;
            case GraphicsOGL.GL_LUMINANCE_ALPHA -> 2;
            default -> -1;
        };
    }

    private static boolean isPowerOfTwo(int value) {
        return value > 0 && (value & (value - 1)) == 0;
    }

    private static int nextPowerOfTwo(int value) {
        int result = 1;
        while (result < value) {
            result <<= 1;
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Client-array copying
    // ------------------------------------------------------------------

    private boolean copyPointer(OglRenderer.OglPointer pointer, int maxIndex, Scratch destination) {
        if (pointer == null) {
            return false;
        }
        int componentBytes = componentBytes(pointer.type());
        if (componentBytes <= 0) {
            return false;
        }
        int components = Math.max(1, pointer.size());
        int stride = pointer.stride() > 0 ? pointer.stride() : components * componentBytes;
        int rowBytes = components * componentBytes;
        long requiredEnd = (long) pointer.byteOffset()
                + (long) Math.max(0, maxIndex) * stride + rowBytes;
        long needed = (long) Math.max(0, maxIndex) * stride + rowBytes;
        if (pointer.byteOffset() < 0 || needed <= 0 || requiredEnd > Integer.MAX_VALUE) {
            return false;
        }
        int requiredBytes = (int) needed;
        if (pointer.bufferObject() != null) {
            byte[] data = pointer.bufferObject().data();
            if (requiredEnd > data.length) {
                return false;
            }
            destination.putBytes(data, pointer.byteOffset(), requiredBytes);
            return true;
        }
        DirectBuffer direct = pointer.pointer();
        if (direct == null) {
            return false;
        }
        if (direct instanceof ByteBuffer byteBuffer) {
            byte[] data = DirectBufferFactory.peekByteArray(byteBuffer);
            if (data == null) {
                return false;
            }
            int segmentOffset = DirectBufferFactory.getSegmentOffset(byteBuffer);
            int segmentLength = DirectBufferFactory.getSegmentLength(byteBuffer);
            int start = segmentOffset + pointer.byteOffset();
            if (pointer.byteOffset() < 0 || start < 0
                    || (long) pointer.byteOffset() + requiredBytes > segmentLength) {
                return false;
            }
            destination.putBytes(data, start, requiredBytes);
            return true;
        }
        if (direct instanceof ShortBuffer shortBuffer) {
            short[] data = DirectBufferFactory.peekShortArray(shortBuffer);
            if (data == null || (pointer.byteOffset() & 1) != 0 || (requiredBytes & 1) != 0) {
                return false;
            }
            int segmentOffset = DirectBufferFactory.getSegmentOffset(shortBuffer);
            int segmentLength = DirectBufferFactory.getSegmentLength(shortBuffer);
            int startElement = segmentOffset + (pointer.byteOffset() >>> 1);
            int elementCount = requiredBytes >>> 1;
            if (pointer.byteOffset() < 0 || startElement < 0
                    || (pointer.byteOffset() >>> 1) + elementCount > segmentLength) {
                return false;
            }
            destination.putShorts(data, startElement, elementCount);
            return true;
        }
        if (direct instanceof FloatBuffer floatBuffer) {
            float[] data = DirectBufferFactory.peekFloatArray(floatBuffer);
            if (data == null || (pointer.byteOffset() & 3) != 0 || (requiredBytes & 3) != 0) {
                return false;
            }
            int segmentOffset = DirectBufferFactory.getSegmentOffset(floatBuffer);
            int segmentLength = DirectBufferFactory.getSegmentLength(floatBuffer);
            int startElement = segmentOffset + (pointer.byteOffset() >>> 2);
            int elementCount = requiredBytes >>> 2;
            if (pointer.byteOffset() < 0 || startElement < 0
                    || (pointer.byteOffset() >>> 2) + elementCount > segmentLength) {
                return false;
            }
            destination.putFloats(data, startElement, elementCount);
            return true;
        }
        return false;
    }

    private static int componentBytes(int type) {
        return switch (type) {
            case GraphicsOGL.GL_UNSIGNED_BYTE, GraphicsOGL.GL_BYTE -> 1;
            case GraphicsOGL.GL_SHORT, GraphicsOGL.GL_UNSIGNED_SHORT -> 2;
            case GraphicsOGL.GL_FLOAT -> 4;
            default -> -1;
        };
    }

    // ------------------------------------------------------------------
    // Scratch and cache types
    // ------------------------------------------------------------------

    private static final class TextureEnvCache {
        boolean known;
        final int[] values = new int[16];
        int envColor = Integer.MIN_VALUE;
    }

    private static final class GpuTexture {
        final OglRenderer.OglTexture texture;
        final int glId;
        int revision = -1;
        int width;
        int height;
        int glWidth;
        int glHeight;
        int baseFormat;
        int minFilter = -1;
        int magFilter = -1;
        int wrapS = -1;
        int wrapT = -1;

        GpuTexture(OglRenderer.OglTexture texture, int glId) {
            this.texture = texture;
            this.glId = glId;
        }
    }

    private static final class Scratch {
        java.nio.ByteBuffer bytes;
        private java.nio.ShortBuffer shorts;
        private java.nio.FloatBuffer floats;

        void ensure(int requiredBytes) {
            int capacity = Math.max(1, requiredBytes);
            if (bytes != null && bytes.capacity() >= capacity) {
                bytes.clear();
                return;
            }
            int grown = Math.max(capacity, bytes == null ? 0 : bytes.capacity() * 2);
            bytes = java.nio.ByteBuffer.allocateDirect(grown)
                    .order(java.nio.ByteOrder.nativeOrder());
            shorts = null;
            floats = null;
        }

        void putBytes(byte[] source, int offset, int length) {
            ensure(length);
            bytes.clear();
            bytes.put(source, offset, length);
            bytes.position(0);
            bytes.limit(length);
        }

        void putShorts(short[] source, int offset, int length) {
            ensure(length * 2);
            bytes.clear();
            shortsView().put(source, offset, length);
            bytes.position(0);
            bytes.limit(length * 2);
        }

        void putFloats(float[] source, int offset, int length) {
            ensure(length * 4);
            bytes.clear();
            floatsView().put(source, offset, length);
            bytes.position(0);
            bytes.limit(length * 4);
        }

        private java.nio.ShortBuffer shortsView() {
            java.nio.ShortBuffer view = shorts;
            if (view == null || view.capacity() < bytes.capacity() / 2) {
                view = bytes.asShortBuffer();
                shorts = view;
            }
            view.clear();
            return view;
        }

        private java.nio.FloatBuffer floatsView() {
            java.nio.FloatBuffer view = floats;
            if (view == null || view.capacity() < bytes.capacity() / 4) {
                view = bytes.asFloatBuffer();
                floats = view;
            }
            view.clear();
            return view;
        }
    }
}
