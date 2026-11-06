package opendoja.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.Log;
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
    private int[] upPixels;
    private int[] upSrcPixels;
    private int[] upColSrc;
    private int[] upRowSrc;
    private int upSrcWidth;
    private int upSrcHeight;
    private int upscaledVersion = -1;
    private boolean upscaleLogged = false;

    private final RectF gameRect = new RectF();
    private final RectF padRect = new RectF();
    private int gamePixelW;
    private int gamePixelH;

    private boolean rotated;
    private int rotationAngle;
    private boolean keypadVisible = true;

    private int lastPointerX;
    private int lastPointerY;

    private JamInputController input;

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

    private void init() {
        setFocusable(false);
        setFocusableInTouchMode(false);
        keyFillPaint.setStyle(Paint.Style.FILL);
        keyStrokePaint.setStyle(Paint.Style.STROKE);
        keyStrokePaint.setStrokeWidth(2f);
        keyTextPaint.setColor(0xFFFFFFFF);
        keyTextPaint.setTextAlign(Paint.Align.CENTER);
        keyTextPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
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
        upscaleBitmap = null;
        upPixels = null;
        upSrcPixels = null;
        upColSrc = null;
        upRowSrc = null;
        upSrcWidth = 0;
        upSrcHeight = 0;
        upscaledVersion = -1;
        upscaleLogged = false;
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
        if (bmp != null && !bmp.isRecycled()) {
            drawGameUpscaled(canvas, bmp, sourceVersion);
        }
        if (keypadVisible) {
            drawKeypad(canvas);
        }
        if (rotationAngle != 0) {
            canvas.restore();
        }
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
        int gameW, gameH;
        synchronized (lock) {
            gameW = screenWidth > 0 ? screenWidth : 240;
            gameH = screenHeight > 0 ? screenHeight : 240;
        }
        boolean fullScreen = isFullScreenMode();
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
            return;
        }
        if (fullScreen) {
            float bandTop = viewHeight - padHeight;
            padRect.set(0f, bandTop, viewWidth, viewHeight);
        } else {
            float bandTop = viewHeight - padHeight;
            padRect.set(0f, bandTop, viewWidth, viewHeight);
        }
        float bandTop = viewHeight - padHeight;
        padRect.set(0f, bandTop, viewWidth, viewHeight);

        float pad = Math.min(padRect.width(), padRect.height()) * 0.05f;
        float innerLeft = padRect.left + pad;
        float innerTop = padRect.top + pad;
        float innerRight = padRect.right - pad;
        float innerBottom = padRect.bottom - pad;
        float innerWidth = innerRight - innerLeft;
        float innerHeight = innerBottom - innerTop;
        if (innerWidth <= 0f || innerHeight <= 0f) return;

        float gap = innerWidth * 0.018f;
        float dpadWidth = innerWidth * 0.34f;
        float softWidth = innerWidth * 0.22f;
        float dialWidth = innerWidth - dpadWidth - softWidth - 2f * gap;

        float side = Math.min(innerHeight, dpadWidth);
        float centerDx = innerLeft + dpadWidth / 2f;
        float centerDy = innerTop + innerHeight / 2f;
        float arm = side / 3f;
        upKey.rect.set(centerDx - arm / 2f, centerDy - side / 2f, centerDx + arm / 2f, centerDy);
        downKey.rect.set(centerDx - arm / 2f, centerDy, centerDx + arm / 2f, centerDy + side / 2f);
        leftKey.rect.set(centerDx - side / 2f, centerDy - arm / 2f, centerDx, centerDy + arm / 2f);
        rightKey.rect.set(centerDx, centerDy - arm / 2f, centerDx + side / 2f, centerDy + arm / 2f);
        selectKey.rect.set(centerDx - arm / 2f, centerDy - arm / 2f, centerDx + arm / 2f, centerDy + arm / 2f);

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
        float cellWidth = dialWidth / 3f;
        inset = Math.min(cellWidth, cellHeight) * 0.05f;
        for (int i = 0; i < DIAL_LABELS.length; i++) {
            int col = i % 3;
            int row = i / 3;
            KeyDef def = keysById.get(CTRL_DIAL_BASE + i);
            if (def == null) continue;
            def.rect.set(dialLeft + col * cellWidth + inset, innerTop + row * cellHeight + inset,
                    dialLeft + (col + 1) * cellWidth - inset, innerTop + (row + 1) * cellHeight - inset);
        }
    }

    private void computeZonesLandscape() {
        float viewWidth = rotated ? getHeight() : getWidth();
        float viewHeight = rotated ? getWidth() : getHeight();
        if (viewWidth <= 0f || viewHeight <= 0f) return;
        int gameW, gameH;
        synchronized (lock) {
            gameW = screenWidth > 0 ? screenWidth : 240;
            gameH = screenHeight > 0 ? screenHeight : 240;
        }
        boolean fullScreen = isFullScreenMode();
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

        padRect.set(viewWidth - rightW, 0f, viewWidth, viewHeight);

        if (!keypadVisible) {
            padRect.set(0f, 0f, 0f, 0f);
            return;
        }

        float density = getResources().getDisplayMetrics().density;
        float leftPad = 6f * density;
        float innerLeft = viewWidth - rightW + leftPad;
        float innerTop = leftPad;
        float innerRight = viewWidth - leftPad;
        float innerBottom = viewHeight - leftPad;
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
        float arm = dpadSize / 3f;
        upKey.rect.set(cx - arm / 2f, cy - dpadSize / 2f, cx + arm / 2f, cy);
        downKey.rect.set(cx - arm / 2f, cy, cx + arm / 2f, cy + dpadSize / 2f);
        leftKey.rect.set(cx - dpadSize / 2f, cy - arm / 2f, cx, cy + arm / 2f);
        rightKey.rect.set(cx, cy - arm / 2f, cx + dpadSize / 2f, cy + arm / 2f);
        selectKey.rect.set(cx - arm / 2f, cy - arm / 2f, cx + arm / 2f, cy + arm / 2f);
    }

    private int computeRotationAngle() {
        android.content.SharedPreferences prefs = getContext().getSharedPreferences(DisplayActivity.PREFS_NAME, android.content.Context.MODE_PRIVATE);
        String gameId = EntryActivity.gameId;
        String orientation;
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
        android.content.SharedPreferences prefs = getContext().getSharedPreferences(DisplayActivity.PREFS_NAME, android.content.Context.MODE_PRIVATE);
        String gameId = EntryActivity.gameId;
        String mode;
        if (gameId != null && !gameId.isEmpty()) {
            String override = prefs.getString(gameId + "_" + DisplayActivity.KEY_DISPLAY_MODE, DisplayActivity.INHERIT);
            if (!override.equals(DisplayActivity.INHERIT)) {
                mode = override;
            } else {
                mode = prefs.getString(DisplayActivity.KEY_DISPLAY_MODE, DisplayActivity.MODE_ORIGINAL);
            }
        } else {
            mode = prefs.getString(DisplayActivity.KEY_DISPLAY_MODE, DisplayActivity.MODE_ORIGINAL);
        }
        return DisplayActivity.MODE_FULLSCREEN.equals(mode);
    }

    private void drawGameUpscaled(Canvas canvas, Bitmap bmp, int sourceVersion) {
        int dstLeft = Math.round(gameRect.left);
        int dstTop = Math.round(gameRect.top);
        int dstWidth = Math.max(1, Math.round(gameRect.width()));
        int dstHeight = Math.max(1, Math.round(gameRect.height()));
        int srcWidth = bmp.getWidth();
        int srcHeight = bmp.getHeight();
        if (srcWidth <= 0 || srcHeight <= 0) return;
        Bitmap scaled = upscaleBitmap;
        if (scaled == null || scaled.isRecycled() || scaled.getWidth() != dstWidth
                || scaled.getHeight() != dstHeight || upSrcWidth != srcWidth || upSrcHeight != srcHeight) {
            scaled = Bitmap.createBitmap(dstWidth, dstHeight, Bitmap.Config.ARGB_8888);
            upscaleBitmap = scaled;
            upPixels = new int[dstWidth * dstHeight];
            upSrcPixels = new int[srcWidth * srcHeight];
            upColSrc = new int[dstWidth];
            for (int x = 0; x < dstWidth; x++) upColSrc[x] = (int) ((long) x * srcWidth / dstWidth);
            upRowSrc = new int[dstHeight];
            for (int y = 0; y < dstHeight; y++) upRowSrc[y] = (int) ((long) y * srcHeight / dstHeight);
            upSrcWidth = srcWidth;
            upSrcHeight = srcHeight;
            upscaledVersion = -1;
        }
        if (upscaledVersion != sourceVersion) {
            bmp.getPixels(upSrcPixels, 0, srcWidth, 0, 0, srcWidth, srcHeight);
            if (dstWidth == srcWidth && dstHeight == srcHeight) {
                System.arraycopy(upSrcPixels, 0, upPixels, 0, srcWidth * srcHeight);
            } else {
                int dstRow = 0;
                for (int y = 0; y < dstHeight; y++) {
                    int srcRow = upRowSrc[y] * srcWidth;
                    int out = dstRow;
                    for (int x = 0; x < dstWidth; x++) {
                        upPixels[out++] = upSrcPixels[srcRow + upColSrc[x]];
                    }
                    dstRow += dstWidth;
                }
            }
            scaled.setPixels(upPixels, 0, dstWidth, 0, 0, dstWidth, dstHeight);
            upscaledVersion = sourceVersion;
        }
        canvas.drawBitmap(scaled, dstLeft, dstTop, null);
    }

    private void drawKeypad(Canvas canvas) {
        if (padRect.height() <= 0f) return;
        for (KeyDef def : keys) {
            drawKey(canvas, def);
        }
    }

    private void drawKey(Canvas canvas, KeyDef def) {
        RectF rect = def.rect;
        if (rect.width() <= 0f || rect.height() <= 0f) return;
        float radius = Math.min(rect.width(), rect.height()) * 0.18f;
        boolean pressed = activeControls.contains(def.id);
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

    private int hitTest(float x, float y) {
        if (selectKey.rect.contains(x, y)) return CTRL_SELECT;
        for (KeyDef def : keys) {
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
}
