package opendoja.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.nttdocomo.ui.Display;
import com.nttdocomo.ui.Frame;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import opendoja.android.DisplayActivity;

public class JamSurfaceView extends View {
    private static final String TAG = "OpenDoJa";
    private static final int CTRL_NONE = 0;
    private static final int CTRL_UP = 1;
    private static final int CTRL_DOWN = 2;
    private static final int CTRL_LEFT = 3;
    private static final int CTRL_RIGHT = 4;
    private static final int CTRL_SELECT = 5;
    private static final int CTRL_SOFT1 = 6;
    private static final int CTRL_SOFT2 = 7;
    private static final int CTRL_MENU = 8;
    private static final int CTRL_CLEAR = 9;
    private static final int CTRL_DIAL_BASE = 10;

    private static final String[] DIAL_LABELS = {
            "1", "2", "3",
            "4", "5", "6",
            "7", "8", "9",
            "*", "0", "#",
    };
    private static final int[] DIAL_KEYS = {
            Display.KEY_1, Display.KEY_2, Display.KEY_3,
            Display.KEY_4, Display.KEY_5, Display.KEY_6,
            Display.KEY_7, Display.KEY_8, Display.KEY_9,
            Display.KEY_ASTERISK, Display.KEY_0, Display.KEY_POUND,
    };

    static class KeyDef {
        final int id;
        final String label;
        final int keyCode;
        final boolean repeatable;
        final RectF rect = new RectF();

        KeyDef(int id, String label, int keyCode, boolean repeatable) {
            this.id = id;
            this.label = label;
            this.keyCode = keyCode;
            this.repeatable = repeatable;
        }
    }

    private final Object lock = new Object();
    private Bitmap screen;
    private int screenWidth;
    private int screenHeight;
    private int[] pixels;
    private int screenVersion;

    private final List<KeyDef> keys = new ArrayList<>();
    private final Map<Integer, KeyDef> keysById = new HashMap<>();
    private KeyDef upKey;
    private KeyDef downKey;
    private KeyDef leftKey;
    private KeyDef rightKey;
    private KeyDef selectKey;

    private final Set<Integer> activeControls = new HashSet<>();
    private Integer pointerFinger;
    private final Map<Integer, Integer> pointerControls = new HashMap<>();

    private final Paint keyFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint keyStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint keyTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Bitmap upscaleBitmap;
    private Canvas upscaleCanvas;
    private Paint upscalePaint;
    private static final int DISPLAY_SNAPSHOT_COUNT = 3;
    private final Bitmap[] displaySnapshots = new Bitmap[DISPLAY_SNAPSHOT_COUNT];
    private int displaySnapshotIndex;
    private int[] displaySnapshotPixels;
    private Bitmap stableDisplayFrame;
    private int stableDisplayVersion = Integer.MIN_VALUE;

    private final android.graphics.Rect upscaleSrcRect = new android.graphics.Rect();
    private final android.graphics.RectF upscaleDstRectF = new android.graphics.RectF();
    private int upSrcWidth;
    private int upSrcHeight;
    private int upscaledVersion = -1;
    private boolean upscaleLogged = false;

    // Cached per-frame lookups: isSvgSkin() does asset file I/O and isLandscapeOrientation()
    // reads SharedPreferences, both of which must not happen every onDraw/zone computation.
    private String cachedSvgSkinKey;
    private boolean cachedSvgSkinResult;
    private Boolean cachedLandscape;

    // Display filter state: the filtered frame is cached per source version so
    // the filter only runs when the game actually presents a new frame.
    private Bitmap filterBitmap;
    private int[] filterPixels;
    private int filterVersion = -1;
    private String filterAppliedId;
    private GameFilter filterInstance;

    private final RectF gameRect = new RectF();
    private final RectF padRect = new RectF();
    private int gamePixelW;
    private int gamePixelH;
    // Rounded-corner clip for the game image in Original (skin) mode: radius
    // follows the skin's screen opening (rx=11 in viewBox units) per axis so
    // it stays proportional when the skin is stretched non-uniformly.
    private float gameCornerRadiusX;
    private float gameCornerRadiusY;
    private final android.graphics.Path gameClipPath = new android.graphics.Path();

    private boolean rotated;
    private int rotationAngle;
    private boolean keypadVisible = true;
    private String cachedSkin = "silver";
    private boolean dpadVisible = true;
    private long lastDpadActivityMs = android.os.SystemClock.elapsedRealtime();
    private android.graphics.Bitmap cachedSkinBitmap;
    private String cachedSkinBitmapId;
    private String pendingSkinKey;
    private boolean skinRenderPending;

    // Cached display settings to avoid reading SharedPreferences on every frame.
    private String cachedSettingsGameId;
    private String cachedOrientation;
    private String cachedDisplayMode;
    private String cachedFilter;

    private int lastPointerX;
    private int lastPointerY;

    private String loadingText;
    private boolean loadingVisible;
    private String exitingText;
    private boolean exitingVisible;
    private String pausedText;
    private boolean pausedVisible;
    private final Paint overlayTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Fake status LED on the skin bezel: blinks green above 30% battery, red at
    // 30% or below, mirroring the lens circle baked into every skin SVG.
    private int batteryPercent = -1;
    private final Paint ledGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ledCorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ledRect = new RectF();
    private final RectF ledGlowRect = new RectF();
    private static final long LED_BLINK_CYCLE_MS = 5000L;
    private static final long LED_BLINK_ON_MS = 120L;
    private android.content.BroadcastReceiver batteryReceiver;

    private JamInputController input;
    private ControlsSkin controlsSkin;

    /** Set true after releaseAllControls() so present() from a stale game thread is ignored. */
    private boolean released = false;

    /** Session ID of the current game session; frames from older sessions are rejected. */
    private int expectedSessionId = -1;

    public JamSurfaceView(Context context, JamInputController input) {
        super(context);
        this.input = input;
        init();
    }

    public void present(opendoja.compat.awt.image.BufferedImage frame, int sessionId) {
        if (released || frame == null) return;
        // Reject frames from a different runtime session only when a live
        // runtime is active and the session does not match. Checking a
        // previously-captured expectedSessionId drops frames that arrive
        // during launch, before the new session id is known, leaving the
        // screen black with BGM still playing.
        opendoja.host.DoJaRuntime current = opendoja.host.DoJaRuntime.current();
        if (current != null && sessionId != current.sessionId()) return;
        android.graphics.Bitmap bmp = frame.getBitmap();
        if (bmp == null) return;
        // DoJa LCD frames are RGB; GL mask blending may leave alpha at zero while RGB still
        // carries the visible picture. Ignore alpha when compositing the presented frame.
        if (bmp.hasAlpha()) {
            bmp.setHasAlpha(false);
        }
        setScreen(bmp, bmp.getWidth(), bmp.getHeight(), screenVersion + 1);
        android.content.Context ctx = getContext();
        if (ctx instanceof EntryActivity) {
            ((EntryActivity) ctx).hideLoadingOverlay();
        }
    }

    /** Called when a new game session begins, clearing the released flag so rendering can proceed. */
    public void prepareForNewGame() {
        released = false;
        expectedSessionId = opendoja.host.DoJaRuntime.current() != null ? opendoja.host.DoJaRuntime.current().sessionId() : -1;
    }

    /** Re-enables frame delivery after a background suspend (EntryActivity
     *  clears the flag in releaseAllControls() when the activity pauses). */
    public void resumeRendering() {
        released = false;
    }

    private void init() {
        setFocusable(false);
        setFocusableInTouchMode(false);
        controlsSkin = ControlsSkin.load(getContext());
        keyFillPaint.setStyle(Paint.Style.FILL);
        keyStrokePaint.setStyle(Paint.Style.STROKE);
        keyStrokePaint.setStrokeWidth(2f);
        keyTextPaint.setColor(0xFFFFFFFF);
        keyTextPaint.setTextAlign(Paint.Align.CENTER);
        keyTextPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
try {
            android.content.SharedPreferences prefs = getContext().getSharedPreferences(ThemeActivity.PREFS_NAME, android.content.Context.MODE_PRIVATE);
            cachedSkin = prefs.getString(ThemeActivity.KEY_SKIN, "silver");
        } catch (Throwable ignored) {
            cachedSkin = "silver";
        }
        try {
            android.graphics.Typeface font = android.graphics.Typeface.createFromAsset(getContext().getAssets(), "fonts/PixelifySans-Bold.ttf");
            overlayTextPaint.setColor(0xFFFFFFFF);
            overlayTextPaint.setTextAlign(Paint.Align.CENTER);
            overlayTextPaint.setTypeface(font);
            overlayTextPaint.setAntiAlias(true);
        } catch (Throwable ignored) {
            overlayTextPaint.setColor(0xFFFFFFFF);
            overlayTextPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            overlayTextPaint.setAntiAlias(true);
        }
    }

    private KeyDef addKey(int id, String label, int keyCode, boolean repeatable) {
        KeyDef def = new KeyDef(id, label, keyCode, repeatable);
        keys.add(def);
        keysById.put(id, def);
        return def;
    }

    private float touchX(MotionEvent e, int index) {
        float x = e.getX(index);
        float y = e.getY(index);
        if (rotationAngle == -90) return getHeight() - y;
        if (rotationAngle == 90) return y;
        if (rotationAngle == 180) return getWidth() - x;
        return x;
    }

    private float touchY(MotionEvent e, int index) {
        float x = e.getX(index);
        float y = e.getY(index);
        if (rotationAngle == -90) return x;
        if (rotationAngle == 90) return getWidth() - x;
        if (rotationAngle == 180) return getHeight() - y;
        return y;
    }

    // Static initializer block to populate keys; runs after fields initialized.
    {
        keys.clear();
        keysById.clear();
        upKey = addKey(CTRL_UP, "\u25B2", Display.KEY_UP, false);
        downKey = addKey(CTRL_DOWN, "\u25BC", Display.KEY_DOWN, false);
        leftKey = addKey(CTRL_LEFT, "\u25C0", Display.KEY_LEFT, false);
        rightKey = addKey(CTRL_RIGHT, "\u25B6", Display.KEY_RIGHT, false);
        selectKey = addKey(CTRL_SELECT, "\u25CF", Display.KEY_SELECT, false);
        addKey(CTRL_SOFT1, "SOFT1", Frame.SOFT_KEY_1, true);
        addKey(CTRL_SOFT2, "SOFT2", Frame.SOFT_KEY_2, true);
        addKey(CTRL_MENU, "MENU", Display.KEY_MENU, false);
        addKey(CTRL_CLEAR, "CLEAR", Display.KEY_CLEAR, false);
        for (int i = 0; i < DIAL_LABELS.length; i++) {
            addKey(CTRL_DIAL_BASE + i, DIAL_LABELS[i], DIAL_KEYS[i], false);
        }
    }

    public void clearScreen() {
        synchronized (lock) {
            screen = null;
            screenWidth = 0;
            screenHeight = 0;
            screenVersion++;
        }
        postInvalidate();
    }

    public void setScreen(Bitmap screen, int width, int height, int version) {
        synchronized (lock) {
            this.screen = screen;
            this.screenWidth = width;
            this.screenHeight = height;
            this.screenVersion = version;
            if (pixels == null || pixels.length != width * height) {
                pixels = new int[width * height];
            }
        }
        postInvalidate();
    }

    /** Display the "Booting..." overlay, centered on the game viewport. */
    public void showLoadingOverlay(String text) {
        this.loadingText = text;
        this.loadingVisible = true;
        // Overlays are mutually exclusive so quick launch/exit does not stack them.
        this.exitingVisible = false;
        this.exitingText = null;
        this.pausedVisible = false;
        this.pausedText = null;
        postInvalidate();
    }

    /** Hide the boot overlay once the game has presented its first frame. */
    public void hideLoadingOverlay() {
        this.loadingVisible = false;
        this.loadingText = null;
        postInvalidate();
    }

    /** Display the "Exiting..." overlay, centered on the game viewport. */
    public void showExitingOverlay(String text) {
        this.exitingText = text;
        this.exitingVisible = true;
        // "Exiting..." supersedes "Booting..." and any pause overlay.
        this.loadingVisible = false;
        this.loadingText = null;
        this.pausedVisible = false;
        this.pausedText = null;
        postInvalidate();
    }

    public void hideExitingOverlay() {
        this.exitingVisible = false;
        this.exitingText = null;
        postInvalidate();
    }

    /** Display the "-Paused- Press any key to continue" overlay, centered on the game viewport. */
    public void showPausedOverlay(String text) {
        this.pausedText = text;
        this.pausedVisible = true;
        // Pause overlay supersedes the boot overlay.
        this.loadingVisible = false;
        this.loadingText = null;
        this.exitingVisible = false;
        this.exitingText = null;
        postInvalidate();
    }

    /** Hide the pause overlay once the game is resumed. */
    public void hidePausedOverlay() {
        this.pausedVisible = false;
        this.pausedText = null;
        postInvalidate();
    }

    public void setKeypadVisible(boolean visible) {
        if (keypadVisible == visible) return;
        keypadVisible = visible;
        if (!visible) releasePointers();
        postInvalidate();
    }

    public boolean isKeypadVisible() {
        return keypadVisible;
    }

    public void releaseAllControls() {
        released = true;
        activeControls.clear();
        pointerControls.clear();
        pointerFinger = null;
        if (input != null) {
            input.releaseAll();
        }
        // Clear the last rendered frame so the next game session does not see
        // a stale image from the previous game. Without this, onDraw() would
        // display the old game's final frame before the new game renders,
        // and any leftover render state can cause black screens or frozen
        // graphics in the new session (and in subsequent games).
        screen = null;
        screenVersion = 0;
        pixels = null;
        stableDisplayFrame = null;
        stableDisplayVersion = Integer.MIN_VALUE;
        displaySnapshotPixels = null;
        java.util.Arrays.fill(displaySnapshots, null);
        upscaleBitmap = null;
        upscaleCanvas = null;
        upSrcWidth = 0;
        upSrcHeight = 0;
        upscaledVersion = -1;
        upscaleLogged = false;
        filterBitmap = null;
        filterPixels = null;
        filterVersion = -1;
        filterAppliedId = null;
        filterInstance = null;
        cachedSvgSkinKey = null;
        cachedLandscape = null;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        rotationAngle = computeRotationAngle();
        rotated = (rotationAngle == 90 || rotationAngle == -90);
        if (rotationAngle == 90 || rotationAngle == -90) {
            canvas.save();
            canvas.translate(getWidth() / 2f, getHeight() / 2f);
            canvas.rotate(rotationAngle);
            canvas.translate(-getHeight() / 2f, -getWidth() / 2f);
        } else if (rotationAngle == 180) {
            canvas.save();
            canvas.translate(getWidth() / 2f, getHeight() / 2f);
            canvas.rotate(180f);
            canvas.translate(-getWidth() / 2f, -getHeight() / 2f);
        }
        Bitmap bmp;
        int sourceVersion;
        synchronized (lock) {
            bmp = screen;
            sourceVersion = screenVersion;
        }
        float viewWidth = rotated ? getHeight() : getWidth();
        float viewHeight = rotated ? getWidth() : getHeight();
        if (viewWidth <= 0f || viewHeight <= 0f) {
            if (rotationAngle != 0) canvas.restore();
            return;
        }
        computeZones();
        if (isLandscapeOrientation()) {
            if (dpadVisible && (android.os.SystemClock.elapsedRealtime() - lastDpadActivityMs > 10000L)) {
                dpadVisible = false;
            }
        } else {
            dpadVisible = true;
        }
        boolean fullScreen = isFullScreenMode();
        if (!fullScreen) {
            // Draw phone-like skin casing (background) in Original mode.
            boolean landscape = viewWidth >= viewHeight;
            android.graphics.Bitmap skinBmp = getSkinBitmap(cachedSkin, landscape, (int) viewWidth, (int) viewHeight);
            if (skinBmp != null && !skinBmp.isRecycled()) {
                android.graphics.Rect src = new android.graphics.Rect(0, 0, skinBmp.getWidth(), skinBmp.getHeight());
                android.graphics.RectF dst = new android.graphics.RectF(0f, 0f, viewWidth, viewHeight);
                canvas.drawBitmap(skinBmp, src, dst, null);
                drawSkinLed(canvas, viewWidth, viewHeight);
            } else {
                android.graphics.Paint skinPaint = new android.graphics.Paint();
                skinPaint.setColor(skinColor(cachedSkin));
                canvas.drawRect(0f, 0f, viewWidth, viewHeight, skinPaint);
                // subtle rounded bezel highlight
                android.graphics.Paint bezelPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                bezelPaint.setStyle(android.graphics.Paint.Style.STROKE);
                bezelPaint.setStrokeWidth(6f);
                bezelPaint.setColor(0x66FFFFFF);
                canvas.drawRoundRect(new android.graphics.RectF(3f, 3f, viewWidth - 3f, viewHeight - 3f), 24f, 24f, bezelPaint);
            }
        }
        if (bmp != null && !bmp.isRecycled()) {
            Bitmap displayFrame = snapshotForDisplay(bmp, sourceVersion);
            if (displayFrame != null) {
                drawGameUpscaled(canvas, displayFrame, sourceVersion);
            }
        }
        if (keypadVisible) {
            drawKeypad(canvas);
        }
        // Draw "Booting..." / "Exiting..." text centered on the game viewport within the skin
        if (loadingVisible && loadingText != null) {
            float cx = gameRect.left + gameRect.width() / 2f;
            float cy = gameRect.top + gameRect.height() / 2f;
            overlayTextPaint.setTextSize(Math.min(gameRect.height() * 0.12f, gameRect.width() * 0.25f));
            canvas.drawText(loadingText, cx, cy, overlayTextPaint);
        }
        if (exitingVisible && exitingText != null) {
            float cx = gameRect.left + gameRect.width() / 2f;
            float cy = gameRect.top + gameRect.height() / 2f;
            overlayTextPaint.setTextSize(Math.min(gameRect.height() * 0.12f, gameRect.width() * 0.25f));
            canvas.drawText(exitingText, cx, cy, overlayTextPaint);
        }
        if (pausedVisible && pausedText != null) {
            float cx = gameRect.left + gameRect.width() / 2f;
            float cy = gameRect.top + gameRect.height() / 2f;
            float maxSize = Math.min(gameRect.height() * 0.12f, gameRect.width() * 0.25f);
            overlayTextPaint.setTextSize(maxSize);
            float measured = overlayTextPaint.measureText(pausedText);
            float maxWidth = gameRect.width() * 0.9f;
            if (measured > maxWidth) {
                overlayTextPaint.setTextSize(maxSize * (maxWidth / measured));
            }
            canvas.drawText(pausedText, cx, cy, overlayTextPaint);
        }
        // Keep the status LED blinking even when the game presents a static frame. Wake up only
        // at the next edge (flash start or end) instead of polling continuously.
        if (!released && !fullScreen && batteryPercent >= 0) {
            postInvalidateDelayed(nextLedFrameDelayMs());
        }
        if (rotationAngle != 0) {
            canvas.restore();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        try {
            android.content.IntentFilter filter = new android.content.IntentFilter(
                    android.content.Intent.ACTION_BATTERY_CHANGED);
            batteryReceiver = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context context, android.content.Intent intent) {
                    int level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                    int scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
                    batteryPercent = (level >= 0 && scale > 0) ? level * 100 / scale : -1;
                }
            };
            android.content.Intent sticky = getContext().registerReceiver(batteryReceiver, filter);
            if (sticky != null) {
                batteryReceiver.onReceive(getContext(), sticky);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (batteryReceiver != null) {
            try { getContext().unregisterReceiver(batteryReceiver); } catch (Throwable ignored) { }
            batteryReceiver = null;
        }
        super.onDetachedFromWindow();
    }

    /** Draws the fake status LED over the skin's lens circle: blinking green
     *  above 30% battery, red at 30% or below. */
    private void drawSkinLed(Canvas canvas, float viewWidth, float viewHeight) {
        if (batteryPercent < 0) {
            return;
        }
        float vbW, vbH, lensX, lensY;
        if (viewWidth < viewHeight) {
            vbW = 400f; vbH = 800f; lensX = 292f; lensY = 55f;   // portrait lens
        } else {
            vbW = 800f; vbH = 400f; lensX = 704f; lensY = 49f;   // landscape lens
        }
        float sx = viewWidth / vbW;
        float sy = viewHeight / vbH;
        float cx = lensX * sx;
        float cy = lensY * sy;
        // The skin lens is a 7-unit circle with a 2-unit white ring, so the visible grey window
        // is the 6-unit inner radius. The skin bitmap is stretched to the view, so match it with
        // the per-axis scale (an ellipse) instead of a min-scale circle, which left a gap on the
        // axis that was scaled more.
        float radiusX = 6.15f * sx;
        float radiusY = 6.15f * sy;
        ledRect.set(cx - radiusX, cy - radiusY, cx + radiusX, cy + radiusY);
        if (!isLedBlinkOn()) {
            return;
        }
        int color = batteryPercent > 30 ? 0xFF2EFF6B : 0xFFFF3B30;
        // Hard-edged pulse: one short flash every five seconds, no fade.
        ledGlowPaint.setColor(color);
        ledGlowPaint.setAlpha(76);
        ledGlowRect.set(cx - radiusX * 2.1f, cy - radiusY * 2.1f,
                cx + radiusX * 2.1f, cy + radiusY * 2.1f);
        canvas.drawOval(ledGlowRect, ledGlowPaint);
        ledCorePaint.setColor(color);
        ledCorePaint.setAlpha(255);
        canvas.drawOval(ledRect, ledCorePaint);
    }

    private static boolean isLedBlinkOn() {
        return android.os.SystemClock.elapsedRealtime() % LED_BLINK_CYCLE_MS < LED_BLINK_ON_MS;
    }

    private static long nextLedFrameDelayMs() {
        long phase = android.os.SystemClock.elapsedRealtime() % LED_BLINK_CYCLE_MS;
        long delay = phase < LED_BLINK_ON_MS
                ? LED_BLINK_ON_MS - phase
                : LED_BLINK_CYCLE_MS - phase;
        return Math.max(16L, delay + 2L);
    }

    private void computeZones() {
        float w = rotated ? getHeight() : getWidth();
        float h = rotated ? getWidth() : getHeight();
        if (w >= h) {
            computeZonesLandscape();
        } else {
            computeZonesPortrait();
        }
    }

    private void computeZonesPortrait() {
        float viewWidth = rotated ? getHeight() : getWidth();
        float viewHeight = rotated ? getWidth() : getHeight();
        if (viewWidth <= 0f || viewHeight <= 0f) return;
        gameCornerRadiusX = 0f;
        gameCornerRadiusY = 0f;
        int gameW, gameH;
        synchronized (lock) {
            gameW = screenWidth > 0 ? screenWidth : 240;
            gameH = screenHeight > 0 ? screenHeight : 240;
        }
        boolean fullScreen = isFullScreenMode();
        boolean svgSkin = isSvgSkin(cachedSkin, false);
        if (svgSkin) {
            float vbW = 400f, vbH = 800f;
            float sx = viewWidth / vbW;
            float sy = viewHeight / vbH;
            // Fill the skin's screen aperture exactly so the game image reaches
            // the bezel on every device. The aperture is scaled non-uniformly
            // when the screen aspect differs from the skin viewBox, so
            // aspect-fitting would leave visible pillarbox gaps on the sides.
            float apertureLeft = 42f * sx;
            float apertureTop = 112f * sy;
            float apertureW = 316f * sx;
            float apertureH = 422f * sy;
            gameRect.set(apertureLeft, apertureTop, apertureLeft + apertureW, apertureTop + apertureH);
            gamePixelW = gameW;
            gamePixelH = gameH;
            if (fullScreen) {
                gameRect.set(0f, 0f, viewWidth, viewHeight);
            } else {
                gameCornerRadiusX = 11f * sx;
                gameCornerRadiusY = 11f * sy;
            }
            if (!keypadVisible) {
                padRect.set(0f, 0f, 0f, 0f);
                return;
            }
            padRect.set(24f * sx, 568f * sy, (24f + 352f) * sx, (568f + 208f) * sy);
        } else {
        float gameAreaW, gameAreaH;
        float padHeight;
        if (!keypadVisible) {
            gameAreaW = viewWidth;
            gameAreaH = viewHeight;
            padHeight = 0f;
        } else if (fullScreen) {
            gameAreaW = viewWidth;
            gameAreaH = viewHeight;
            padHeight = viewHeight * 0.26f;
        } else {
            padHeight = Math.max(viewHeight * 0.36f, viewWidth * 0.55f);
            if (padHeight > viewHeight * 0.5f) padHeight = viewHeight * 0.5f;
            gameAreaW = viewWidth;
            gameAreaH = viewHeight - padHeight;
        }
        float scale = Math.min(gameAreaW / gameW, gameAreaH / gameH);
        float dstW = gameW * scale;
        float dstH = gameH * scale;
        float gameLeft = (viewWidth - dstW) / 2f;
        float gameTop = (gameAreaH - dstH) / 2f;
        gameRect.set(gameLeft, gameTop, gameLeft + dstW, gameTop + dstH);
        gamePixelW = gameW;
        gamePixelH = gameH;
        if (fullScreen) gameRect.set(0f, 0f, viewWidth, viewHeight);

        if (!keypadVisible) {
            padRect.set(0f, 0f, 0f, 0f);
            // close svg else
        } else {
        float bandTop = viewHeight - padHeight;
        padRect.set(0f, bandTop, viewWidth, viewHeight);
        }
        } // end else svg
        // now key layout uses padRect
        if (padRect.height() <= 0f) {
            // skip key layout if no pad
        } else {
        float pad = Math.min(padRect.width(), padRect.height()) * 0.05f;
        float innerLeft = padRect.left + pad;
        float innerTop = padRect.top + pad;
        float innerRight = padRect.right - pad;
        float innerBottom = padRect.bottom - pad;
        float innerWidth = innerRight - innerLeft;
        float innerHeight = innerBottom - innerTop;
        if (innerWidth <= 0f || innerHeight <= 0f) return;

        float gap = innerWidth * 0.018f;
        float dpadWidth = innerWidth * 0.32f;
        float softWidth = innerWidth * 0.20f;
        float dialWidth = innerWidth - dpadWidth - softWidth - 2f * gap;

        float cell = Math.min(innerHeight / 3f, dpadWidth / 3f);
        float padCenterX = innerLeft + dpadWidth / 2f;
        float padCenterY = innerTop + innerHeight / 2f;
        float gridLeft = padCenterX - cell * 1.5f;
        float gridTop = padCenterY - cell * 1.5f;
        upKey.rect.set(gridLeft + cell, gridTop, gridLeft + 2f * cell, gridTop + cell);
        downKey.rect.set(gridLeft + cell, gridTop + 2f * cell, gridLeft + 2f * cell, gridTop + 3f * cell);
        leftKey.rect.set(gridLeft, gridTop + cell, gridLeft + cell, gridTop + 2f * cell);
        rightKey.rect.set(gridLeft + 2f * cell, gridTop + cell, gridLeft + 3f * cell, gridTop + 2f * cell);
        selectKey.rect.set(gridLeft + cell, gridTop + cell, gridLeft + 2f * cell, gridTop + 2f * cell);

        float softLeft = innerLeft + dpadWidth + gap;
        float cellHeight = innerHeight / 4f;
        float inset = Math.min(dialWidth / 3f, cellHeight) * 0.05f;
        KeyDef[] softColumn = {
                keysById.get(CTRL_SOFT1), keysById.get(CTRL_SOFT2),
                keysById.get(CTRL_MENU), keysById.get(CTRL_CLEAR),
        };
        for (int row = 0; row < softColumn.length; row++) {
            if (softColumn[row] == null) continue;
            softColumn[row].rect.set(softLeft, innerTop + row * cellHeight + inset,
                    softLeft + softWidth, innerTop + (row + 1) * cellHeight - inset);
        }

        float dialLeft = softLeft + softWidth + gap;
        float dialCellW = dialWidth / 3f;
        // Wider-than-tall number keys: cap the cell height and centre the 3x4 block vertically
        // in the pad band so the digits read as longer rectangles instead of squares.
        float dialCellH = Math.min(cellHeight, dialCellW / 1.22f);
        float dialTop = innerTop + (innerHeight - dialCellH * 4f) / 2f;
        inset = Math.min(dialCellW, dialCellH) * 0.015f;
        for (int i = 0; i < DIAL_LABELS.length; i++) {
            int col = i % 3;
            int row = i / 3;
            KeyDef def = keysById.get(CTRL_DIAL_BASE + i);
            if (def == null) continue;
            def.rect.set(dialLeft + col * dialCellW + inset, dialTop + row * dialCellH + inset,
                    dialLeft + (col + 1) * dialCellW - inset, dialTop + (row + 1) * dialCellH - inset);
        }
        }
    }

    private void computeZonesLandscape() {
        float viewWidth = rotated ? getHeight() : getWidth();
        float viewHeight = rotated ? getWidth() : getHeight();
        if (viewWidth <= 0f || viewHeight <= 0f) return;
        gameCornerRadiusX = 0f;
        gameCornerRadiusY = 0f;
        int gameW, gameH;
        synchronized (lock) {
            gameW = screenWidth > 0 ? screenWidth : 240;
            gameH = screenHeight > 0 ? screenHeight : 240;
        }
        boolean fullScreen = isFullScreenMode();
        boolean svgSkin = isSvgSkin(cachedSkin, true);
        if (svgSkin) {
            float vbW = 800f, vbH = 400f;
            float sx = viewWidth / vbW;
            float sy = viewHeight / vbH;
            // Fill the aperture exactly (same reasoning as portrait).
            float apertureLeft = 48f * sx;
            float apertureTop = 42f * sy;
            float apertureW = 422f * sx;
            float apertureH = 316f * sy;
            gameRect.set(apertureLeft, apertureTop, apertureLeft + apertureW, apertureTop + apertureH);
            gamePixelW = gameW;
            gamePixelH = gameH;
            if (fullScreen) {
                gameRect.set(0f, 0f, viewWidth, viewHeight);
            } else {
                gameCornerRadiusX = 11f * sx;
                gameCornerRadiusY = 11f * sy;
            }
            if (!keypadVisible) {
                padRect.set(0f, 0f, 0f, 0f);
            } else {
                padRect.set(514f * sx, 44f * sy, (514f + 256f) * sx, (44f + 304f) * sy);
            }
        } else {
        float rightW = Math.max(viewWidth * 0.24f, 180f * getResources().getDisplayMetrics().density);
        if (rightW > viewWidth * 0.35f) rightW = viewWidth * 0.35f;
        float gameAreaW = fullScreen ? viewWidth : (viewWidth - rightW);
        float gameAreaH = viewHeight;
        if (!keypadVisible) {
            gameAreaW = viewWidth;
            gameAreaH = viewHeight;
        }
        float scale = Math.min(gameAreaW / gameW, gameAreaH / gameH);
        float dstW = gameW * scale;
        float dstH = gameH * scale;
        float gameLeft = fullScreen ? 0f : (!keypadVisible ? (viewWidth - dstW) / 2f : Math.max(0f, (viewWidth - rightW - dstW) / 2f));
        float gameTop = (viewHeight - dstH) / 2f;
        gameRect.set(gameLeft, gameTop, gameLeft + dstW, gameTop + dstH);
        gamePixelW = gameW;
        gamePixelH = gameH;
        if (fullScreen) gameRect.set(0f, 0f, viewWidth, viewHeight);

        if (!keypadVisible) {
            padRect.set(0f, 0f, 0f, 0f);
        } else {
            padRect.set(viewWidth - rightW, 0f, viewWidth, viewHeight);
        }
        } // end svg else
        if (!keypadVisible) {
            // no keys
        } else {
        float density = getResources().getDisplayMetrics().density;
        float leftPad = 6f * density;
        float innerLeft = padRect.left + leftPad;
        float innerTop = padRect.top + padRect.height() * 0.10f + leftPad;
        float innerRight = padRect.right - leftPad;
        float innerBottom = padRect.bottom - leftPad;
        float innerW = innerRight - innerLeft;
        float innerH = innerBottom - innerTop;

        float softCellW = innerW / 2f;
        float softCellH = innerH * 0.12f;
        KeyDef[] softs = {
                keysById.get(CTRL_SOFT1), keysById.get(CTRL_SOFT2),
                keysById.get(CTRL_MENU), keysById.get(CTRL_CLEAR),
        };
        for (int i = 0; i < softs.length; i++) {
            if (softs[i] == null) continue;
            int col = i % 2;
            int row = i / 2;
            softs[i].rect.set(innerLeft + col * softCellW + 2f * density,
                    innerTop + row * softCellH + 2f * density,
                    innerLeft + (col + 1) * softCellW - 2f * density,
                    innerTop + (row + 1) * softCellH - 2f * density);
        }

        float dialTop = innerTop + 2f * softCellH + 4f * density;
        float dialH = innerBottom - dialTop;
        float cellW = innerW / 3f;
        float cellH = dialH / 4f;
        float inset = Math.min(cellW, cellH) * 0.06f;
        for (int i = 0; i < DIAL_LABELS.length; i++) {
            int col = i % 3;
            int row = i / 3;
            KeyDef def = keysById.get(CTRL_DIAL_BASE + i);
            if (def == null) continue;
            def.rect.set(innerLeft + col * cellW + inset, dialTop + row * cellH + inset,
                    innerLeft + (col + 1) * cellW - inset, dialTop + (row + 1) * cellH - inset);
        }

        float dpadSize = Math.min(viewWidth * 0.22f, viewHeight * 0.30f);
        float cx = viewWidth * 0.18f;
        float cy = viewHeight - dpadSize / 2f - 8f * density;
        float cell = dpadSize / 3f;
        float gridLeft = cx - cell * 1.5f;
        float gridTop = cy - cell * 1.5f;
        upKey.rect.set(gridLeft + cell, gridTop, gridLeft + 2f * cell, gridTop + cell);
        downKey.rect.set(gridLeft + cell, gridTop + 2f * cell, gridLeft + 2f * cell, gridTop + 3f * cell);
        leftKey.rect.set(gridLeft, gridTop + cell, gridLeft + cell, gridTop + 2f * cell);
        rightKey.rect.set(gridLeft + 2f * cell, gridTop + cell, gridLeft + 3f * cell, gridTop + 2f * cell);
        selectKey.rect.set(gridLeft + cell, gridTop + cell, gridLeft + 2f * cell, gridTop + 2f * cell);
    }
    }

    public static int getGameRotationAngle(android.content.Context context) {
        String gameId = EntryActivity.gameId;
        String orientation;
        android.content.SharedPreferences prefs = context.getSharedPreferences(DisplayActivity.PREFS_NAME, android.content.Context.MODE_PRIVATE);
        if (gameId != null && !gameId.isEmpty()) {
            String override = prefs.getString(gameId + "_" + DisplayActivity.KEY_ORIENTATION, DisplayActivity.INHERIT);
            if (!override.equals(DisplayActivity.INHERIT)) {
                orientation = override;
            } else {
                orientation = prefs.getString(DisplayActivity.KEY_ORIENTATION, DisplayActivity.ORIENTATION_PORTRAIT);
            }
        } else {
            orientation = prefs.getString(DisplayActivity.KEY_ORIENTATION, DisplayActivity.ORIENTATION_PORTRAIT);
        }
        boolean desiredPortrait = orientation.startsWith("portrait");
        boolean devicePortrait = context.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT;
        boolean reverse = orientation.endsWith("reverse");
        if (desiredPortrait != devicePortrait) {
            if (desiredPortrait) return reverse ? 90 : -90;
            return reverse ? -90 : 90;
        }
        if (reverse) return 180;
        return 0;
    }

    private int computeRotationAngle() {
        String gameId = EntryActivity.gameId;
        String orientation;
        if (gameId != null && !gameId.isEmpty()) {
            if (!gameId.equals(cachedSettingsGameId) || cachedOrientation == null) {
                android.content.SharedPreferences prefs = getContext().getSharedPreferences(DisplayActivity.PREFS_NAME, android.content.Context.MODE_PRIVATE);
                String override = prefs.getString(gameId + "_" + DisplayActivity.KEY_ORIENTATION, DisplayActivity.INHERIT);
                if (!override.equals(DisplayActivity.INHERIT)) {
                    cachedOrientation = override;
                } else {
                    cachedOrientation = prefs.getString(DisplayActivity.KEY_ORIENTATION, DisplayActivity.ORIENTATION_PORTRAIT);
                }
                cachedSettingsGameId = gameId;
            }
            orientation = cachedOrientation;
        } else {
            if (cachedSettingsGameId != null || cachedOrientation == null) {
                android.content.SharedPreferences prefs = getContext().getSharedPreferences(DisplayActivity.PREFS_NAME, android.content.Context.MODE_PRIVATE);
                cachedOrientation = prefs.getString(DisplayActivity.KEY_ORIENTATION, DisplayActivity.ORIENTATION_PORTRAIT);
                cachedSettingsGameId = gameId;
            }
            orientation = cachedOrientation;
        }
        boolean desiredPortrait = orientation.startsWith("portrait");
        boolean devicePortrait = getHeight() >= getWidth();
        boolean reverse = orientation.endsWith("reverse");
        if (desiredPortrait != devicePortrait) {
            if (desiredPortrait) return reverse ? 90 : -90;
            return reverse ? -90 : 90;
        }
        if (reverse) return 180;
        return 0;
    }

    private boolean isFullScreenMode() {
        String gameId = EntryActivity.gameId;
        String mode;
        if (gameId != null && !gameId.isEmpty()) {
            if (!gameId.equals(cachedSettingsGameId) || cachedDisplayMode == null) {
                // orientation cached above may have set cachedSettingsGameId; read mode now
                android.content.SharedPreferences prefs = getContext().getSharedPreferences(DisplayActivity.PREFS_NAME, android.content.Context.MODE_PRIVATE);
                String override = prefs.getString(gameId + "_" + DisplayActivity.KEY_DISPLAY_MODE, DisplayActivity.INHERIT);
                if (!override.equals(DisplayActivity.INHERIT)) {
                    cachedDisplayMode = override;
                } else {
                    cachedDisplayMode = prefs.getString(DisplayActivity.KEY_DISPLAY_MODE, DisplayActivity.MODE_ORIGINAL);
                }
            }
            mode = cachedDisplayMode;
        } else {
            if (cachedSettingsGameId != null || cachedDisplayMode == null) {
                android.content.SharedPreferences prefs = getContext().getSharedPreferences(DisplayActivity.PREFS_NAME, android.content.Context.MODE_PRIVATE);
                cachedDisplayMode = prefs.getString(DisplayActivity.KEY_DISPLAY_MODE, DisplayActivity.MODE_ORIGINAL);
                cachedSettingsGameId = gameId;
            }
            mode = cachedDisplayMode;
        }
        return DisplayActivity.MODE_FULLSCREEN.equals(mode);
    }

    private String currentFilterId() {
        String gameId = EntryActivity.gameId;
        String filter;
        if (gameId != null && !gameId.isEmpty()) {
            if (!gameId.equals(cachedSettingsGameId) || cachedFilter == null) {
                android.content.SharedPreferences prefs = getContext().getSharedPreferences(DisplayActivity.PREFS_NAME, android.content.Context.MODE_PRIVATE);
                String override = prefs.getString(gameId + "_" + DisplayActivity.KEY_FILTER, DisplayActivity.INHERIT);
                if (!override.equals(DisplayActivity.INHERIT)) {
                    cachedFilter = override;
                } else {
                    cachedFilter = prefs.getString(DisplayActivity.KEY_FILTER, GameFilters.ID_NONE);
                }
                cachedSettingsGameId = gameId;
            }
            filter = cachedFilter;
        } else {
            if (cachedSettingsGameId != null || cachedFilter == null) {
                android.content.SharedPreferences prefs = getContext().getSharedPreferences(DisplayActivity.PREFS_NAME, android.content.Context.MODE_PRIVATE);
                cachedFilter = prefs.getString(DisplayActivity.KEY_FILTER, GameFilters.ID_NONE);
                cachedSettingsGameId = gameId;
            }
            filter = cachedFilter;
        }
        return filter;
    }

    /**
     * Applies the configured display filter to the frame at native resolution,
     * if any. The result is cached per source version so the filter only runs
     * when the game presents a new frame.
     */
    private Bitmap applyDisplayFilter(Bitmap bmp, int srcWidth, int srcHeight, int sourceVersion) {
        String filterId = currentFilterId();
        if (filterId == null || GameFilters.ID_NONE.equals(filterId)) {
            return bmp;
        }
        if (filterInstance == null || !filterId.equals(filterAppliedId)) {
            filterInstance = GameFilters.create(filterId);
            filterAppliedId = filterId;
            filterVersion = -1;
        }
        if (filterInstance == null) {
            return bmp;
        }
        Bitmap out = filterBitmap;
        if (out == null || out.isRecycled() || out.getWidth() != srcWidth || out.getHeight() != srcHeight) {
            out = Bitmap.createBitmap(srcWidth, srcHeight, Bitmap.Config.ARGB_8888);
            filterBitmap = out;
            filterPixels = null;
            filterVersion = -1;
        }
        if (filterVersion != sourceVersion) {
            int length = srcWidth * srcHeight;
            int[] px = filterPixels;
            if (px == null || px.length < length) {
                px = new int[length];
                filterPixels = px;
            }
            Bitmap input = bmp.getConfig() == Bitmap.Config.ARGB_8888 ? bmp
                    : bmp.copy(Bitmap.Config.ARGB_8888, false);
            input.getPixels(px, 0, srcWidth, 0, 0, srcWidth, srcHeight);
            filterInstance.apply(px, srcWidth, srcHeight);
            out.setPixels(px, 0, srcWidth, 0, 0, srcWidth, srcHeight);
            filterVersion = sourceVersion;
        }
        return out;
    }

    /**
     * Returns a stable, opaque copy of a presented frame. Called on the thread
     * that produced the frame, before the emulator can rotate its presentation
     * buffers, so the copy is always a whole frame.
     */
    private Bitmap snapshotForDisplay(Bitmap source, int sourceVersion) {
        int width = source.getWidth();
        int height = source.getHeight();
        if (width <= 0 || height <= 0) {
            return source;
        }
        if (stableDisplayFrame != null && stableDisplayVersion == sourceVersion
                && stableDisplayFrame.getWidth() == width && stableDisplayFrame.getHeight() == height) {
            return stableDisplayFrame;
        }
        int length = width * height;
        int[] px = displaySnapshotPixels;
        if (px == null || px.length != length) {
            px = new int[length];
            displaySnapshotPixels = px;
        }
        synchronized (opendoja.host.DesktopSurface.PRESENTATION_COPY_LOCK) {
            source.getPixels(px, 0, width, 0, 0, width, height);
        }
        for (int i = 0; i < length; i++) {
            px[i] |= 0xFF000000;
        }
        displaySnapshotIndex = (displaySnapshotIndex + 1) % DISPLAY_SNAPSHOT_COUNT;
        Bitmap snapshot = displaySnapshots[displaySnapshotIndex];
        if (snapshot == null || snapshot.getWidth() != width || snapshot.getHeight() != height) {
            snapshot = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            displaySnapshots[displaySnapshotIndex] = snapshot;
        }
        snapshot.setHasAlpha(false);
        snapshot.setPixels(px, 0, width, 0, 0, width, height);
        stableDisplayFrame = snapshot;
        stableDisplayVersion = sourceVersion;
        return snapshot;
    }

    private void drawGameUpscaled(Canvas canvas, Bitmap bmp, int sourceVersion) {
        int dstLeft = Math.round(gameRect.left);
        int dstTop = Math.round(gameRect.top);
        int dstWidth = Math.max(1, Math.round(gameRect.width()));
        int dstHeight = Math.max(1, Math.round(gameRect.height()));
        int srcWidth = bmp.getWidth();
        int srcHeight = bmp.getHeight();
        if (srcWidth <= 0 || srcHeight <= 0) return;
        Bitmap src = applyDisplayFilter(bmp, srcWidth, srcHeight, sourceVersion);
        if (upscalePaint == null) {
            upscalePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
            upscalePaint.setDither(true);
        }
        boolean clipRounded = gameCornerRadiusX > 0f && gameCornerRadiusY > 0f;
        if (clipRounded) {
            // Match the skin's screen opening so the game image shows rounded
            // corners where the skin's bezel curves (Original mode only).
            canvas.save();
            gameClipPath.rewind();
            gameClipPath.addRoundRect(gameRect, gameCornerRadiusX, gameCornerRadiusY,
                    android.graphics.Path.Direction.CW);
            canvas.clipPath(gameClipPath);
        }
        // Scale straight onto the (hardware-accelerated) view canvas so the GPU does the
        // bilinear filtered upscale. The previous code first scaled the whole frame into an
        // intermediate ARGB_8888 bitmap with a software Canvas every frame, which cost a full
        // frame-size CPU scale on high-DPI devices and showed up as in-game stutter/heat.
        if (dstWidth == srcWidth && dstHeight == srcHeight) {
            canvas.drawBitmap(src, dstLeft, dstTop, upscalePaint);
        } else {
            upscaleSrcRect.set(0, 0, srcWidth, srcHeight);
            upscaleDstRectF.set(dstLeft, dstTop, dstLeft + dstWidth, dstTop + dstHeight);
            canvas.drawBitmap(src, upscaleSrcRect, upscaleDstRectF, upscalePaint);
        }
        if (clipRounded) {
            canvas.restore();
        }
    }

    private void drawKeypad(Canvas canvas) {
        if (padRect.height() <= 0f) return;
        for (KeyDef def : keys) {
            if (!dpadVisible && isLandscapeOrientation() && (def.id == CTRL_UP || def.id == CTRL_DOWN || def.id == CTRL_LEFT || def.id == CTRL_RIGHT || def.id == CTRL_SELECT)) {
                continue;
            }
            drawKey(canvas, def);
        }
    }

    private void drawKey(Canvas canvas, KeyDef def) {
        RectF rect = def.rect;
        if (rect.width() <= 0f || rect.height() <= 0f) return;
        boolean pressed = activeControls.contains(def.id);
        if (controlsSkin != null) {
            String artId = skinIdForControl(def.id);
            if (artId != null) {
                boolean landscape = isLandscapeOrientation();
                int alpha = (artId.startsWith("dpad-") && landscape) ? 128 : 255;
                boolean stretch = !landscape && isDialArt(artId);
                if (controlsSkin.draw(canvas, artId, rect, pressed, alpha, stretch)) {
                    return;
                }
            }
        }
        float radius = Math.min(rect.width(), rect.height()) * 0.18f;
        if (def.id == CTRL_UP || def.id == CTRL_DOWN || def.id == CTRL_LEFT
                || def.id == CTRL_RIGHT || def.id == CTRL_SELECT) {
            keyFillPaint.setColor(pressed ? 0xCC505050 : 0x99303030);
            keyStrokePaint.setColor(0xCCFFFFFF);
        } else if (pressed) {
            keyFillPaint.setColor(0x99FFFF90);
            keyStrokePaint.setColor(0xFFFFFFFF);
        } else {
            keyFillPaint.setColor(0x99303030);
            keyStrokePaint.setColor(0xCCFFFFFF);
        }
        canvas.drawRoundRect(rect, radius, radius, keyFillPaint);
        canvas.drawRoundRect(rect, radius, radius, keyStrokePaint);
        keyTextPaint.setTextSize(Math.min(rect.height() * 0.42f, rect.width() * 0.5f));
        float measured = keyTextPaint.measureText(def.label);
        if (measured > rect.width() * 0.9f && measured > 0f) {
            keyTextPaint.setTextSize(keyTextPaint.getTextSize() * (rect.width() * 0.9f / measured));
        }
        if (keyTextPaint.getTextSize() < 8f) keyTextPaint.setTextSize(8f);
        canvas.drawText(def.label, rect.centerX(),
                rect.centerY() + keyTextPaint.getTextSize() * 0.34f, keyTextPaint);
    }

    private static boolean isDialArt(String artId) {
        if (artId == null || !artId.startsWith("key-")) {
            return false;
        }
        String tail = artId.substring(4);
        return tail.length() == 1 || "star".equals(tail) || "hash".equals(tail);
    }

    private String skinIdForControl(int id) {
        switch (id) {
            case CTRL_SOFT1: return "key-soft1";
            case CTRL_SOFT2: return "key-soft2";
            case CTRL_MENU: return "key-menu";
            case CTRL_CLEAR: return "key-clear";
            case CTRL_UP: return "dpad-up";
            case CTRL_DOWN: return "dpad-down";
            case CTRL_LEFT: return "dpad-left";
            case CTRL_RIGHT: return "dpad-right";
            case CTRL_SELECT: return "dpad-center";
            default:
                break;
        }
        if (id >= CTRL_DIAL_BASE && id < CTRL_DIAL_BASE + DIAL_LABELS.length) {
            String label = DIAL_LABELS[id - CTRL_DIAL_BASE];
            if ("*".equals(label)) return "key-star";
            if ("#".equals(label)) return "key-hash";
            return "key-" + label;
        }
        return null;
    }

    private int hitTest(float x, float y) {
        if (!dpadVisible && isLandscapeOrientation()) {
            // skip dpad keys
        } else {
            if (selectKey.rect.contains(x, y)) return CTRL_SELECT;
        }
        for (KeyDef def : keys) {
            if (!dpadVisible && isLandscapeOrientation() && (def.id == CTRL_UP || def.id == CTRL_DOWN || def.id == CTRL_LEFT || def.id == CTRL_RIGHT || def.id == CTRL_SELECT)) {
                continue;
            }
            if (def.rect.contains(x, y)) return def.id;
        }
        return CTRL_NONE;
    }

    private int[] toCanvas(float x, float y, boolean clampToGame) {
        if (gamePixelW <= 0 || gamePixelH <= 0 || gameRect.width() <= 0f || gameRect.height() <= 0f) return null;
        float fx = (x - gameRect.left) * gamePixelW / gameRect.width();
        float fy = (y - gameRect.top) * gamePixelH / gameRect.height();
        if (!clampToGame && (fx < 0f || fy < 0f || fx >= gamePixelW || fy >= gamePixelH)) return null;
        int cx = (int) Math.floor(fx);
        int cy = (int) Math.floor(fy);
        if (cx < 0) cx = 0;
        if (cy < 0) cy = 0;
        if (cx >= gamePixelW) cx = gamePixelW - 1;
        if (cy >= gamePixelH) cy = gamePixelH - 1;
        return new int[]{cx, cy};
    }

    private int controlToKeyCode(int control) {
        switch (control) {
            case CTRL_UP: return Display.KEY_UP;
            case CTRL_DOWN: return Display.KEY_DOWN;
            case CTRL_LEFT: return Display.KEY_LEFT;
            case CTRL_RIGHT: return Display.KEY_RIGHT;
            case CTRL_SELECT: return Display.KEY_SELECT;
            case CTRL_SOFT1: return Frame.SOFT_KEY_1;
            case CTRL_SOFT2: return Frame.SOFT_KEY_2;
            case CTRL_MENU: return Display.KEY_MENU;
            case CTRL_CLEAR: return Display.KEY_CLEAR;
        }
        if (control >= CTRL_DIAL_BASE && control < CTRL_DIAL_BASE + DIAL_KEYS.length) {
            return DIAL_KEYS[control - CTRL_DIAL_BASE];
        }
        return -1;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        computeZones();
        if (isLandscapeOrientation()) {
            dpadVisible = true;
            lastDpadActivityMs = android.os.SystemClock.elapsedRealtime();
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int index = event.getActionIndex();
                float x = touchX(event, index);
                float y = touchY(event, index);
                int control = keypadVisible ? hitTest(x, y) : CTRL_NONE;
                if (control != CTRL_NONE) {
                    updatePointer(event.getPointerId(index), control);
                    return true;
                }
                if (pointerFinger == null && toCanvas(x, y, false) != null) {
                    pressGamePointer(event.getPointerId(index), x, y);
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_MOVE: {
                boolean handled = false;
                for (int i = 0; i < event.getPointerCount(); i++) {
                    int pointerId = event.getPointerId(i);
                    float x = touchX(event, i);
                    float y = touchY(event, i);
                    if (pointerControls.containsKey(pointerId)) {
                        updatePointer(pointerId, hitTest(x, y));
                        handled = true;
                    } else if (pointerFinger != null && pointerId == pointerFinger) {
                        moveGamePointer(pointerId, x, y);
                        handled = true;
                    }
                }
                return handled;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                int index = event.getActionIndex();
                int pointerId = event.getPointerId(index);
                if (pointerFinger != null && pointerId == pointerFinger) {
                    releaseGamePointer(pointerId, touchX(event, index), touchY(event, index));
                } else {
                    updatePointer(pointerId, CTRL_NONE);
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                int pointerId = event.getPointerId(0);
                if (pointerFinger != null && pointerId == pointerFinger) {
                    releaseGamePointer(pointerId, touchX(event, 0), touchY(event, 0));
                } else {
                    updatePointer(pointerId, CTRL_NONE);
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                releasePointers();
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    private void updatePointer(int pointerId, int control) {
        Integer previous = pointerControls.get(pointerId);
        if (previous != null && previous != CTRL_NONE && previous != control) {
            activeControls.remove(previous);
            if (input != null) {
                int prevKey = controlToKeyCode(previous);
                if (prevKey >= 0) {
                    if (previous == CTRL_SOFT1 || previous == CTRL_SOFT2) {
                        int softKey = (previous == CTRL_SOFT1) ? Frame.SOFT_KEY_1 : Frame.SOFT_KEY_2;
                        input.releaseSoft(softKey);
                    } else {
                        input.releaseDojaKey(prevKey);
                    }
                }
            }
        }
        if (control == CTRL_NONE) {
            if (previous != null && previous != CTRL_NONE) {
                activeControls.remove(previous);
                if (input != null) {
                    int prevKey = controlToKeyCode(previous);
                    if (prevKey >= 0) {
                        if (previous == CTRL_SOFT1 || previous == CTRL_SOFT2) {
                            int softKey = (previous == CTRL_SOFT1) ? Frame.SOFT_KEY_1 : Frame.SOFT_KEY_2;
                            input.releaseSoft(softKey);
                        } else {
                            input.releaseDojaKey(prevKey);
                        }
                    }
                }
            }
            pointerControls.remove(pointerId);
        } else {
            pointerControls.put(pointerId, control);
            activeControls.add(control);
            if (input != null) {
                int keyCode = controlToKeyCode(control);
                    if (keyCode >= 0) {
                        if (control == CTRL_SOFT1 || control == CTRL_SOFT2) {
                        int softKey = (control == CTRL_SOFT1) ? Frame.SOFT_KEY_1 : Frame.SOFT_KEY_2;
                        input.pressSoft(softKey);
                    } else {
                        input.pressDojaKey(keyCode);
                    }
                }
            }
        }
        postInvalidate();
    }

    private void pressGamePointer(int pointerId, float x, float y) {
        pointerFinger = pointerId;
        lastPointerX = (int) x;
        lastPointerY = (int) y;
        int[] canvas = toCanvas(x, y, true);
        if (canvas != null && input != null) {
            input.pressPointer(canvas[0], canvas[1]);
        }
    }

    private void moveGamePointer(int pointerId, float x, float y) {
        lastPointerX = (int) x;
        lastPointerY = (int) y;
        int[] canvas = toCanvas(x, y, true);
        if (canvas != null && input != null) {
            input.movePointer(canvas[0], canvas[1]);
        }
    }

    private void releaseGamePointer(int pointerId, float x, float y) {
        pointerFinger = null;
        int[] canvas = toCanvas(x, y, true);
        if (canvas != null && input != null) {
            input.releasePointer(canvas[0], canvas[1]);
        }
    }

    private void releasePointers() {
        for (int control : new ArrayList<>(activeControls)) {
            if (input != null) {
                int keyCode = controlToKeyCode(control);
                    if (keyCode >= 0) {
                        if (control == CTRL_SOFT1 || control == CTRL_SOFT2) {
                        int softKey = (control == CTRL_SOFT1) ? Frame.SOFT_KEY_1 : Frame.SOFT_KEY_2;
                        input.releaseSoft(softKey);
                    } else {
                        input.releaseDojaKey(keyCode);
                    }
                }
            }
        }
        activeControls.clear();
        pointerControls.clear();
        if (pointerFinger != null) {
            pointerFinger = null;
            if (input != null) input.releasePointer(lastPointerX, lastPointerY);
        }
        postInvalidate();
    }

    private android.graphics.Bitmap getSkinBitmap(String skin, boolean landscape, int w, int h) {
        if (skin == null || w <= 0 || h <= 0) return null;
        String orient = landscape ? "landscape" : "portrait";
        String key = skin + "|" + orient + "|" + w + "x" + h;
        if (key.equals(cachedSkinBitmapId)) return cachedSkinBitmap;
        if (skinRenderPending && key.equals(pendingSkinKey)) return cachedSkinBitmap;
        // Try immediate PNG first.
        try {
            java.io.InputStream is = getContext().getAssets().open("skins/" + skin + "-" + orient + ".png");
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(is);
            is.close();
            if (bmp != null) {
                cachedSkinBitmap = bmp;
                cachedSkinBitmapId = key;
                return bmp;
            }
        } catch (Exception ignored) {
        }
        // Try SVG via WebView (async). Return null (fallback) until rendered.
        String svgAsset = "skins/" + skin + "-" + orient + ".svg";
        try {
            java.io.InputStream is = getContext().getAssets().open(svgAsset);
            is.close();
        } catch (Exception e) {
            cachedSkinBitmap = null;
            cachedSkinBitmapId = key;
            return null;
        }
        skinRenderPending = true;
        pendingSkinKey = key;
        rasterizeSvg(skin, orient, w, h, key);
        return null;
    }

    private void rasterizeSvg(String skin, String orient, int w, int h, String key) {
        String svg;
        try (java.io.InputStream is = getContext().getAssets().open("skins/" + skin + "-" + orient + ".svg")) {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
            svg = baos.toString("UTF-8");
            svg = svg.replace("preserveAspectRatio=\"xMidYMid meet\"", "preserveAspectRatio=\"none\"");
        } catch (Exception e) {
            skinRenderPending = false;
            cachedSkinBitmap = null;
            cachedSkinBitmapId = key;
            return;
        }
        android.view.ViewGroup rootVar = null;
        try {
            rootVar = (android.view.ViewGroup) ((android.app.Activity) getContext()).findViewById(android.R.id.content);
        } catch (Throwable ignored) {
        }
        final android.view.ViewGroup root = rootVar;
        android.webkit.WebView wv = new android.webkit.WebView(getContext());
        if (root != null) {
            wv.setAlpha(0f);
            root.addView(wv, new android.widget.FrameLayout.LayoutParams(w, h));
        }
        wv.setBackgroundColor(0xFF000000);
        wv.getSettings().setJavaScriptEnabled(false);
        wv.setWebViewClient(new android.webkit.WebViewClient() {
            @Override
            public void onPageFinished(android.webkit.WebView view, String url) {
                view.postDelayed(() -> {
                    try {
                        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
                        android.graphics.Canvas c = new android.graphics.Canvas(bmp);
                        view.draw(c);
                        cachedSkinBitmap = bmp;
                        cachedSkinBitmapId = key;
                    } catch (Throwable ignored) {
                        cachedSkinBitmap = null;
                        cachedSkinBitmapId = key;
                    }
                    skinRenderPending = false;
                    try {
                        if (root != null) root.removeView(view);
                    } catch (Throwable ignored) {
                    }
                    postInvalidate();
                }, 150);
            }
        });
        wv.layout(0, 0, w, h);
        wv.loadDataWithBaseURL(null, svg, "image/svg+xml", "UTF-8", null);
    }

    private boolean isSvgSkin(String skin, boolean landscape) {
        if (skin == null) return false;
        String key = skin + (landscape ? "|L" : "|P");
        if (key.equals(cachedSvgSkinKey)) {
            return cachedSvgSkinResult;
        }
        String orient = landscape ? "landscape" : "portrait";
        boolean result;
        try {
            getContext().getAssets().open("skins/" + skin + "-" + orient + ".svg").close();
            result = true;
        } catch (Exception e) {
            result = false;
        }
        cachedSvgSkinKey = key;
        cachedSvgSkinResult = result;
        return result;
    }

    private boolean isLandscapeOrientation() {
        Boolean cached = cachedLandscape;
        if (cached != null) {
            return cached;
        }
        String gameId = EntryActivity.gameId;
        android.content.SharedPreferences prefs = getContext().getSharedPreferences(DisplayActivity.PREFS_NAME, android.content.Context.MODE_PRIVATE);
        String orientation;
        if (gameId != null && !gameId.isEmpty()) {
            String override = prefs.getString(gameId + "_" + DisplayActivity.KEY_ORIENTATION, DisplayActivity.INHERIT);
            orientation = override.equals(DisplayActivity.INHERIT) ? prefs.getString(DisplayActivity.KEY_ORIENTATION, DisplayActivity.ORIENTATION_PORTRAIT) : override;
        } else {
            orientation = prefs.getString(DisplayActivity.KEY_ORIENTATION, DisplayActivity.ORIENTATION_PORTRAIT);
        }
        boolean landscape = orientation.startsWith("landscape");
        cachedLandscape = landscape;
        return landscape;
    }

    private int skinColor(String skin) {
        if (skin == null) return 0xFF202020;
        switch (skin) {
            case "blue": return 0xFF1E88E5;
            case "red": return 0xFFE53935;
            case "green": return 0xFF43A047;
            case "orange": return 0xFFFB8C00;
            case "silver":
            default: return 0xFFB0BEC5;
        }
    }
}
