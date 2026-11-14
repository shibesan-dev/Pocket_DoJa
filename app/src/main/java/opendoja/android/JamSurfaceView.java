package opendoja.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import com.nttdocomo.ui.Display;
import com.nttdocomo.ui.Frame;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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

    private static final int GROUP_DPAD = 0;
    private static final int GROUP_DIAL = 1;
    private static final int GROUP_SOFT = 2;
    private static final int GROUP_COUNT = 3;
    private static final String[] GROUP_PREF_NAMES = {"dpad", "dial", "soft"};

    private static final int EDIT_TOOL_MOVE = 0;
    private static final int EDIT_TOOL_RESIZE = 1;
    private static final int EDIT_TOOL_HIDE = 2;
    private static final int EDIT_TOOL_RESET = 3;
    private static final int EDIT_TOOL_OK = 4;
    private static final int EDIT_TOOL_CANCEL = 5;
    private static final int EDIT_TOOL_COUNT = 6;
    private static final int EDIT_FOCUS_TOOLBAR = 0;
    private static final int EDIT_FOCUS_GROUP = 1;
    private static final int EDIT_FOCUS_ADJUST = 2;
    private static final int[] EDIT_TOOL_ICONS = {
            R.drawable.ic_button_edit_move,
            R.drawable.ic_button_edit_resize,
            R.drawable.ic_button_edit_hide,
            R.drawable.ic_button_edit_reset,
            R.drawable.ic_button_edit_ok,
            R.drawable.ic_button_edit_cancel,
    };

    private static final float BUTTON_SCALE_MIN = 0.5f;
    private static final float BUTTON_SCALE_MAX = 2.0f;
    private static final String BUTTON_PREF_PREFIX = "buttons";

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

    /** Per-game transform for one on-screen control set. Offsets are fractions
     *  of the logical view size so they survive resolution changes. */
    static class ButtonLayout {
        float dx;
        float dy;
        float scale = 1f;
        boolean hidden;

        void reset() {
            dx = 0f;
            dy = 0f;
            scale = 1f;
            hidden = false;
        }

        void copyFrom(ButtonLayout other) {
            dx = other.dx;
            dy = other.dy;
            scale = other.scale;
            hidden = other.hidden;
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
    private final Paint letterboxPaint = new Paint();

    // "Edit Buttons" overlay paints. The grid and group highlight are drawn on
    // top of the normal keypad; the toolbar replaces nothing, so the game frame
    // stays visible behind it while editing.
    private final Paint editGridPaint = new Paint();
    private final Paint editHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint editSelectionFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint editToolbarBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint editToolFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint editToolBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint editLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint editSliderTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint editSliderKnobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint editFocusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint editHiddenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Bitmap upscaleBitmap;
    private Canvas upscaleCanvas;
    private Paint upscalePaint;
    private static final int DISPLAY_SNAPSHOT_COUNT = 3;
    private final Bitmap[] displaySnapshots = new Bitmap[DISPLAY_SNAPSHOT_COUNT];
    private final Canvas[] displaySnapshotCanvases = new Canvas[DISPLAY_SNAPSHOT_COUNT];
    private final Paint displaySnapshotPaint = new Paint();
    private int displaySnapshotIndex;
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
    /** Screen opening that the game is letterboxed inside (skin/fullscreen). */
    private final RectF apertureRect = new RectF();
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
    private String cachedGameDisplay;
    private String cachedGameDisplayId;
    private String cachedFilter;

    // FPS overlay (Settings -> Display -> Show FPS): measured from presented
    // game frames, drawn in the top-right corner of the game screen.
    private static final long FPS_SAMPLE_WINDOW_NANOS = 1_000_000_000L;
    private static final long FPS_STALE_AFTER_NANOS = 1_500_000_000L;
    private volatile boolean showFps;
    private final Object fpsLock = new Object();
    private final ArrayDeque<Long> fpsFrameTimes = new ArrayDeque<>();
    private long fpsLastFrameNanos;
    private final Paint fpsTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fpsBackgroundPaint = new Paint();

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

    // ---- Edit Buttons state (per game) ----
    private final ButtonLayout[] savedButtonLayouts = {
            new ButtonLayout(), new ButtonLayout(), new ButtonLayout(),
    };
    private final ButtonLayout[] editButtonLayouts = {
            new ButtonLayout(), new ButtonLayout(), new ButtonLayout(),
    };
    private final RectF[] groupBaseBounds = {new RectF(), new RectF(), new RectF()};
    private final RectF[] groupBounds = {new RectF(), new RectF(), new RectF()};
    private final RectF[] editToolbarRects = {
            new RectF(), new RectF(), new RectF(), new RectF(), new RectF(), new RectF(),
    };
    private final RectF editToolbarPanel = new RectF();
    private final RectF editResizePanel = new RectF();
    private final RectF editResizeTrack = new RectF();
    private final RectF editResizeKnob = new RectF();
    private Drawable[] editToolIcons;
    private String buttonLayoutGameId;
    private boolean buttonLayoutsLoaded;
    private boolean buttonEditMode;
    private boolean keypadVisibleBeforeButtonEdit;
    private int editTool = EDIT_TOOL_MOVE;
    private int editSelectedGroup = -1;
    private int editFocusedTool = EDIT_TOOL_MOVE;
    private int editFocusedGroup = 0;
    private int editFocusArea = EDIT_FOCUS_TOOLBAR;
    private int editPressGroup = -1;
    private int editPointerId = -1;
    private boolean editPressWasSelected;
    private boolean editPressMoved;
    private boolean editSliderDragging;
    private float editPressStartX;
    private float editPressStartY;
    private float editDragStartDx;
    private float editDragStartDy;
    private float editIconSize;

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
        if (showFps) {
            recordPresentedFrame();
        }
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

    /** Enables the lime-green FPS counter in the top-right corner of the game screen. */
    public void setShowFps(boolean enabled) {
        if (showFps == enabled) {
            return;
        }
        showFps = enabled;
        synchronized (fpsLock) {
            fpsFrameTimes.clear();
            fpsLastFrameNanos = 0L;
        }
        postInvalidate();
    }

    public boolean isShowFps() {
        return showFps;
    }

    private void recordPresentedFrame() {
        long now = System.nanoTime();
        synchronized (fpsLock) {
            fpsLastFrameNanos = now;
            fpsFrameTimes.addLast(now);
            trimFpsFrameTimes(now);
        }
    }

    private void trimFpsFrameTimes(long now) {
        while (!fpsFrameTimes.isEmpty()
                && now - fpsFrameTimes.peekFirst() > FPS_SAMPLE_WINDOW_NANOS) {
            fpsFrameTimes.removeFirst();
        }
    }

    private double currentFps(long now) {
        if (fpsLastFrameNanos == 0L || now - fpsLastFrameNanos > FPS_STALE_AFTER_NANOS) {
            return 0.0;
        }
        if (fpsFrameTimes.size() >= 2) {
            long first = fpsFrameTimes.peekFirst();
            long last = fpsFrameTimes.peekLast();
            if (last > first) {
                return ((fpsFrameTimes.size() - 1) * 1_000_000_000.0) / (last - first);
            }
        }
        if (fpsFrameTimes.size() == 1) {
            return Math.min(999.0, 1_000_000_000.0 / Math.max(1L, now - fpsFrameTimes.peekFirst()));
        }
        return 0.0;
    }

    private void drawFpsOverlay(Canvas canvas) {
        if (gameRect.width() <= 0f || gameRect.height() <= 0f) {
            return;
        }
        String label;
        synchronized (fpsLock) {
            long now = System.nanoTime();
            trimFpsFrameTimes(now);
            label = String.format(Locale.ROOT, "%.1f FPS", currentFps(now));
        }
        float textSize = Math.max(10f, Math.min(20f, gameRect.height() * 0.065f));
        fpsTextPaint.setTextSize(textSize);
        float textWidth = fpsTextPaint.measureText(label);
        float margin = Math.max(3f, textSize * 0.3f);
        float padH = Math.max(3f, textSize * 0.28f);
        float padV = Math.max(2f, textSize * 0.16f);
        float x = gameRect.right - margin - textWidth;
        if (x < gameRect.left + margin) {
            x = gameRect.left + margin;
        }
        float baseline = gameRect.top + margin + textSize;
        // Solid dark backdrop keeps the counter legible over bright game HUD
        // text. 60% black is dark enough without hiding the scene completely.
        float bgLeft = Math.max(gameRect.left, x - padH);
        float bgTop = Math.max(gameRect.top, baseline - textSize - padV);
        float bgRight = Math.min(gameRect.right, x + textWidth + padH);
        float bgBottom = Math.min(gameRect.bottom, baseline + textSize * 0.25f + padV);
        canvas.drawRect(bgLeft, bgTop, bgRight, bgBottom, fpsBackgroundPaint);
        canvas.drawText(label, x, baseline, fpsTextPaint);
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
        displaySnapshotPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
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
        fpsTextPaint.setColor(0xFFB6FF00);
        fpsTextPaint.setTextAlign(Paint.Align.LEFT);
        fpsTextPaint.setTypeface(android.graphics.Typeface.create(
                android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD));
        fpsBackgroundPaint.setColor(0x99000000);

        editGridPaint.setColor(0x557DFF5A);
        editGridPaint.setStyle(Paint.Style.STROKE);
        editGridPaint.setStrokeWidth(1f);
        editHighlightPaint.setStyle(Paint.Style.STROKE);
        editHighlightPaint.setColor(0xFFFFD600);
        editSelectionFillPaint.setStyle(Paint.Style.FILL);
        editSelectionFillPaint.setColor(0x2EFFD600);
        editToolbarBgPaint.setColor(0xB3121212);
        editToolFillPaint.setStyle(Paint.Style.FILL);
        editToolBorderPaint.setStyle(Paint.Style.STROKE);
        editToolBorderPaint.setStrokeWidth(2f);
        editLabelPaint.setColor(0xFFFFFFFF);
        editLabelPaint.setTextAlign(Paint.Align.CENTER);
        editLabelPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        editLabelPaint.setAntiAlias(true);
        editSliderTrackPaint.setStyle(Paint.Style.FILL);
        editSliderTrackPaint.setColor(0xFF444444);
        editSliderKnobPaint.setStyle(Paint.Style.FILL);
        editSliderKnobPaint.setColor(0xFFFFD600);
        editFocusPaint.setStyle(Paint.Style.STROKE);
        editFocusPaint.setColor(0xFF4DA3FF);
        editHiddenPaint.setStyle(Paint.Style.STROKE);
        editHiddenPaint.setColor(0xAAFF7043);
        editToolIcons = new Drawable[EDIT_TOOL_COUNT];
        for (int i = 0; i < EDIT_TOOL_ICONS.length; i++) {
            Drawable drawable = getContext().getDrawable(EDIT_TOOL_ICONS[i]);
            editToolIcons[i] = drawable == null ? null : drawable.mutate();
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

    /** Opens the per-game on-screen button layout editor. The caller is
     *  responsible for pausing the game while this mode is active. */
    public void beginButtonEditMode() {
        if (buttonEditMode) {
            return;
        }
        ensureButtonLayoutsLoaded();
        for (int i = 0; i < GROUP_COUNT; i++) {
            editButtonLayouts[i].copyFrom(savedButtonLayouts[i]);
        }
        keypadVisibleBeforeButtonEdit = keypadVisible;
        keypadVisible = true;
        dpadVisible = true;
        lastDpadActivityMs = android.os.SystemClock.elapsedRealtime();
        buttonEditMode = true;
        editTool = EDIT_TOOL_MOVE;
        editSelectedGroup = -1;
        editFocusedTool = EDIT_TOOL_MOVE;
        editFocusedGroup = firstEditableGroup();
        editFocusArea = EDIT_FOCUS_TOOLBAR;
        editPressGroup = -1;
        editPointerId = -1;
        editSliderDragging = false;
        releasePointers();
        postInvalidate();
    }

    /** Leaves the editor, saving the current layout when {@code save} is true
     *  and discarding it otherwise. */
    public void endButtonEditMode(boolean save) {
        if (!buttonEditMode) {
            return;
        }
        if (save) {
            saveButtonLayouts();
        }
        buttonEditMode = false;
        keypadVisible = keypadVisibleBeforeButtonEdit;
        editSelectedGroup = -1;
        editFocusArea = EDIT_FOCUS_TOOLBAR;
        editFocusedTool = EDIT_TOOL_MOVE;
        editPressGroup = -1;
        editPointerId = -1;
        editSliderDragging = false;
        editPressMoved = false;
        postInvalidate();
    }

    public boolean isButtonEditMode() {
        return buttonEditMode;
    }

    /**
     * Routes a D-pad / remote key press while the button editor is open.
     *
     * <p>Toolbar: left/right chooses a tool, Center activates it, down enters
     * the control groups. Groups: left/right chooses a set, Center selects or
     * toggles it, up returns to the toolbar. Once a set is selected in Move or
     * Resize mode the arrows nudge or scale it and Center/Back steps out.
     * Returns false only for Back on the toolbar, which cancels the editor.</p>
     */
    public boolean handleButtonEditKey(KeyEvent event) {
        if (!buttonEditMode || event == null) {
            return false;
        }
        int keyCode = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_UP) {
            return true;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return true;
        }
        boolean repeating = event.getRepeatCount() > 0;
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (repeating) {
                return true;
            }
            if (editFocusArea == EDIT_FOCUS_ADJUST) {
                editSelectedGroup = -1;
                editFocusArea = EDIT_FOCUS_GROUP;
            } else if (editFocusArea == EDIT_FOCUS_GROUP) {
                editFocusArea = EDIT_FOCUS_TOOLBAR;
                editFocusedTool = editTool;
            } else {
                return false;
            }
            ButtonHaptics.tick(getContext());
            postInvalidate();
            return true;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (!repeating) {
                    ButtonHaptics.tick(getContext());
                    handleEditDirection(keyCode);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (!repeating) {
                    ButtonHaptics.tick(getContext());
                    activateEditFocus();
                }
                return true;
            default:
                // The editor owns all other keys; nothing may leak into the
                // suspended game while it is open.
                return true;
        }
    }

    private void handleEditDirection(int keyCode) {
        int dx = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1
                : keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
        int dy = keyCode == KeyEvent.KEYCODE_DPAD_UP ? -1
                : keyCode == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
        if (editFocusArea == EDIT_FOCUS_TOOLBAR) {
            if (dx != 0) {
                editFocusedTool = Math.max(0, Math.min(EDIT_TOOL_COUNT - 1,
                        editFocusedTool + dx));
            } else if (dy > 0) {
                editFocusedGroup = firstEditableGroup();
                editFocusArea = EDIT_FOCUS_GROUP;
            }
            postInvalidate();
            return;
        }
        if (editFocusArea == EDIT_FOCUS_GROUP) {
            if (dx != 0) {
                moveEditGroupFocus(dx);
            } else if (dy < 0) {
                editFocusArea = EDIT_FOCUS_TOOLBAR;
                editFocusedTool = editTool;
            }
            postInvalidate();
            return;
        }
        if (editSelectedGroup < 0) {
            editFocusArea = EDIT_FOCUS_GROUP;
            postInvalidate();
            return;
        }
        if (editTool == EDIT_TOOL_RESIZE) {
            if (dx != 0 || dy != 0) {
                adjustEditScale(dx != 0 ? dx : dy);
            }
        } else if (editTool == EDIT_TOOL_MOVE) {
            if (dx != 0 || dy != 0) {
                nudgeEditGroup(dx, dy);
            }
        }
    }

    private void activateEditFocus() {
        if (editFocusArea == EDIT_FOCUS_TOOLBAR) {
            int tool = editFocusedTool;
            handleEditToolbar(tool);
            if (buttonEditMode) {
                if (tool == EDIT_TOOL_MOVE || tool == EDIT_TOOL_RESIZE
                        || tool == EDIT_TOOL_HIDE) {
                    editFocusArea = EDIT_FOCUS_GROUP;
                } else {
                    editFocusArea = EDIT_FOCUS_TOOLBAR;
                    editFocusedTool = tool;
                }
            }
            postInvalidate();
            return;
        }
        if (editFocusArea == EDIT_FOCUS_GROUP) {
            int group = editFocusedGroup;
            if (group < 0 || group >= GROUP_COUNT || groupBounds[group].isEmpty()) {
                return;
            }
            if (editTool == EDIT_TOOL_HIDE) {
                ButtonLayout layout = editButtonLayouts[group];
                layout.hidden = !layout.hidden;
                editSelectedGroup = -1;
            } else if (editTool == EDIT_TOOL_MOVE || editTool == EDIT_TOOL_RESIZE) {
                editSelectedGroup = group;
                editFocusArea = EDIT_FOCUS_ADJUST;
            }
            postInvalidate();
            return;
        }
        editSelectedGroup = -1;
        editFocusArea = EDIT_FOCUS_GROUP;
        postInvalidate();
    }

    private int firstEditableGroup() {
        for (int group = 0; group < GROUP_COUNT; group++) {
            if (!groupBounds[group].isEmpty()) {
                return group;
            }
        }
        return 0;
    }

    private void moveEditGroupFocus(int direction) {
        int start = editFocusedGroup;
        if (start < 0 || start >= GROUP_COUNT) {
            start = 0;
        }
        for (int step = 1; step <= GROUP_COUNT; step++) {
            int candidate = (start + direction * step + GROUP_COUNT * 2) % GROUP_COUNT;
            if (!groupBounds[candidate].isEmpty()) {
                editFocusedGroup = candidate;
                return;
            }
        }
    }

    private void nudgeEditGroup(int dx, int dy) {
        if (editSelectedGroup < 0) {
            return;
        }
        float viewWidth = logicalViewWidth();
        float viewHeight = logicalViewHeight();
        if (viewWidth <= 0f || viewHeight <= 0f) {
            return;
        }
        float step = Math.max(2f, editGridStep() * 0.5f);
        ButtonLayout layout = editButtonLayouts[editSelectedGroup];
        if (dx != 0) {
            layout.dx += dx * step / viewWidth;
        }
        if (dy != 0) {
            layout.dy += dy * step / viewHeight;
        }
        computeZones();
        postInvalidate();
    }

    private void adjustEditScale(int direction) {
        if (editSelectedGroup < 0) {
            return;
        }
        ButtonLayout layout = editButtonLayouts[editSelectedGroup];
        float scale = clamp(layout.scale, BUTTON_SCALE_MIN, BUTTON_SCALE_MAX);
        layout.scale = clamp(scale + direction * 0.05f, BUTTON_SCALE_MIN, BUTTON_SCALE_MAX);
        computeZones();
        postInvalidate();
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
        java.util.Arrays.fill(displaySnapshots, null);
        java.util.Arrays.fill(displaySnapshotCanvases, null);
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
        if (buttonEditMode) {
            dpadVisible = true;
        } else if (isLandscapeOrientation()) {
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
            if (buttonEditMode) {
                drawEditGrid(canvas, viewWidth, viewHeight);
            }
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
        if (showFps) {
            drawFpsOverlay(canvas);
            if (!released) {
                // Keep the counter ticking (and decaying to 0 when the game
                // stops presenting) even if no new frame arrives.
                postInvalidateDelayed(250L);
            }
        }
        if (buttonEditMode) {
            drawButtonEditChrome(canvas, viewWidth, viewHeight);
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

    private float logicalViewWidth() {
        return rotated ? getHeight() : getWidth();
    }

    private float logicalViewHeight() {
        return rotated ? getWidth() : getHeight();
    }

    private static int groupForControl(int id) {
        if (id == CTRL_UP || id == CTRL_DOWN || id == CTRL_LEFT
                || id == CTRL_RIGHT || id == CTRL_SELECT) {
            return GROUP_DPAD;
        }
        if (id >= CTRL_DIAL_BASE && id < CTRL_DIAL_BASE + DIAL_LABELS.length) {
            return GROUP_DIAL;
        }
        if (id == CTRL_SOFT1 || id == CTRL_SOFT2 || id == CTRL_MENU || id == CTRL_CLEAR) {
            return GROUP_SOFT;
        }
        return -1;
    }

    private ButtonLayout activeButtonLayout(int group) {
        if (group < 0 || group >= GROUP_COUNT) {
            return null;
        }
        return buttonEditMode ? editButtonLayouts[group] : savedButtonLayouts[group];
    }

    private String buttonPrefKey(String gameId, int group, String suffix) {
        return gameId + "_" + BUTTON_PREF_PREFIX + "_" + GROUP_PREF_NAMES[group] + "_" + suffix;
    }

    private void ensureButtonLayoutsLoaded() {
        String gameId = EntryActivity.gameId;
        if (gameId == null) {
            gameId = "";
        }
        if (buttonLayoutsLoaded && gameId.equals(buttonLayoutGameId)) {
            return;
        }
        android.content.SharedPreferences prefs = getContext().getSharedPreferences(
                DisplayActivity.PREFS_NAME, android.content.Context.MODE_PRIVATE);
        for (int i = 0; i < GROUP_COUNT; i++) {
            ButtonLayout layout = savedButtonLayouts[i];
            layout.dx = prefs.getFloat(buttonPrefKey(gameId, i, "dx"), 0f);
            layout.dy = prefs.getFloat(buttonPrefKey(gameId, i, "dy"), 0f);
            layout.scale = clamp(prefs.getFloat(buttonPrefKey(gameId, i, "scale"), 1f),
                    BUTTON_SCALE_MIN, BUTTON_SCALE_MAX);
            layout.hidden = prefs.getBoolean(buttonPrefKey(gameId, i, "hidden"), false);
        }
        buttonLayoutGameId = gameId;
        buttonLayoutsLoaded = true;
    }

    private void saveButtonLayouts() {
        String gameId = buttonLayoutGameId;
        if (gameId == null) {
            gameId = EntryActivity.gameId == null ? "" : EntryActivity.gameId;
        }
        android.content.SharedPreferences.Editor editor = getContext().getSharedPreferences(
                DisplayActivity.PREFS_NAME, android.content.Context.MODE_PRIVATE).edit();
        for (int i = 0; i < GROUP_COUNT; i++) {
            ButtonLayout layout = editButtonLayouts[i];
            savedButtonLayouts[i].copyFrom(layout);
            editor.putFloat(buttonPrefKey(gameId, i, "dx"), layout.dx);
            editor.putFloat(buttonPrefKey(gameId, i, "dy"), layout.dy);
            editor.putFloat(buttonPrefKey(gameId, i, "scale"), layout.scale);
            editor.putBoolean(buttonPrefKey(gameId, i, "hidden"), layout.hidden);
        }
        editor.commit();
    }

    private void resetEditButtonLayouts() {
        for (int i = 0; i < GROUP_COUNT; i++) {
            editButtonLayouts[i].reset();
        }
        editSelectedGroup = -1;
        editPressGroup = -1;
        editTool = EDIT_TOOL_MOVE;
        editFocusedTool = EDIT_TOOL_MOVE;
        editFocusedGroup = firstEditableGroup();
        editFocusArea = EDIT_FOCUS_TOOLBAR;
    }

    /**
     * Re-applies the active per-game transform to key rectangles computed by
     * {@link #computeBaseZones()}. Scale is around the set's centre so all keys
     * in a set grow/shrink together, then the whole set is translated by the
     * stored fraction of the view size.
     */
    private void applyButtonLayouts() {
        ensureButtonLayoutsLoaded();
        float viewWidth = logicalViewWidth();
        float viewHeight = logicalViewHeight();
        if (viewWidth <= 0f || viewHeight <= 0f) {
            return;
        }
        for (int group = 0; group < GROUP_COUNT; group++) {
            RectF base = groupBaseBounds[group];
            base.setEmpty();
            for (KeyDef def : keys) {
                if (groupForControl(def.id) == group && def.rect.width() > 0f
                        && def.rect.height() > 0f) {
                    base.union(def.rect);
                }
            }
            ButtonLayout layout = activeButtonLayout(group);
            if (layout == null || base.isEmpty()) {
                groupBounds[group].setEmpty();
                continue;
            }
            float scale = clamp(layout.scale, BUTTON_SCALE_MIN, BUTTON_SCALE_MAX);
            float cx = base.centerX();
            float cy = base.centerY();
            float scaledLeft = cx + (base.left - cx) * scale;
            float scaledTop = cy + (base.top - cy) * scale;
            float scaledRight = cx + (base.right - cx) * scale;
            float scaledBottom = cy + (base.bottom - cy) * scale;

            float dx = layout.dx * viewWidth;
            float dy = layout.dy * viewHeight;
            float minDx = -scaledLeft;
            float maxDx = viewWidth - scaledRight;
            if (maxDx < minDx) {
                dx = (viewWidth - (scaledRight - scaledLeft)) / 2f - scaledLeft;
            } else {
                dx = clamp(dx, minDx, maxDx);
            }
            float minDy = -scaledTop;
            float maxDy = viewHeight - scaledBottom;
            if (maxDy < minDy) {
                dy = (viewHeight - (scaledBottom - scaledTop)) / 2f - scaledTop;
            } else {
                dy = clamp(dy, minDy, maxDy);
            }

            for (KeyDef def : keys) {
                if (groupForControl(def.id) != group) {
                    continue;
                }
                RectF r = def.rect;
                float left = cx + (r.left - cx) * scale + dx;
                float top = cy + (r.top - cy) * scale + dy;
                float right = cx + (r.right - cx) * scale + dx;
                float bottom = cy + (r.bottom - cy) * scale + dy;
                r.set(left, top, right, bottom);
            }
            groupBounds[group].set(scaledLeft + dx, scaledTop + dy,
                    scaledRight + dx, scaledBottom + dy);
        }
    }

    private float editGridStep() {
        float min = Math.min(logicalViewWidth(), logicalViewHeight());
        return Math.max(14f, min / 12f);
    }

    private static float clamp(float value, float min, float max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    private void updateEditGeometry() {
        if (!buttonEditMode) {
            return;
        }
        float viewWidth = logicalViewWidth();
        float viewHeight = logicalViewHeight();
        if (viewWidth <= 0f || viewHeight <= 0f) {
            return;
        }
        float min = Math.min(viewWidth, viewHeight);
        float icon = Math.max(26f, Math.min(62f, min * 0.105f));
        float gap = Math.max(3f, icon * 0.14f);
        float total = icon * EDIT_TOOL_COUNT + gap * (EDIT_TOOL_COUNT - 1);
        float available = Math.max(120f, viewWidth - 8f);
        if (total > available) {
            icon = Math.max(20f, (available - gap * (EDIT_TOOL_COUNT - 1)) / EDIT_TOOL_COUNT);
            total = icon * EDIT_TOOL_COUNT + gap * (EDIT_TOOL_COUNT - 1);
        }
        editIconSize = icon;
        float startX = Math.max(4f, (viewWidth - total) / 2f);
        float top = Math.max(4f, min * 0.015f);
        for (int i = 0; i < EDIT_TOOL_COUNT; i++) {
            float left = startX + i * (icon + gap);
            editToolbarRects[i].set(left, top, left + icon, top + icon);
        }
        float labelSize = Math.max(7f, icon * 0.20f);
        float pad = Math.max(3f, icon * 0.08f);
        editToolbarPanel.set(startX - pad, top - pad,
                startX + total + pad, top + icon + labelSize * 1.55f + pad);

        float sliderTop = editToolbarPanel.bottom + Math.max(3f, icon * 0.12f);
        float sliderWidth = Math.min(viewWidth * 0.62f, icon * 4.8f);
        RectF resizeRect = editToolbarRects[EDIT_TOOL_RESIZE];
        float sliderCenter = resizeRect.centerX();
        float half = sliderWidth / 2f;
        sliderCenter = clamp(sliderCenter, half + 4f, viewWidth - half - 4f);
        float sliderHeight = labelSize * 1.55f + Math.max(10f, icon * 0.22f) + pad * 2f;
        editResizePanel.set(sliderCenter - half, sliderTop,
                sliderCenter + half, sliderTop + sliderHeight);
        float trackInset = Math.max(8f, pad * 1.6f);
        float trackY = sliderTop + labelSize * 1.55f + Math.max(2f, icon * 0.05f);
        float trackHeight = Math.max(3f, icon * 0.075f);
        editResizeTrack.set(editResizePanel.left + trackInset, trackY,
                editResizePanel.right - trackInset, trackY + trackHeight);
        ButtonLayout layout = activeButtonLayout(editSelectedGroup);
        float scale = layout == null ? 1f : clamp(layout.scale, BUTTON_SCALE_MIN, BUTTON_SCALE_MAX);
        float knobRadius = Math.max(5f, icon * 0.12f);
        float knobX = editResizeTrack.left
                + (scale - BUTTON_SCALE_MIN) / (BUTTON_SCALE_MAX - BUTTON_SCALE_MIN)
                * editResizeTrack.width();
        editResizeKnob.set(knobX - knobRadius, editResizeTrack.centerY() - knobRadius,
                knobX + knobRadius, editResizeTrack.centerY() + knobRadius);
    }

    private void computeZones() {
        computeBaseZones();
        applyButtonLayouts();
        updateEditGeometry();
    }

    private void computeBaseZones() {
        float w = rotated ? getHeight() : getWidth();
        float h = rotated ? getWidth() : getHeight();
        if (w >= h) {
            computeZonesLandscape();
        } else {
            computeZonesPortrait();
        }
        if (gameRect.width() > 0f && gameRect.height() > 0f) {
            // Let the software OpenGLES rasterizer match its internal 3D buffer to the
            // physical size of the skin opening. Rendering the title's full native
            // framebuffer into a much smaller LCD area wastes CPU and looks over-sharp.
            opendoja.host.HostDisplayTarget.set(
                    Math.round(gameRect.width()), Math.round(gameRect.height()));
        }
    }

    /**
     * Centres the game inside {@code aperture} at its native aspect ratio so
     * widescreen titles are not stretched into a nearly square skin opening.
     * The uncovered area is painted black by {@link #drawGameUpscaled}.
     */
    private void fitGameInto(RectF aperture, int gameW, int gameH) {
        float scale = Math.min(aperture.width() / gameW, aperture.height() / gameH);
        float dstW = gameW * scale;
        float dstH = gameH * scale;
        float left = aperture.left + (aperture.width() - dstW) / 2f;
        float top = aperture.top + (aperture.height() - dstH) / 2f;
        gameRect.set(left, top, left + dstW, top + dstH);
        gamePixelW = gameW;
        gamePixelH = gameH;
    }

    private void fillGameInto(RectF aperture, int gameW, int gameH) {
        gameRect.set(aperture);
        gamePixelW = gameW;
        gamePixelH = gameH;
    }

    private void applyGameDisplay(RectF aperture, int gameW, int gameH, boolean fit) {
        if (fit) {
            fitGameInto(aperture, gameW, gameH);
        } else {
            fillGameInto(aperture, gameW, gameH);
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
        boolean fit = useAspectFit();
        boolean svgSkin = isSvgSkin(cachedSkin, false);
        if (svgSkin) {
            float vbW = 400f, vbH = 800f;
            float sx = viewWidth / vbW;
            float sy = viewHeight / vbH;
            float apertureLeft = 42f * sx;
            float apertureTop = 112f * sy;
            float apertureW = 316f * sx;
            float apertureH = 422f * sy;
            apertureRect.set(apertureLeft, apertureTop, apertureLeft + apertureW, apertureTop + apertureH);
            applyGameDisplay(apertureRect, gameW, gameH, fit);
            if (fullScreen) {
                apertureRect.set(0f, 0f, viewWidth, viewHeight);
                applyGameDisplay(apertureRect, gameW, gameH, fit);
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
        apertureRect.set(0f, 0f, gameAreaW, gameAreaH);
        applyGameDisplay(apertureRect, gameW, gameH, fit);

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
        boolean fit = useAspectFit();
        boolean svgSkin = isSvgSkin(cachedSkin, true);
        if (svgSkin) {
            float vbW = 800f, vbH = 400f;
            float sx = viewWidth / vbW;
            float sy = viewHeight / vbH;
            float apertureLeft = 48f * sx;
            float apertureTop = 42f * sy;
            float apertureW = 422f * sx;
            float apertureH = 316f * sy;
            apertureRect.set(apertureLeft, apertureTop, apertureLeft + apertureW, apertureTop + apertureH);
            applyGameDisplay(apertureRect, gameW, gameH, fit);
            if (fullScreen) {
                apertureRect.set(0f, 0f, viewWidth, viewHeight);
                applyGameDisplay(apertureRect, gameW, gameH, fit);
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
        apertureRect.set(0f, 0f, gameAreaW, gameAreaH);
        applyGameDisplay(apertureRect, gameW, gameH, fit);

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

    /**
     * Whether the game keeps its native aspect ratio (Scale to Fit) instead of
     * stretching to the screen opening (Fill Screen).
     */
    private boolean useAspectFit() {
        String gameId = EntryActivity.gameId;
        String value;
        if (gameId != null && !gameId.isEmpty()) {
            if (!gameId.equals(cachedGameDisplayId) || cachedGameDisplay == null) {
                android.content.SharedPreferences prefs = getContext().getSharedPreferences(
                        DisplayActivity.PREFS_NAME, android.content.Context.MODE_PRIVATE);
                String override = prefs.getString(gameId + "_" + DisplayActivity.KEY_GAME_DISPLAY,
                        DisplayActivity.INHERIT);
                if (!override.equals(DisplayActivity.INHERIT)) {
                    cachedGameDisplay = override;
                } else {
                    cachedGameDisplay = prefs.getString(DisplayActivity.KEY_GAME_DISPLAY,
                            DisplayActivity.GAME_DISPLAY_FILL);
                }
                cachedGameDisplayId = gameId;
            }
            value = cachedGameDisplay;
        } else {
            if (cachedGameDisplayId != null || cachedGameDisplay == null) {
                android.content.SharedPreferences prefs = getContext().getSharedPreferences(
                        DisplayActivity.PREFS_NAME, android.content.Context.MODE_PRIVATE);
                cachedGameDisplay = prefs.getString(DisplayActivity.KEY_GAME_DISPLAY,
                        DisplayActivity.GAME_DISPLAY_FILL);
                cachedGameDisplayId = null;
            }
            value = cachedGameDisplay;
        }
        return DisplayActivity.GAME_DISPLAY_FIT.equals(value);
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
        displaySnapshotIndex = (displaySnapshotIndex + 1) % DISPLAY_SNAPSHOT_COUNT;
        Bitmap snapshot = displaySnapshots[displaySnapshotIndex];
        Canvas snapshotCanvas = displaySnapshotCanvases[displaySnapshotIndex];
        if (snapshot == null || snapshot.getWidth() != width || snapshot.getHeight() != height) {
            snapshot = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            displaySnapshots[displaySnapshotIndex] = snapshot;
            snapshotCanvas = new Canvas(snapshot);
            displaySnapshotCanvases[displaySnapshotIndex] = snapshotCanvas;
        }
        // Both the source and the snapshot are ARGB_8888 Android bitmaps. Copying
        // through a Canvas is a native blit; the previous getPixels/OR-alpha/setPixels
        // path round-tripped the whole frame through the JNI and hwui color converters.
        synchronized (opendoja.host.DesktopSurface.PRESENTATION_COPY_LOCK) {
            snapshotCanvas.drawBitmap(source, 0f, 0f, displaySnapshotPaint);
        }
        // The handset LCD has no alpha channel: RGB is displayed as-is.
        snapshot.setHasAlpha(false);
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
        RectF clipRect = apertureRect.width() > 0f && apertureRect.height() > 0f ? apertureRect : gameRect;
        boolean clipRounded = gameCornerRadiusX > 0f && gameCornerRadiusY > 0f;
        if (clipRounded) {
            // Match the skin's screen opening so the game image shows rounded
            // corners where the skin's bezel curves (Original mode only).
            canvas.save();
            gameClipPath.rewind();
            gameClipPath.addRoundRect(clipRect, gameCornerRadiusX, gameCornerRadiusY,
                    android.graphics.Path.Direction.CW);
            canvas.clipPath(gameClipPath);
        }
        // Aspect fitting leaves bars on the long axis of a widescreen game.
        // Paint them black so the skin opening looks like a proper letterbox
        // instead of showing whatever the skin artwork has underneath.
        if (gameRect.left > clipRect.left + 0.5f || gameRect.top > clipRect.top + 0.5f
                || gameRect.right < clipRect.right - 0.5f || gameRect.bottom < clipRect.bottom - 0.5f) {
            letterboxPaint.setColor(0xFF000000);
            canvas.drawRect(clipRect, letterboxPaint);
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
            int group = groupForControl(def.id);
            boolean hidden = group >= 0 && activeButtonLayout(group) != null
                    && activeButtonLayout(group).hidden;
            if (!buttonEditMode && hidden) {
                continue;
            }
            if (!buttonEditMode && !dpadVisible && isLandscapeOrientation()
                    && (def.id == CTRL_UP || def.id == CTRL_DOWN || def.id == CTRL_LEFT
                    || def.id == CTRL_RIGHT || def.id == CTRL_SELECT)) {
                continue;
            }
            drawKey(canvas, def, hidden && buttonEditMode ? 92 : 255);
        }
    }

    private void drawKey(Canvas canvas, KeyDef def) {
        drawKey(canvas, def, 255);
    }

    private void drawKey(Canvas canvas, KeyDef def, int alphaOverride) {
        RectF rect = def.rect;
        if (rect.width() <= 0f || rect.height() <= 0f) return;
        boolean pressed = activeControls.contains(def.id);
        if (controlsSkin != null) {
            String artId = skinIdForControl(def.id);
            if (artId != null) {
                boolean landscape = isLandscapeOrientation();
                int alpha = (artId.startsWith("dpad-") && landscape) ? 128 : 255;
                if (alphaOverride < 255) {
                    alpha = alpha * alphaOverride / 255;
                }
                boolean stretch = !landscape && isDialArt(artId);
                if (controlsSkin.draw(canvas, artId, rect, pressed, alpha, stretch)) {
                    return;
                }
            }
        }
        float radius = Math.min(rect.width(), rect.height()) * 0.18f;
        if (def.id == CTRL_UP || def.id == CTRL_DOWN || def.id == CTRL_LEFT
                || def.id == CTRL_RIGHT || def.id == CTRL_SELECT) {
            setPaintColorAndAlpha(keyFillPaint, pressed ? 0xCC505050 : 0x99303030, alphaOverride);
            setPaintColorAndAlpha(keyStrokePaint, 0xCCFFFFFF, alphaOverride);
        } else if (pressed) {
            setPaintColorAndAlpha(keyFillPaint, 0x99FFFF90, alphaOverride);
            setPaintColorAndAlpha(keyStrokePaint, 0xFFFFFFFF, alphaOverride);
        } else {
            setPaintColorAndAlpha(keyFillPaint, 0x99303030, alphaOverride);
            setPaintColorAndAlpha(keyStrokePaint, 0xCCFFFFFF, alphaOverride);
        }
        canvas.drawRoundRect(rect, radius, radius, keyFillPaint);
        canvas.drawRoundRect(rect, radius, radius, keyStrokePaint);
        setPaintColorAndAlpha(keyTextPaint, 0xFFFFFFFF, alphaOverride);
        keyTextPaint.setTextSize(Math.min(rect.height() * 0.42f, rect.width() * 0.5f));
        float measured = keyTextPaint.measureText(def.label);
        if (measured > rect.width() * 0.9f && measured > 0f) {
            keyTextPaint.setTextSize(keyTextPaint.getTextSize() * (rect.width() * 0.9f / measured));
        }
        if (keyTextPaint.getTextSize() < 8f) keyTextPaint.setTextSize(8f);
        canvas.drawText(def.label, rect.centerX(),
                rect.centerY() + keyTextPaint.getTextSize() * 0.34f, keyTextPaint);
    }

    private static void setPaintColorAndAlpha(Paint paint, int color, int alphaOverride) {
        paint.setColor(color);
        int baseAlpha = color >>> 24;
        paint.setAlpha(baseAlpha * Math.max(0, Math.min(255, alphaOverride)) / 255);
    }

    private void drawEditGrid(Canvas canvas, float viewWidth, float viewHeight) {
        float step = editGridStep();
        for (float x = 0f; x <= viewWidth; x += step) {
            canvas.drawLine(x, 0f, x, viewHeight, editGridPaint);
        }
        for (float y = 0f; y <= viewHeight; y += step) {
            canvas.drawLine(0f, y, viewWidth, y, editGridPaint);
        }
    }

    private void drawButtonEditChrome(Canvas canvas, float viewWidth, float viewHeight) {
        float margin = Math.max(3f, Math.min(viewWidth, viewHeight) * 0.007f);
        for (int group = 0; group < GROUP_COUNT; group++) {
            ButtonLayout layout = activeButtonLayout(group);
            if (layout == null || groupBounds[group].isEmpty()) {
                continue;
            }
            RectF r = new RectF(groupBounds[group]);
            r.inset(-margin, -margin);
            boolean hidden = layout.hidden;
            boolean selected = !hidden && editSelectedGroup == group
                    && (editTool == EDIT_TOOL_MOVE || editTool == EDIT_TOOL_RESIZE);
            float radius = Math.max(6f, margin * 2.2f);
            if (hidden) {
                // Hidden sets stay visible in the editor (with a red outline)
                // so they can be focused and toggled back with the D-pad.
                editHiddenPaint.setStrokeWidth(Math.max(3f, margin * 1.6f));
                canvas.drawRoundRect(r, radius, radius, editHiddenPaint);
            } else {
                if (selected) {
                    canvas.drawRoundRect(r, radius, radius, editSelectionFillPaint);
                }
                editHighlightPaint.setStrokeWidth(selected ? Math.max(6f, margin * 2.2f)
                        : Math.max(4f, margin * 1.45f));
                canvas.drawRoundRect(r, radius, radius, editHighlightPaint);
            }
            if (editFocusArea != EDIT_FOCUS_TOOLBAR && editFocusedGroup == group) {
                RectF focus = new RectF(groupBounds[group]);
                float focusInset = margin * (selected ? 3.2f : 2.6f);
                focus.inset(-focusInset, -focusInset);
                editFocusPaint.setStrokeWidth(Math.max(5f, margin * 2.4f));
                canvas.drawRoundRect(focus, radius * 1.4f, radius * 1.4f, editFocusPaint);
            }
        }
        drawEditToolbar(canvas);
        if (editTool == EDIT_TOOL_RESIZE && editSelectedGroup >= 0) {
            drawEditResizeSlider(canvas);
        }
    }

    private void drawEditToolbar(Canvas canvas) {
        float radius = Math.max(5f, editIconSize * 0.12f);
        canvas.drawRoundRect(editToolbarPanel, radius, radius, editToolbarBgPaint);
        float labelSize = Math.max(7f, editIconSize * 0.20f);
        editLabelPaint.setTextSize(labelSize);
        for (int i = 0; i < EDIT_TOOL_COUNT; i++) {
            RectF r = editToolbarRects[i];
            boolean selected = i == editTool && i <= EDIT_TOOL_HIDE;
            boolean focused = editFocusArea == EDIT_FOCUS_TOOLBAR && i == editFocusedTool;
            editToolFillPaint.setColor(focused ? (selected ? 0x664A90FF : 0x553B82F6)
                    : (selected ? 0x3AFFD600 : 0x2A000000));
            editToolBorderPaint.setColor(focused ? 0xFF60A5FA
                    : (selected ? 0xFFFFD600 : 0xCCFFFFFF));
            editToolBorderPaint.setStrokeWidth(focused ? 3f : (selected ? 2.5f : 1.5f));
            canvas.drawRect(r, editToolFillPaint);
            canvas.drawRect(r, editToolBorderPaint);
            Drawable icon = editToolIcons == null ? null : editToolIcons[i];
            if (icon != null) {
                float inset = r.width() * 0.19f;
                icon.setTint(focused ? 0xFF7CB8FF : (selected ? 0xFFFFD600 : 0xFFFFFFFF));
                icon.setBounds(Math.round(r.left + inset), Math.round(r.top + inset),
                        Math.round(r.right - inset), Math.round(r.bottom - inset));
                icon.draw(canvas);
            }
            String label = editToolLabel(i);
            float labelWidth = editLabelPaint.measureText(label);
            if (labelWidth > r.width() * 1.35f && labelWidth > 0f) {
                editLabelPaint.setTextSize(labelSize * (r.width() * 1.35f / labelWidth));
            } else {
                editLabelPaint.setTextSize(labelSize);
            }
            canvas.drawText(label, r.centerX(), r.bottom + labelSize * 1.35f, editLabelPaint);
        }
    }

    private String editToolLabel(int tool) {
        switch (tool) {
            case EDIT_TOOL_MOVE: return getContext().getString(R.string.edit_buttons_move);
            case EDIT_TOOL_RESIZE: return getContext().getString(R.string.edit_buttons_resize);
            case EDIT_TOOL_HIDE: return getContext().getString(R.string.edit_buttons_hide);
            case EDIT_TOOL_RESET: return getContext().getString(R.string.edit_buttons_reset);
            case EDIT_TOOL_OK: return getContext().getString(R.string.edit_buttons_ok);
            case EDIT_TOOL_CANCEL: return getContext().getString(R.string.edit_buttons_cancel);
            default: return "";
        }
    }

    private void drawEditResizeSlider(Canvas canvas) {
        ButtonLayout layout = activeButtonLayout(editSelectedGroup);
        if (layout == null) {
            return;
        }
        float radius = Math.max(5f, editIconSize * 0.12f);
        canvas.drawRoundRect(editResizePanel, radius, radius, editToolbarBgPaint);
        if (editFocusArea == EDIT_FOCUS_ADJUST && editTool == EDIT_TOOL_RESIZE) {
            RectF focus = new RectF(editResizePanel);
            focus.inset(-Math.max(3f, editIconSize * 0.08f), -Math.max(3f, editIconSize * 0.08f));
            editFocusPaint.setStrokeWidth(Math.max(3f, editIconSize * 0.10f));
            canvas.drawRoundRect(focus, radius * 1.4f, radius * 1.4f, editFocusPaint);
        }
        float labelSize = Math.max(7f, editIconSize * 0.20f);
        editLabelPaint.setTextSize(labelSize);
        String label = getContext().getString(R.string.edit_buttons_scale,
                Math.round(clamp(layout.scale, BUTTON_SCALE_MIN, BUTTON_SCALE_MAX) * 100f));
        float baseline = editResizePanel.top + Math.max(3f, editIconSize * 0.08f) + labelSize;
        canvas.drawText(label, editResizePanel.centerX(), baseline, editLabelPaint);
        float trackRadius = editResizeTrack.height() / 2f;
        canvas.drawRoundRect(editResizeTrack, trackRadius, trackRadius, editSliderTrackPaint);
        canvas.drawCircle(editResizeKnob.centerX(), editResizeKnob.centerY(),
                editResizeKnob.width() / 2f, editSliderKnobPaint);
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
        boolean dpadHidden = false;
        ButtonLayout dpadLayout = activeButtonLayout(GROUP_DPAD);
        if (dpadLayout != null && dpadLayout.hidden) {
            dpadHidden = true;
        }
        if (!dpadHidden && (!dpadVisible && isLandscapeOrientation())) {
            // skip dpad keys
        } else if (!dpadHidden) {
            if (selectKey.rect.contains(x, y)) return CTRL_SELECT;
        }
        for (KeyDef def : keys) {
            int group = groupForControl(def.id);
            ButtonLayout layout = activeButtonLayout(group);
            if (layout != null && layout.hidden) {
                continue;
            }
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
        if (buttonEditMode) {
            return handleButtonEditTouch(event);
        }
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

    private boolean handleButtonEditTouch(MotionEvent event) {
        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                editPointerId = event.getPointerId(event.getActionIndex());
                return editTouchDown(touchX(event, event.getActionIndex()),
                        touchY(event, event.getActionIndex()));
            }
            case MotionEvent.ACTION_MOVE: {
                int index = findPointerIndex(event, editPointerId);
                if (index < 0) {
                    return true;
                }
                float x = touchX(event, index);
                float y = touchY(event, index);
                if (editSliderDragging) {
                    updateEditResizeFromX(x);
                } else if (editTool == EDIT_TOOL_MOVE && editPressGroup >= 0) {
                    updateEditDrag(x, y);
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                if (event.getPointerId(event.getActionIndex()) == editPointerId) {
                    int index = event.getActionIndex();
                    editTouchUp(touchX(event, index), touchY(event, index));
                    editPointerId = -1;
                }
                return true;
            case MotionEvent.ACTION_UP: {
                int index = event.getActionIndex();
                if (event.getPointerId(index) == editPointerId) {
                    editTouchUp(touchX(event, index), touchY(event, index));
                }
                editPointerId = -1;
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                editPointerId = -1;
                editPressGroup = -1;
                editPressMoved = false;
                editSliderDragging = false;
                postInvalidate();
                return true;
            default:
                return true;
        }
    }

    private static int findPointerIndex(MotionEvent event, int pointerId) {
        for (int i = 0; i < event.getPointerCount(); i++) {
            if (event.getPointerId(i) == pointerId) {
                return i;
            }
        }
        return -1;
    }

    private boolean editTouchDown(float x, float y) {
        int tool = hitEditToolbar(x, y);
        if (tool >= 0) {
            ButtonHaptics.tick(getContext());
            editFocusedTool = tool;
            editFocusArea = EDIT_FOCUS_TOOLBAR;
            handleEditToolbar(tool);
            return true;
        }
        if (editTool == EDIT_TOOL_RESIZE && editSelectedGroup >= 0
                && editResizePanel.contains(x, y)) {
            ButtonHaptics.tick(getContext());
            editFocusArea = EDIT_FOCUS_ADJUST;
            editFocusedGroup = editSelectedGroup;
            editSliderDragging = true;
            updateEditResizeFromX(x);
            return true;
        }
        int group = hitEditGroup(x, y);
        if (group < 0) {
            if (editTool == EDIT_TOOL_MOVE) {
                editSelectedGroup = -1;
                editFocusArea = EDIT_FOCUS_GROUP;
                postInvalidate();
            }
            return true;
        }
        ButtonHaptics.tick(getContext());
        editFocusedGroup = group;
        if (editTool == EDIT_TOOL_HIDE) {
            editFocusArea = EDIT_FOCUS_GROUP;
            ButtonLayout layout = editButtonLayouts[group];
            layout.hidden = !layout.hidden;
            editSelectedGroup = -1;
            postInvalidate();
            return true;
        }
        if (editTool == EDIT_TOOL_MOVE || editTool == EDIT_TOOL_RESIZE) {
            editFocusArea = EDIT_FOCUS_ADJUST;
            editPressGroup = group;
            editPressWasSelected = editSelectedGroup == group;
            editSelectedGroup = group;
            editPressStartX = x;
            editPressStartY = y;
            editDragStartDx = editButtonLayouts[group].dx;
            editDragStartDy = editButtonLayouts[group].dy;
            editPressMoved = false;
            editSliderDragging = false;
            postInvalidate();
            return true;
        }
        return true;
    }

    private void editTouchUp(float x, float y) {
        if (editSliderDragging) {
            editSliderDragging = false;
            editPressGroup = -1;
            return;
        }
        if (editTool == EDIT_TOOL_MOVE && editPressGroup >= 0
                && !editPressMoved && editPressWasSelected) {
            editSelectedGroup = -1;
            postInvalidate();
        }
        editPressGroup = -1;
        editPressMoved = false;
    }

    private int hitEditToolbar(float x, float y) {
        for (int i = 0; i < EDIT_TOOL_COUNT; i++) {
            if (editToolbarRects[i].contains(x, y)) {
                return i;
            }
        }
        return -1;
    }

    private int hitEditGroup(float x, float y) {
        float pad = Math.max(4f, editIconSize * 0.10f);
        for (int group = 0; group < GROUP_COUNT; group++) {
            ButtonLayout layout = editButtonLayouts[group];
            if (layout.hidden && editTool != EDIT_TOOL_HIDE) {
                continue;
            }
            RectF r = groupBounds[group];
            if (!r.isEmpty() && x >= r.left - pad && x <= r.right + pad
                    && y >= r.top - pad && y <= r.bottom + pad) {
                return group;
            }
        }
        return -1;
    }

    private void handleEditToolbar(int tool) {
        if (tool == EDIT_TOOL_RESET) {
            resetEditButtonLayouts();
            editSliderDragging = false;
            editFocusedTool = tool;
            editFocusArea = EDIT_FOCUS_TOOLBAR;
            computeZones();
            postInvalidate();
            return;
        }
        if (tool == EDIT_TOOL_OK) {
            finishButtonEditFromOverlay(true);
            return;
        }
        if (tool == EDIT_TOOL_CANCEL) {
            finishButtonEditFromOverlay(false);
            return;
        }
        editTool = tool;
        editPressGroup = -1;
        editPressMoved = false;
        editSliderDragging = false;
        editFocusedTool = tool;
        if (tool == EDIT_TOOL_HIDE) {
            editSelectedGroup = -1;
        }
        if (tool == EDIT_TOOL_MOVE || tool == EDIT_TOOL_RESIZE || tool == EDIT_TOOL_HIDE) {
            editFocusArea = EDIT_FOCUS_GROUP;
            if (tool != EDIT_TOOL_HIDE && editSelectedGroup >= 0) {
                editFocusedGroup = editSelectedGroup;
            } else if (editFocusedGroup < 0 || editFocusedGroup >= GROUP_COUNT
                    || groupBounds[editFocusedGroup].isEmpty()) {
                editFocusedGroup = firstEditableGroup();
            }
        } else {
            editFocusArea = EDIT_FOCUS_TOOLBAR;
        }
        postInvalidate();
    }

    private void finishButtonEditFromOverlay(boolean save) {
        if (getContext() instanceof EntryActivity) {
            ((EntryActivity) getContext()).finishButtonEditing(save);
        } else {
            endButtonEditMode(save);
        }
    }

    private void updateEditDrag(float x, float y) {
        if (editPressGroup < 0) {
            return;
        }
        float dxPx = x - editPressStartX;
        float dyPx = y - editPressStartY;
        if (!editPressMoved) {
            float slop = Math.max(5f, getResources().getDisplayMetrics().density * 4f);
            if (Math.hypot(dxPx, dyPx) < slop) {
                return;
            }
            editPressMoved = true;
        }
        float viewWidth = logicalViewWidth();
        float viewHeight = logicalViewHeight();
        if (viewWidth <= 0f || viewHeight <= 0f) {
            return;
        }
        ButtonLayout layout = editButtonLayouts[editPressGroup];
        float scale = clamp(layout.scale, BUTTON_SCALE_MIN, BUTTON_SCALE_MAX);
        RectF base = groupBaseBounds[editPressGroup];
        if (base.isEmpty()) {
            return;
        }
        float cx = base.centerX();
        float cy = base.centerY();
        float scaledLeft = cx + (base.left - cx) * scale;
        float scaledTop = cy + (base.top - cy) * scale;
        float scaledRight = cx + (base.right - cx) * scale;
        float scaledBottom = cy + (base.bottom - cy) * scale;
        float step = editGridStep();

        float targetLeft = scaledLeft + editDragStartDx * viewWidth + dxPx;
        float targetTop = scaledTop + editDragStartDy * viewHeight + dyPx;
        float newDx = Math.round(targetLeft / step) * step - scaledLeft;
        float newDy = Math.round(targetTop / step) * step - scaledTop;

        float minDx = -scaledLeft;
        float maxDx = viewWidth - scaledRight;
        if (maxDx < minDx) {
            newDx = (viewWidth - (scaledRight - scaledLeft)) / 2f - scaledLeft;
        } else {
            newDx = clamp(newDx, minDx, maxDx);
        }
        float minDy = -scaledTop;
        float maxDy = viewHeight - scaledBottom;
        if (maxDy < minDy) {
            newDy = (viewHeight - (scaledBottom - scaledTop)) / 2f - scaledTop;
        } else {
            newDy = clamp(newDy, minDy, maxDy);
        }
        layout.dx = newDx / viewWidth;
        layout.dy = newDy / viewHeight;
        computeZones();
        postInvalidate();
    }

    private void updateEditResizeFromX(float x) {
        if (editSelectedGroup < 0 || editResizeTrack.width() <= 0f) {
            return;
        }
        float fraction = (x - editResizeTrack.left) / editResizeTrack.width();
        fraction = clamp(fraction, 0f, 1f);
        ButtonLayout layout = editButtonLayouts[editSelectedGroup];
        layout.scale = BUTTON_SCALE_MIN + fraction * (BUTTON_SCALE_MAX - BUTTON_SCALE_MIN);
        computeZones();
        postInvalidate();
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
            if (previous == null || previous == CTRL_NONE || previous != control) {
                // Haptic tick only when a new button is pressed, not while a
                // finger stays on the same button.
                ButtonHaptics.tick(getContext());
            }
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
