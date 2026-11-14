package opendoja.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/**
 * Small in-app keyboard designed for D-pad / remote control use.
 *
 * <p>The system IME cannot be driven reliably by a remote, so Rename and
 * Search use this view instead. D-pad left/right/up/down move the highlighted
 * key, Center/Enter types it, Backspace deletes and {@code OK} submits.</p>
 */
final class DpadKeyboardView extends View {

    interface Listener {
        void onText(String text);

        void onDelete();

        void onSubmit();

        void onCancel();
    }

    private static final int ACTION_CHAR = 0;
    private static final int ACTION_SHIFT = 1;
    private static final int ACTION_DELETE = 2;
    private static final int ACTION_MODE = 3;
    private static final int ACTION_SPACE = 4;
    private static final int ACTION_OK = 5;
    private static final int ACTION_CANCEL = 6;

    private static final class Key {
        final String label;
        final String value;
        final int action;
        final float weight;

        Key(String label, String value, int action, float weight) {
            this.label = label;
            this.value = value;
            this.action = action;
            this.weight = weight;
        }
    }

    private static Key character(String value) {
        return new Key(value, value, ACTION_CHAR, 1f);
    }

    private static Key action(String label, int action, float weight) {
        return new Key(label, "", action, weight);
    }

    private static Key[][] letters() {
        return new Key[][]{
                {
                        character("q"), character("w"), character("e"), character("r"),
                        character("t"), character("y"), character("u"), character("i"),
                        character("o"), character("p"),
                },
                {
                        character("a"), character("s"), character("d"), character("f"),
                        character("g"), character("h"), character("j"), character("k"),
                        character("l"),
                },
                {
                        action("SHIFT", ACTION_SHIFT, 1.7f),
                        character("z"), character("x"), character("c"), character("v"),
                        character("b"), character("n"), character("m"),
                        action("DEL", ACTION_DELETE, 1.7f),
                },
                {
                        action("123", ACTION_MODE, 1.7f),
                        action("SPACE", ACTION_SPACE, 4.6f),
                        action("OK", ACTION_OK, 1.7f),
                        action("Cancel", ACTION_CANCEL, 2.0f),
                },
        };
    }

    private static Key[][] symbols() {
        return new Key[][]{
                {
                        character("1"), character("2"), character("3"), character("4"),
                        character("5"), character("6"), character("7"), character("8"),
                        character("9"), character("0"),
                },
                {
                        character("-"), character("_"), character("."), character(","),
                        character("?"), character("!"), character("@"), character("#"),
                        character("$"),
                },
                {
                        character("/"), character(":"), character(";"), character("("),
                        character(")"), character("&"), character("+"), character("="),
                        character("%"),
                },
                {
                        action("ABC", ACTION_MODE, 1.7f),
                        action("SPACE", ACTION_SPACE, 3.4f),
                        action("DEL", ACTION_DELETE, 1.7f),
                        action("OK", ACTION_OK, 1.7f),
                        action("Cancel", ACTION_CANCEL, 2.0f),
                },
        };
    }

    private final Paint backgroundPaint = new Paint();
    private final Paint accentPaint = new Paint();
    private final Paint keyFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint keyBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint keyTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF keyRect = new RectF();

    private Listener listener;
    private Key[][] rows = letters();
    private boolean symbolMode;
    private boolean shifted;
    private int focusRow;
    private int focusCol;

    DpadKeyboardView(Context context) {
        super(context);
        setClickable(true);
        setFocusable(false);
        float density = getResources().getDisplayMetrics().density;
        backgroundPaint.setColor(0xF0101218);
        accentPaint.setColor(0xFF3B82F6);
        keyFillPaint.setStyle(Paint.Style.FILL);
        keyBorderPaint.setStyle(Paint.Style.STROKE);
        keyTextPaint.setColor(0xFFF5F7FA);
        keyTextPaint.setTextAlign(Paint.Align.CENTER);
        keyTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        keyTextPaint.setAntiAlias(true);
        keyBorderPaint.setStrokeWidth(Math.max(1f, density));
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY) {
            height = Math.round(300 * getResources().getDisplayMetrics().density);
        }
        setMeasuredDimension(width, height);
    }

    private float horizontalPadding() {
        return dp(6);
    }

    private float verticalPadding() {
        return dp(6);
    }

    private float keyGap() {
        return dp(4);
    }

    private float rowHeight() {
        int rowCount = rows.length;
        float available = getHeight() - verticalPadding() * 2f - keyGap() * (rowCount - 1);
        return Math.max(1f, available / rowCount);
    }

    private float rowWeight(int row) {
        float total = 0f;
        for (Key key : rows[row]) {
            total += key.weight;
        }
        return Math.max(0.001f, total);
    }

    private float keyWidth(int row, int col) {
        float available = getWidth() - horizontalPadding() * 2f
                - keyGap() * (rows[row].length - 1);
        return Math.max(1f, available * rows[row][col].weight / rowWeight(row));
    }

    private float keyCenterX(int row, int col) {
        float x = horizontalPadding();
        for (int i = 0; i < col; i++) {
            x += keyWidth(row, i) + keyGap();
        }
        return x + keyWidth(row, col) / 2f;
    }

    private void rectFor(int row, int col, RectF out) {
        float x = horizontalPadding();
        for (int i = 0; i < col; i++) {
            x += keyWidth(row, i) + keyGap();
        }
        float y = verticalPadding() + row * (rowHeight() + keyGap());
        out.set(x, y, x + keyWidth(row, col), y + rowHeight());
    }

    private String labelFor(Key key) {
        switch (key.action) {
            case ACTION_OK:
                return getContext().getString(R.string.edit_buttons_ok);
            case ACTION_CANCEL:
                return getContext().getString(R.string.edit_buttons_cancel);
            case ACTION_SHIFT:
                return shifted ? "SHIFT*" : "SHIFT";
            default:
                if (key.action == ACTION_CHAR && shifted) {
                    return key.label.toUpperCase(Locale.ROOT);
                }
                return key.label;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        if (width <= 0f || height <= 0f) {
            return;
        }
        canvas.drawRect(0f, 0f, width, height, backgroundPaint);
        canvas.drawRect(0f, 0f, width, Math.max(2f, dp(2)), accentPaint);

        float rowHeight = rowHeight();
        float textSize = Math.min(rowHeight * 0.40f, dp(19));
        for (int row = 0; row < rows.length; row++) {
            for (int col = 0; col < rows[row].length; col++) {
                Key key = rows[row][col];
                rectFor(row, col, keyRect);
                boolean focused = row == focusRow && col == focusCol;
                boolean toggled = (key.action == ACTION_SHIFT && shifted)
                        || (key.action == ACTION_MODE && symbolMode);
                keyFillPaint.setColor(focused ? 0x663B82F6 : (toggled ? 0x5534A853 : 0xFF262A33));
                keyBorderPaint.setColor(focused ? 0xFF60A5FA : 0x55FFFFFF);
                keyBorderPaint.setStrokeWidth(focused ? Math.max(2f, dp(1.6f)) : Math.max(1f, dp(1f)));
                float radius = Math.max(3f, dp(4));
                canvas.drawRoundRect(keyRect, radius, radius, keyFillPaint);
                canvas.drawRoundRect(keyRect, radius, radius, keyBorderPaint);

                String label = labelFor(key);
                keyTextPaint.setTextSize(textSize);
                float labelWidth = keyTextPaint.measureText(label);
                float maxWidth = keyRect.width() * 0.88f;
                if (labelWidth > maxWidth && labelWidth > 0f) {
                    keyTextPaint.setTextSize(textSize * maxWidth / labelWidth);
                }
                Paint.FontMetrics metrics = keyTextPaint.getFontMetrics();
                float baseline = keyRect.centerY() - (metrics.ascent + metrics.descent) / 2f;
                canvas.drawText(label, keyRect.centerX(), baseline, keyTextPaint);
            }
        }
    }

    private void moveFocus(int dx, int dy) {
        if (dy == 0) {
            focusCol = Math.max(0, Math.min(rows[focusRow].length - 1, focusCol + dx));
        } else {
            int targetRow = Math.max(0, Math.min(rows.length - 1, focusRow + dy));
            if (targetRow != focusRow) {
                float center = keyCenterX(focusRow, focusCol);
                int bestCol = 0;
                float bestDistance = Float.MAX_VALUE;
                for (int col = 0; col < rows[targetRow].length; col++) {
                    float distance = Math.abs(keyCenterX(targetRow, col) - center);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestCol = col;
                    }
                }
                focusRow = targetRow;
                focusCol = bestCol;
            }
        }
        invalidate();
    }

    private void activateFocused() {
        if (focusRow < 0 || focusRow >= rows.length
                || focusCol < 0 || focusCol >= rows[focusRow].length) {
            return;
        }
        activate(rows[focusRow][focusCol]);
    }

    private void activate(Key key) {
        ButtonHaptics.tick(getContext());
        switch (key.action) {
            case ACTION_CHAR: {
                String value = shifted ? key.value.toUpperCase(Locale.ROOT) : key.value;
                if (listener != null) {
                    listener.onText(value);
                }
                if (shifted) {
                    shifted = false;
                    invalidate();
                }
                break;
            }
            case ACTION_SPACE:
                if (listener != null) {
                    listener.onText(" ");
                }
                break;
            case ACTION_DELETE:
                if (listener != null) {
                    listener.onDelete();
                }
                break;
            case ACTION_SHIFT:
                shifted = !shifted;
                invalidate();
                break;
            case ACTION_MODE:
                symbolMode = !symbolMode;
                rows = symbolMode ? symbols() : letters();
                focusRow = 0;
                focusCol = 0;
                shifted = false;
                invalidate();
                break;
            case ACTION_OK:
                if (listener != null) {
                    listener.onSubmit();
                }
                break;
            case ACTION_CANCEL:
                if (listener != null) {
                    listener.onCancel();
                }
                break;
            default:
                break;
        }
    }

    private static boolean isNavigationKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    private static boolean isConfirmKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                || keyCode == KeyEvent.KEYCODE_BUTTON_A;
    }

    /**
     * Routes a key event to the keyboard. Returns true when the key was used
     * by the keyboard and should not reach the rest of the activity.
     */
    boolean handleKeyEvent(KeyEvent event) {
        if (event == null) {
            return false;
        }
        int keyCode = event.getKeyCode();
        int action = event.getAction();
        if (action == KeyEvent.ACTION_UP) {
            if (isNavigationKey(keyCode) || isConfirmKey(keyCode)
                    || keyCode == KeyEvent.KEYCODE_DEL
                    || keyCode == KeyEvent.KEYCODE_FORWARD_DEL
                    || keyCode == KeyEvent.KEYCODE_SPACE) {
                return true;
            }
            int unicode = event.getUnicodeChar(event.getMetaState());
            return unicode >= 0x20 && unicode != 0x7F;
        }
        if (action != KeyEvent.ACTION_DOWN) {
            return false;
        }
        boolean repeating = event.getRepeatCount() > 0;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (!repeating) {
                    moveFocus(-1, 0);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (!repeating) {
                    moveFocus(1, 0);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (!repeating) {
                    moveFocus(0, -1);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (!repeating) {
                    moveFocus(0, 1);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (!repeating) {
                    activateFocused();
                }
                return true;
            case KeyEvent.KEYCODE_DEL:
            case KeyEvent.KEYCODE_FORWARD_DEL:
                if (listener != null) {
                    listener.onDelete();
                }
                return true;
            case KeyEvent.KEYCODE_SPACE:
                if (!repeating && listener != null) {
                    listener.onText(" ");
                    ButtonHaptics.tick(getContext());
                }
                return true;
            default:
                break;
        }
        int unicode = event.getUnicodeChar(event.getMetaState());
        if (unicode >= 0x20 && unicode != 0x7F) {
            if (!repeating && listener != null) {
                listener.onText(new String(Character.toChars(unicode)));
                ButtonHaptics.tick(getContext());
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            if (focusAt(event.getX(), event.getY())) {
                return true;
            }
            return super.onTouchEvent(event);
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            int row = focusRow;
            int col = focusCol;
            if (focusAt(event.getX(), event.getY()) && row == focusRow && col == focusCol) {
                activateFocused();
                return true;
            }
            return true;
        }
        return event.getActionMasked() == MotionEvent.ACTION_MOVE
                || super.onTouchEvent(event);
    }

    private boolean focusAt(float x, float y) {
        for (int row = 0; row < rows.length; row++) {
            for (int col = 0; col < rows[row].length; col++) {
                rectFor(row, col, keyRect);
                if (keyRect.contains(x, y)) {
                    focusRow = row;
                    focusCol = col;
                    invalidate();
                    return true;
                }
            }
        }
        return false;
    }
}
