package opendoja.android;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.SharedPreferences;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.opengl.GLSurfaceView;
import android.os.BatteryManager;
import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.content.ContentResolver;
import android.content.res.AssetFileDescriptor;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.ScrollView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import opendoja.host.JamMetadataResolver;
import opendoja.host.OpenDoJaLog;

/**
 * Launcher: title bar with an options menu (load JAM / key mapping / delete
 * game) plus a PPI-scaled grid of every installed i-mode game. Tapping a tile
 * launches it; icons come from the JAM AppIcon entry inside the game jar.
 */
public class HomeActivity extends Activity {
    private static final String TAG = "OpenDoJa";
    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    private static final int REQUEST_CHANGE_ICON = 2;
    private static final int REQUEST_IMPORT_FOLDER = 1;
    private static final int REQUEST_EXPORT_SAVE = 3;
    private static final int REQUEST_IMPORT_SAVE = 4;
    private static final int REQUEST_IMPORT_SD_DATA = 5;
    private static final int REQUEST_STORAGE_PERMISSION = 9001;
    private File pendingSdDataGame;
    // Shared by every built-in picker. If the user has to grant storage
    // access first, the action is held here and replayed when they return.
    private Runnable pendingStorageAction;
    private Runnable pendingSafAction;
    private boolean pendingStorageWrite;
    private boolean waitingForStorageSettings;
    // Shown in diagnostics so we can confirm which build is actually installed.
    private static final String DIAG_BUILD = "2026-11-11 15:55 led-blink";
    // Save Diagnostics - Dev use only!! Enable/Disable
    // false hides the menu item entirely; captureDiagnostics() stays in the code.
    private static final boolean SAVE_DIAGNOSTICS_ENABLED = false;
    private static final int MENU_INDEX_SAVE_DIAGNOSTICS = 6;
    private GameEntry pendingIconGame;
    private GameEntry pendingSaveGame;
    private String[] menuItems;
    private String[] gameOptions;

    private final Map<String, Bitmap> iconCache = new ConcurrentHashMap<>();
    private Bitmap defaultGameIcon;
    private ExecutorService worker = Executors.newFixedThreadPool(2);
    private EditText searchBox;
    private TextView titleView;
    private String searchQuery = "";
    private LinearLayout gridContainer;
    private TextView emptyView;
    private PopupWindow menuPopup;
    private int columns = 4;
    private int iconSizePx;
    private View fixedTopBar;
    private ImageView wifiIcon;
    private ImageView batteryIcon;
    private ImageView mascotIcon;
    private TextView searchButton;
    private TextView viewToggleButton;
    private TextView editButton;
    private TextView menuButton;
    // In-app D-pad keyboard used by Search and Rename instead of the system IME.
    private FrameLayout rootView;
    private DpadKeyboardView searchKeyboard;
    private FrameLayout renameOverlay;
    private DpadKeyboardView renameKeyboard;
    private TextView renameDisplay;
    private GameEntry renameTarget;
    private String renameText = "";
    // -1 = game grid has D-pad focus; 0/1/2/3 = Search / view toggle / edit / menu.
    private int topBarFocusIndex = -1;
    private TextView timeView;
    private float topBarScale = 1f;
    private BroadcastReceiver wifiReceiver;
    private BroadcastReceiver batteryReceiver;
    private java.util.Timer timeTimer;
    private android.os.Handler mascotHandler;
    private Runnable mascotRunnable;

    private static final String HOME_PREFS = "home";
    private static final String KEY_LAST_GAME = "lastGamePath";
    private static final String KEY_GAME_ORDER = "gameOrder";
    private static final long NAV_DUPLICATE_DOWN_MS = 60L;
    // Slightly shorter than the platform timeout so a remote's key-up never
    // beats the long-press handler.
    private static final long NAV_LONG_PRESS_MS = 400L;

    // The pixel font is loaded once per process. createFromAsset parses the
    // TTF on every call, so caching avoids re-parsing per game-list row.
    private static android.graphics.Typeface pixelifyBold;
    private static android.graphics.Typeface pixelifyRegular;

    private static synchronized android.graphics.Typeface pixelifyTypeface(android.content.Context context, boolean bold) {
        if (bold) {
            if (pixelifyBold == null) {
                pixelifyBold = android.graphics.Typeface.createFromAsset(context.getAssets(), "fonts/PixelifySans-Bold.ttf");
            }
            return pixelifyBold;
        }
        if (pixelifyRegular == null) {
            pixelifyRegular = android.graphics.Typeface.createFromAsset(context.getAssets(), "fonts/PixelifySans-Regular.ttf");
        }
        return pixelifyRegular;
    }

    private ScrollView gameScrollView;
    private final List<GameEntry> currentGames = new ArrayList<>();
    private final Map<String, View> gameItemViews = new LinkedHashMap<>();
    private String selectedGamePath;
    private long lastNavDownTime = 0L;
    private int lastNavDownKey = 0;
    // Remote/gamepad confirm key: short press opens the selected game, holding
    // it opens the game options menu (same as long-pressing a game tile).
    private android.os.Handler navKeyHandler;
    private Runnable navSelectLongPressRunnable;
    private boolean navSelectPressed;
    private boolean navSelectLongPressed;
    private SoundPool navSoundPool;
    private int navSoundId = 0;
    private boolean navSoundReady = false;

    // ---- Layout edit mode ----
    // While editMode is on, tiles wobble and long-press / Select picks one up.
    // The new order stays in memory until the user confirms Save; Discard
    // simply rescans, which reapplies the previously persisted order.
    private boolean editMode = false;
    private boolean editOrderDirty = false;
    private boolean pendingEditEntrance = false;
    private final Map<String, Animator> wobbleAnimators = new LinkedHashMap<>();
    private float lastTouchRawX = 0f;
    private float lastTouchRawY = 0f;
    private ImageView dragOverlay;
    private int dragOverlaySizePx = 0;
    private String dragGamePath;
    private String dragHoverPath;
    private View dragSourceView;
    private boolean controllerDragging = false;
    private boolean controllerDragMoved = false;
    private String controllerDragPath;

    public static HomeActivity instance;
    public static volatile boolean bgmSuppressed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Tapping the launcher icon while the app's task already exists makes
        // Android push another HomeActivity on top of the current screen (for
        // example a hibernating game) with LAUNCH_MULTIPLE. Reopening from
        // Recents instead brings the task forward. Return to the screen that
        // was already showing so the game stays alive and resumes with its
        // paused overlay.
        if (!isTaskRoot()) {
            opendoja.host.OpenDoJaLog.info(HomeActivity.class,
                    "HomeActivity.onCreate: stacked launch on task " + getTaskId()
                            + "; returning to existing screen");
            finish();
            return;
        }
        DisplayActivity.applyShippingDefaults(this);
        instance = this;
        // Cancel any stale restart alarm from the previous process-restart implementation
        try {
            Intent restart = new Intent(this, HomeActivity.class);
            restart.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                    this, 0, restart, android.app.PendingIntent.FLAG_ONE_SHOT | android.app.PendingIntent.FLAG_IMMUTABLE);
            android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
            if (am != null) {
                am.cancel(pi);
            }
            pi.cancel();
        } catch (Exception ignored) {
        }
        opendoja.host.OpenDoJaLog.info(HomeActivity.class, "HomeActivity.onCreate pid=" + android.os.Process.myPid() + " taskId=" + getTaskId());
        applyDisplayOrientation(this);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(themeSurfaceColor()));
        computeGridMetrics();

        menuItems = getResources().getStringArray(R.array.home_menu_items);
        gameOptions = getResources().getStringArray(R.array.game_options);

        FrameLayout root = new FrameLayout(this);

        GLSurfaceView glView = new GLSurfaceView(this);
        glView.setClickable(false);
        glView.setFocusable(false);
        glView.setEGLContextClientVersion(2);
        glView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        glView.getHolder().setFormat(android.graphics.PixelFormat.TRANSLUCENT);
        glView.setZOrderOnTop(false);
        ThemeRenderer renderer = new ThemeRenderer(this);
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        SharedPreferences themePrefs = getSharedPreferences(ThemeActivity.PREFS_NAME, MODE_PRIVATE);
        String theme = themePrefs.getString(ThemeActivity.KEY_THEME, "flow");
        renderer.setTheme(theme);
        appliedGlTheme = theme;
        FrameLayout.LayoutParams glParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        root.addView(glView, glParams);

        this.glView = glView;
        this.themeRenderer = renderer;

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        // Add top padding to account for fixed top bar
        content.setPadding(dp(12), dp(56) + dp(12), dp(12), dp(12));

        emptyView = new TextView(this);
        emptyView.setText("No installed games\nUse the top-right menu to Import .JAM");
        emptyView.setTextColor(0xFFBBBBBB);
        emptyView.setTextSize(15f);
        emptyView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams emptyParams = matchWrap();
        emptyParams.topMargin = dp(96);
        content.addView(emptyView, emptyParams);

        gridContainer = new LinearLayout(this);
        gridContainer.setOrientation(LinearLayout.VERTICAL);
        gridContainer.setPadding(0, dp(8), 0, 0);
        gridContainer.setFocusable(true);
        gridContainer.setFocusableInTouchMode(true);
        gridContainer.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        FocusHighlight.disable(gridContainer);
        content.addView(gridContainer, matchWrap());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        // Keep the ScrollView out of the focus chain so DPad events are handled
        // by the activity instead of being consumed as scrolling.
        scroll.setFocusable(false);
        scroll.setFocusableInTouchMode(false);
        gameScrollView = scroll;
        scroll.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Build fixed top bar (stays at top, doesn't scroll) - add LAST so it's on top
        fixedTopBar = buildFixedTopBar();
        FrameLayout.LayoutParams topBarParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(56));
        topBarParams.gravity = Gravity.TOP;
        root.addView(fixedTopBar, topBarParams);

        rootView = root;
        searchKeyboard = buildSearchKeyboard();
        FrameLayout.LayoutParams keyboardParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, keyboardHeightPx());
        keyboardParams.gravity = Gravity.BOTTOM;
        searchKeyboard.setVisibility(View.GONE);
        root.addView(searchKeyboard, keyboardParams);

        // All import/icon pickers use the built-in CustomFilePicker.
        setContentView(root);
        initNavSound();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // A stacked launcher launch finishes in onCreate() before the UI is
        // built; if the framework still delivers onResume, do not touch the
        // uninitialized views.
        if (isFinishing()) {
            return;
        }
        ThemeRenderer.logEvent("Home.onResume glView=" + (glView != null)
                + " themeRenderer=" + (themeRenderer != null));
        applyDisplayOrientation(this);
        computeGridMetrics();
        applyTopBarScale();
        refreshGames();
        if (glView != null) {
            SharedPreferences themePrefs = getSharedPreferences(ThemeActivity.PREFS_NAME, MODE_PRIVATE);
            String theme = themePrefs.getString(ThemeActivity.KEY_THEME, "flow");
            // Rebuild the GL surface on resume. Reusing it meant onSurfaceCreated() was
            // sometimes never called after returning from a game (the surface was not
            // recreated), leaving the theme black. A fresh view always gets a new surface and
            // context. The old view is detached (its GL thread exits) and the new one uses
            // RENDERMODE_WHEN_DIRTY at ~20 fps, so this does not cause continuous rendering.
            android.view.ViewGroup parent = (android.view.ViewGroup) glView.getParent();
            if (parent != null) {
                parent.removeView(glView);
            }
            glView.onPause();
            glView = new GLSurfaceView(this);
            glView.setEGLContextClientVersion(2);
            glView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
            glView.getHolder().setFormat(android.graphics.PixelFormat.TRANSLUCENT);
            glView.setZOrderOnTop(false);
            glView.setClickable(false);
            glView.setFocusable(false);
            ThemeRenderer newRenderer = new ThemeRenderer(this);
            glView.setRenderer(newRenderer);
            glView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
            newRenderer.setTheme(theme);
            FrameLayout.LayoutParams glParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
            if (parent != null) {
                parent.addView(glView, 0, glParams);
            }
            this.glView = glView;
            this.themeRenderer = newRenderer;
            appliedGlTheme = theme;
            glView.onResume();
            glView.requestRender();
            startGlFrameLoop();
            android.util.Log.i("OpenDoJa", "HomeActivity.onResume: GLSurfaceView rebuilt theme=" + theme);
        }
        // Ensure fixed top bar stays on top and clickable
        if (fixedTopBar != null) {
            fixedTopBar.bringToFront();
            updateFixedTopBarTheme();
        }
        if (pendingStorageAction != null) {
            if (hasDirectStorageAccess(pendingStorageWrite)) {
                Runnable action = pendingStorageAction;
                clearPendingStorageAction();
                action.run();
            } else if (waitingForStorageSettings) {
                waitingForStorageSettings = false;
                clearPendingStorageAction();
                Toast.makeText(this, "Storage access was not granted", Toast.LENGTH_LONG).show();
            }
        }
        if (!gameRunning) playBgm();
    }

    @Override
    protected void onPause() {
        super.onPause();
        cancelNavSelectLongPress();
        navSelectPressed = false;
        navSelectLongPressed = false;
        // Do not leave a floating tile or an endless wobble running behind
        // another activity. The order itself stays in memory for Save.
        if (dragOverlay != null) {
            finishTouchDrag(null);
        }
        if (controllerDragging) {
            endControllerDrag();
        }
        stopWobbleAnimations();
        ThemeRenderer.logEvent("Home.onPause");
        stopGlFrameLoop();
        if (glView != null) {
            glView.onPause();
            opendoja.host.OpenDoJaLog.info(HomeActivity.class, "HomeActivity.onPause: glView.onPause() called");
        }
        stopBgm();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // If the window regains focus without a full resume (e.g. notification shade, lock
        // screen overlay) make sure the animated theme resumes drawing instead of staying black.
        if (hasFocus && glView != null) {
            glView.requestRender();
            startGlFrameLoop();
        }
    }

    /** Drives the animated home background at ~30 fps while the screen is visible. */
    private void startGlFrameLoop() {
        if (glView == null) {
            return;
        }
        if (glFrameHandler == null) {
            glFrameHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        }
        if (glFrameTick == null) {
            glFrameTick = new Runnable() {
                @Override
                public void run() {
                    try {
                        GLSurfaceView view = glView;
                        if (view != null) {
                            view.requestRender();
                        }
                    } catch (Throwable ignored) {
                        // A transient GL error must never kill the repeating frame loop,
                        // otherwise the animated background freezes/stays black.
                    } finally {
                        android.os.Handler handler = glFrameHandler;
                        if (handler != null) {
                            handler.postDelayed(this, GL_FRAME_INTERVAL_MS);
                        }
                    }
                }
            };
        }
        glFrameLoopActive = true;
        glFrameHandler.removeCallbacks(glFrameTick);
        glFrameHandler.postDelayed(glFrameTick, GL_FRAME_INTERVAL_MS);
    }

    private void stopGlFrameLoop() {
        glFrameLoopActive = false;
        if (glFrameHandler != null && glFrameTick != null) {
            glFrameHandler.removeCallbacks(glFrameTick);
        }
    }

    @Override
    protected void onDestroy() {
        removeDragOverlayNow();
        cancelWobbleAnimations();
        worker.shutdownNow();
        if (wifiReceiver != null) {
            try { unregisterReceiver(wifiReceiver); } catch (Exception ignored) {}
        }
        if (batteryReceiver != null) {
            try { unregisterReceiver(batteryReceiver); } catch (Exception ignored) {}
        }
        if (timeTimer != null) {
            timeTimer.cancel();
            timeTimer = null;
        }
        releaseNavSound();
        super.onDestroy();
    }

    private View buildFixedTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, 0, dp(4), 0);
        applyTopBarGradient(bar);

        titleView = new TextView(this);
        titleView.setText("Pocket DoJa");
        titleView.setTextColor(themeTextColor());
        titleView.setTextSize(24f);
        titleView.setTypeface(pixelifyTypeface(this, true));
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setGravity(Gravity.CENTER_VERTICAL);
        titleView.setPadding(dp(16), 0, dp(8), 0);
        bar.addView(titleView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));

        int indicatorSize = dp(33);
        int mascotSize = dp(48);

        // Mascot icon immediately after the title, before the flexible spacer.
        mascotIcon = new ImageView(this);
        mascotIcon.setLayoutParams(new LinearLayout.LayoutParams(mascotSize, mascotSize));
        mascotIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mascotIcon.setPadding(dp(8), 0, dp(8), 0);
        bar.addView(mascotIcon);

        // Push the clock and status indicators to the right edge.
        View titleSpacer = new View(this);
        bar.addView(titleSpacer, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        // Digital time before WiFi
        timeView = new TextView(this);
        timeView.setTextColor(themeTextColor());
        timeView.setTextSize(16f);
        timeView.setTypeface(pixelifyTypeface(this, false));
        timeView.setGravity(Gravity.CENTER_VERTICAL);
        timeView.setPadding(dp(4), 0, dp(8), 0);
        timeView.setSingleLine(true);
        bar.addView(timeView);

        // WiFi indicator (early Android 3D style) - with custom icon pack support
        wifiIcon = new ImageView(this);
        wifiIcon.setLayoutParams(new LinearLayout.LayoutParams(indicatorSize, indicatorSize));
        wifiIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        wifiIcon.setPadding(dp(8), 0, dp(4), 0);
        bar.addView(wifiIcon);

        // Battery indicator (early Android 3D style) - with custom icon pack support
        batteryIcon = new ImageView(this);
        batteryIcon.setLayoutParams(new LinearLayout.LayoutParams(indicatorSize, indicatorSize));
        batteryIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        batteryIcon.setPadding(dp(8), 0, dp(8), 0);
        bar.addView(batteryIcon);

        // Initialize and register receivers
        initStatusIndicators();
        // Start time updater
        startTimeUpdater();

        searchButton = new TextView(this);
        searchButton.setText("\uD83D\uDD0D");
        searchButton.setTextSize(20f);
        searchButton.setTextColor(themeTextColor());
        searchButton.setGravity(Gravity.CENTER);
        searchButton.setPadding(dp(8), 0, dp(8), 0);
        searchButton.setClickable(true);
        // Focus is tracked by the custom D-pad navigation, not the platform.
        // Keeping these buttons non-focusable prevents the system focus ring
        // (and focus getting stuck on the view toggle) on controller input.
        searchButton.setFocusable(false);
        FocusHighlight.disable(searchButton);
        searchButton.setOnClickListener(v -> toggleSearch());
        
        // Try to load custom search icon
        updateSearchIcon();

        searchBox = new EditText(this);
        searchBox.setHint("Search games");
        searchBox.setTextColor(themeTextColor());
        searchBox.setHintTextColor(0x80FFFFFF);
        searchBox.setSingleLine(true);
        FocusHighlight.disable(searchBox);
        // The built-in D-pad keyboard is the default; the Display setting can
        // hand text entry over to the device's installed IME instead.
        searchBox.setShowSoftInputOnFocus(DisplayActivity.isSystemKeyboardEnabled(this));
        searchBox.setVisibility(View.GONE);
        searchBox.setOnClickListener(v -> {
            if (useSystemKeyboard()) {
                searchBox.setShowSoftInputOnFocus(true);
                showSystemKeyboard(searchBox);
            } else {
                searchBox.setShowSoftInputOnFocus(false);
                showSearchKeyboard();
            }
        });
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchQuery = s == null ? "" : s.toString().trim();
                refreshGames();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        searchBox.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                    || actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.hideSoftInputFromWindow(searchBox.getWindowToken(), 0);
                }
                return true;
            }
            return false;
        });

        bar.addView(searchButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));
        bar.addView(searchBox, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        viewToggleButton = new TextView(this);
        viewToggleButton.setText("");
        viewToggleButton.setTextSize(22f);
        viewToggleButton.setTextColor(themeTextColor());
        viewToggleButton.setGravity(Gravity.CENTER);
        viewToggleButton.setPadding(dp(8), 0, dp(8), 0);
        viewToggleButton.setClickable(true);
        viewToggleButton.setFocusable(false);
        FocusHighlight.disable(viewToggleButton);
        viewToggleButton.setOnClickListener(v -> {
            setGridView(!isGridView());
            updateViewToggleIcon();
            refreshGames();
        });
        bar.addView(viewToggleButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));
        updateViewToggleIcon();

        editButton = new TextView(this);
        editButton.setText("");
        editButton.setTextSize(22f);
        editButton.setTextColor(themeTextColor());
        editButton.setGravity(Gravity.CENTER);
        editButton.setPadding(dp(8), 0, dp(8), 0);
        editButton.setClickable(true);
        editButton.setFocusable(false);
        editButton.setContentDescription(getString(R.string.edit_layout));
        FocusHighlight.disable(editButton);
        editButton.setOnClickListener(v -> onEditButtonClicked());
        bar.addView(editButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));
        updateEditButtonAppearance();

        menuButton = new TextView(this);
        menuButton.setText("...");
        menuButton.setTextSize(26f);
        menuButton.setTextColor(themeTextColor());
        menuButton.setGravity(Gravity.CENTER);
        menuButton.setPadding(dp(16), 0, dp(16), 0);
        menuButton.setClickable(true);
        menuButton.setFocusable(false);
        FocusHighlight.disable(menuButton);
        menuButton.setBackground(new RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF), null, new ColorDrawable(0x1FFFFFFF)));
        menuButton.setOnClickListener(this::showOptionsMenu);
        bar.addView(menuButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));

        // Add a subtle bottom divider line for retro phone feel
        View divider = new View(this);
        divider.setBackgroundColor(0xFF3A3D45);
        FrameLayout.LayoutParams dividerParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(1));
        dividerParams.gravity = Gravity.BOTTOM;
        FrameLayout container = new FrameLayout(this);
        container.setClickable(true);
        container.setFocusable(false);
        FocusHighlight.disable(container);
        container.addView(bar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(56)));
        container.addView(divider, dividerParams);
        applyTopBarScale();
        return container;
    }

    /**
     * Recomputes the proportional scale from the live window width and applies
     * it to every top bar element so the bar always fits the current
     * orientation. The width is read from the window display rather than
     * Resources.getDisplayMetrics(), which can stay frozen to the metrics
     * captured when the LocaleHelper configuration context was created and
     * therefore report stale values after an orientation change.
     */
    private void applyTopBarScale() {
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(dm);
        float widthDp = dm.widthPixels / dm.density;
        topBarScale = Math.min(1f, Math.max(0.55f, widthDp / 600f));
        if (titleView != null) {
            titleView.setTextSize(24f * topBarScale);
            titleView.setPadding(scaledDp(16, topBarScale), 0, scaledDp(8, topBarScale), 0);
        }
        int indicatorSize = scaledDp(33, topBarScale);
        int mascotSize = scaledDp(48, topBarScale);
        if (mascotIcon != null) {
            mascotIcon.setLayoutParams(new LinearLayout.LayoutParams(mascotSize, mascotSize));
            mascotIcon.setPadding(scaledDp(8, topBarScale), 0, scaledDp(8, topBarScale), 0);
        }
        if (timeView != null) {
            timeView.setTextSize(16f * topBarScale);
            timeView.setPadding(scaledDp(4, topBarScale), 0, scaledDp(8, topBarScale), 0);
        }
        if (wifiIcon != null) {
            wifiIcon.setLayoutParams(new LinearLayout.LayoutParams(indicatorSize, indicatorSize));
            wifiIcon.setPadding(scaledDp(8, topBarScale), 0, scaledDp(4, topBarScale), 0);
        }
        if (batteryIcon != null) {
            batteryIcon.setLayoutParams(new LinearLayout.LayoutParams(indicatorSize, indicatorSize));
            batteryIcon.setPadding(scaledDp(8, topBarScale), 0, scaledDp(8, topBarScale), 0);
        }
        if (searchButton != null) {
            searchButton.setTextSize(20f * topBarScale);
            searchButton.setPadding(scaledDp(8, topBarScale), 0, scaledDp(8, topBarScale), 0);
        }
        if (viewToggleButton != null) {
            viewToggleButton.setTextSize(22f * topBarScale);
            viewToggleButton.setPadding(scaledDp(8, topBarScale), 0, scaledDp(8, topBarScale), 0);
        }
        if (editButton != null) {
            editButton.setTextSize(22f * topBarScale);
            editButton.setPadding(scaledDp(8, topBarScale), 0, scaledDp(8, topBarScale), 0);
        }
        if (menuButton != null) {
            menuButton.setTextSize(26f * topBarScale);
            menuButton.setPadding(scaledDp(16, topBarScale), 0, scaledDp(16, topBarScale), 0);
        }
        if (fixedTopBar instanceof FrameLayout) {
            FrameLayout container = (FrameLayout) fixedTopBar;
            if (container.getChildCount() > 0 && container.getChildAt(0) instanceof LinearLayout) {
                LinearLayout bar = (LinearLayout) container.getChildAt(0);
                bar.setPadding(0, 0, scaledDp(4, topBarScale), 0);
            }
        }
        if (fixedTopBar != null) {
            fixedTopBar.requestLayout();
        }
    }

    private void applyTopBarGradient(View bar) {
        SharedPreferences prefs = getSharedPreferences(ThemeActivity.PREFS_NAME, MODE_PRIVATE);
        String theme = prefs.getString(ThemeActivity.KEY_THEME, "flow");
        android.graphics.drawable.GradientDrawable gradient = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                getTopBarGradientColors(theme));
        gradient.setCornerRadius(0);
        bar.setBackground(gradient);
    }

    private int[] getTopBarGradientColors(String theme) {
        switch (theme) {
            case "flow-dream":
                // Pink gradient for Flow Dream
                return new int[]{0xFFF8BBD0, 0xFFF48FB1, 0xFFEC407A};
            case "flow-azure":
                // Light blue gradient for Flow Azure
                return new int[]{0xFFB3E5FC, 0xFF81D4FA, 0xFF4FC3F7};
            case "flow-green":
                // Light tea green gradient for Flow Green
                return new int[]{0xFFDCEDC8, 0xFFC5E1A5, 0xFFAED581};
            case "flow-midnight":
                // Dark grey/midnight gradient for Flow Midnight
                return new int[]{0xFF37474F, 0xFF263238, 0xFF1A2327};
            default:
                // White/light gradient for Flow
                return new int[]{0xFFFFFFFF, 0xFFF5F5F5, 0xFFEEEEEE};
        }
    }

    private void initStatusIndicators() {
        // WiFi receiver
        wifiReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.d("PocketDoJa_WiFi", "WiFi broadcast received: " + intent.getAction());
                updateWifiIcon();
            }
        };
        IntentFilter wifiFilter = new IntentFilter();
        wifiFilter.addAction(WifiManager.RSSI_CHANGED_ACTION);
        wifiFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        wifiFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        registerInternalReceiver(wifiReceiver, wifiFilter);
        Log.d("PocketDoJa_WiFi", "WiFi receiver registered");

        // Battery receiver
        batteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.d("PocketDoJa_Battery", "Battery broadcast received: " + intent.getAction());
                updateBatteryIcon();
            }
        };
        IntentFilter batteryFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        registerInternalReceiver(batteryReceiver, batteryFilter);
        Log.d("PocketDoJa_Battery", "Battery receiver registered");

        // Initial update
        updateWifiIcon();
        updateBatteryIcon();
    }

    /**
     * Registers process-local receivers. RECEIVER_NOT_EXPORTED and the
     * three-argument overload are only available from API 33; Android 7.x uses
     * the two-argument form. These filters only carry protected system
     * broadcasts, so the older overload is sufficient there.
     */
    private void registerInternalReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
    }

    private void updateMascotIcon(int percent, boolean charging, boolean present) {
        if (mascotIcon == null) return;
        
        String webpName;
        String gifName;
        String pngName;
        if (!present || charging || percent >= 70) {
            webpName = "icons/happy.webp";
            gifName = "icons/happy.gif";
            pngName = "icons/happy.png";
        } else if (percent >= 40) {
            webpName = "icons/neutral.webp";
            gifName = "icons/neutral.gif";
            pngName = "icons/neutral.png";
        } else {
            webpName = "icons/sad.webp";
            gifName = "icons/sad.gif";
            pngName = "icons/sad.png";
        }
        
        // Try animated WebP on API 28+
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            try (InputStream is = getAssets().open(webpName)) {
                android.graphics.drawable.Drawable drawable = 
                    android.graphics.drawable.AnimatedImageDrawable.createFromStream(is, webpName);
                if (drawable instanceof android.graphics.drawable.AnimatedImageDrawable) {
                    android.graphics.drawable.AnimatedImageDrawable animated = (android.graphics.drawable.AnimatedImageDrawable) drawable;
                    mascotIcon.setImageDrawable(animated);
                    animated.start();
                    Log.d("PocketDoJa_Mascot", "Started animated WebP: " + webpName);
                    return;
                }
            } catch (Exception e) {
                Log.w("PocketDoJa_Mascot", "Failed to load animated WebP: " + webpName, e);
            }
        }
        
        // Try GIF on API 24-27 (using Movie class for animation)
        if (android.os.Build.VERSION.SDK_INT < 28 && android.os.Build.VERSION.SDK_INT >= 24) {
            try {
                // Use a custom GIFView for animation on older APIs
                playGifAnimation(gifName);
                return;
            } catch (Exception e) {
                Log.w("PocketDoJa_Mascot", "Failed to load GIF: " + gifName, e);
            }
        }
        
        // Fallback to static PNG
        Bitmap icon = loadCustomIcon(pngName);
        if (icon != null) {
            mascotIcon.setImageBitmap(icon);
            Log.d("PocketDoJa_Mascot", "Using static PNG: " + pngName);
        } else {
            Log.w("PocketDoJa_Mascot", "No mascot icon found for: " + pngName);
        }
    }

    private void playGifAnimation(String gifName) {
        try (InputStream is = getAssets().open(gifName)) {
            byte[] bytes = readAllBytes(is);
            android.graphics.Movie movie = android.graphics.Movie.decodeByteArray(bytes, 0, bytes.length);
            if (movie != null) {
                // Create custom ImageView that animates the GIF
                mascotIcon.setTag(movie);
                mascotIcon.setImageDrawable(new android.graphics.drawable.Drawable() {
                    private long startTime = android.os.SystemClock.uptimeMillis();
                    
                    @Override
                    public void draw(Canvas canvas) {
                        long now = android.os.SystemClock.uptimeMillis();
                        int relTime = (int) ((now - startTime) % movie.duration());
                        movie.setTime(relTime);
                        canvas.drawBitmap(
                            android.graphics.Bitmap.createBitmap(movie.width(), movie.height(), android.graphics.Bitmap.Config.ARGB_8888),
                            0, 0, null
                        );
                        movie.draw(canvas, 0, 0);
                        invalidateSelf();
                    }
                    
                    @Override
                    public void setAlpha(int alpha) {}
                    
                    @Override
                    public void setColorFilter(ColorFilter colorFilter) {}
                    
                    @Override
                    public int getOpacity() {
                        return android.graphics.PixelFormat.TRANSLUCENT;
                    }
                });
                Log.d("PocketDoJa_Mascot", "Started GIF animation: " + gifName);
            }
        } catch (Exception e) {
            Log.w("PocketDoJa_Mascot", "GIF animation failed: " + gifName, e);
        }
    }

    private void updateWifiIcon() {
        if (wifiIcon == null) return;
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager == null || !wifiManager.isWifiEnabled()) {
            Log.d("PocketDoJa_WiFi", "WiFi disabled or null manager");
            wifiIcon.setImageBitmap(loadCustomIcon("icons/wifi_no.png"));
            return;
        }
        int rssi = wifiManager.getConnectionInfo().getRssi();
        int level = WifiManager.calculateSignalLevel(rssi, 4);
        Log.d("PocketDoJa_WiFi", "WiFi RSSI: " + rssi + ", level: " + level);
        String iconName = "icons/wifi_" + level + ".png";
        Bitmap icon = loadCustomIcon(iconName);
        if (icon == null) {
            Log.d("PocketDoJa_WiFi", "Custom icon not found, using drawn: " + iconName);
            icon = drawWifiIcon(level, true);
        }
        wifiIcon.setImageBitmap(icon);
    }

    private void updateBatteryIcon() {
        if (batteryIcon == null) return;
        Intent batteryIntent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batteryIntent == null) {
            // No battery info (e.g., TV device) - show full battery
            Log.d("PocketDoJa_Battery", "No battery intent, using batt_full");
            batteryIcon.setImageBitmap(loadCustomIcon("icons/batt_full.png"));
            return;
        }
        int level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
        int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int percent = (level * 100) / scale;
        boolean charging = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN) == BatteryManager.BATTERY_STATUS_CHARGING;
        boolean present = batteryIntent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true);
        
        Log.d("PocketDoJa_Battery", "Battery: level=" + level + ", scale=" + scale + ", percent=" + percent + ", charging=" + charging + ", present=" + present);
        
        String iconName;
        if (!present) {
            iconName = "icons/batt_full.png";
        } else if (charging) {
            iconName = "icons/batt_charge.png";
        } else if (percent >= 100) {
            iconName = "icons/batt_full.png";
        } else if (percent >= 70) {
            iconName = "icons/batt_75.png";
        } else if (percent >= 45) {
            iconName = "icons/batt_50.png";
        } else if (percent >= 15) {
            iconName = "icons/batt_25.png";
        } else {
            iconName = "icons/batt_5.png";
        }
        
        Log.d("PocketDoJa_Battery", "Battery icon: " + iconName);
        Bitmap icon = loadCustomIcon(iconName);
        if (icon == null) {
            Log.d("PocketDoJa_Battery", "Custom icon not found, using drawn");
            icon = drawBatteryIcon(percent, charging, !present);
        }
        batteryIcon.setImageBitmap(icon);
        
        // Update mascot based on battery
        updateMascotIcon(percent, charging, present);
    }

    private void updateSearchIcon() {
        if (searchButton != null) {
            Bitmap icon = loadCustomIcon("icons/search.png");
            if (icon != null) {
                searchButton.setText("");
                searchButton.setCompoundDrawablesWithIntrinsicBounds(null, null, new BitmapDrawable(getResources(), icon), null);
            }
        }
    }

    /** Grid/list toggle artwork. Dimmed in list mode so the current mode reads at a glance. */
    private void updateViewToggleIcon() {
        if (viewToggleButton == null) {
            return;
        }
        Drawable icon = getResources().getDrawable(R.drawable.ic_grid, getTheme()).mutate();
        icon.setTint(themeTextColor());
        icon.setAlpha(isGridView() ? 255 : 140);
        viewToggleButton.setCompoundDrawablesWithIntrinsicBounds(null, null, icon, null);
    }

    /** Edit toggle artwork. Accent tint while the layout is being edited. */
    private void updateEditButtonAppearance() {
        if (editButton == null) {
            return;
        }
        Drawable icon = getResources().getDrawable(R.drawable.ic_edit, getTheme()).mutate();
        icon.setTint(editMode ? 0xFF3080FF : themeTextColor());
        editButton.setCompoundDrawablesWithIntrinsicBounds(null, null, icon, null);
    }

    private void startTimeUpdater() {
        if (timeTimer != null) {
            timeTimer.cancel();
        }
        timeTimer = new java.util.Timer();
        timeTimer.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override
            public void run() {
                runOnUiThread(() -> {
                    if (timeView != null) {
                        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault());
                        timeView.setText(sdf.format(new java.util.Date()));
                    }
                });
            }
        }, 0, 1000);
    }

    private Bitmap loadCustomIcon(String assetPath) {
        try {
            InputStream is = getAssets().open(assetPath);
            Bitmap bitmap = BitmapFactory.decodeStream(is);
            is.close();
            if (bitmap != null) {
                int targetSize = dp(28);
                if (bitmap.getWidth() != targetSize || bitmap.getHeight() != targetSize) {
                    bitmap = Bitmap.createScaledBitmap(bitmap, targetSize, targetSize, true);
                }
                return bitmap;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // Early Android 3D-style WiFi icon (4 arcs with highlight/shadow for 3D effect)
    private Bitmap drawWifiIcon(int level, boolean enabled) {
        int size = dp(28);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setStrokeCap(Paint.Cap.ROUND);

        int color = enabled ? themeTextColor() : 0xFF888888;
        int highlightColor = enabled ? lightenColor(themeTextColor(), 0.4f) : 0xFFAAAAAA;
        int shadowColor = enabled ? darkenColor(themeTextColor(), 0.4f) : 0xFF666666;

        int centerX = size / 2;
        int centerY = size / 2 + dp(2);

        // Draw 4 arcs for signal levels (back to front for 3D look)
        for (int i = 3; i >= 0; i--) {
            float radius = dp(3) + i * dp(4);
            float sweepAngle = 80;
            float startAngle = 130;

            // Shadow (bottom-right)
            paint.setColor(shadowColor);
            canvas.drawArc(centerX - radius + dp(0.5f), centerY - radius + dp(0.5f),
                    centerX + radius + dp(0.5f), centerY + radius + dp(0.5f),
                    startAngle, sweepAngle, false, paint);

            // Main arc
            if (enabled && i < level) {
                paint.setColor(color);
            } else {
                paint.setColor(0xFF444444);
            }
            canvas.drawArc(centerX - radius, centerY - radius,
                    centerX + radius, centerY + radius,
                    startAngle, sweepAngle, false, paint);

            // Highlight (top-left)
            if (enabled && i < level) {
                paint.setColor(highlightColor);
                canvas.drawArc(centerX - radius - dp(0.5f), centerY - radius - dp(0.5f),
                        centerX + radius - dp(0.5f), centerY + radius - dp(0.5f),
                        startAngle, sweepAngle, false, paint);
            }
        }

        // Center dot
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(enabled ? color : 0xFF888888);
        canvas.drawCircle(centerX, centerY + dp(4), dp(1.5f), paint);

        return bitmap;
    }

    // Early Android 3D-style battery icon (rounded rect with 3D bevel)
    private Bitmap drawBatteryIcon(int percent, boolean charging, boolean noBattery) {
        int size = dp(28);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        int bodyColor = themeTextColor();
        int highlightColor = lightenColor(bodyColor, 0.3f);
        int shadowColor = darkenColor(bodyColor, 0.4f);
        int fillColor;
        if (percent <= 15) fillColor = 0xFFFF4444;
        else if (percent <= 40) fillColor = 0xFFFFAA00;
        else fillColor = 0xFF44CC44;

        int bodyW = dp(16);
        int bodyH = dp(12);
        int tipW = dp(2);
        int tipH = dp(4);
        int x = (size - bodyW - tipW) / 2;
        int y = (size - bodyH) / 2;
        int radius = dp(2);

        // Battery body - shadow (bottom-right)
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(shadowColor);
        canvas.drawRoundRect(x + dp(1), y + dp(1), x + bodyW + dp(1), y + bodyH + dp(1), radius, radius, paint);
        // Battery tip shadow
        canvas.drawRect(x + bodyW + dp(1), y + (bodyH - tipH) / 2 + dp(1), x + bodyW + tipW + dp(1), y + (bodyH + tipH) / 2 + dp(1), paint);

        // Battery body - main
        paint.setColor(bodyColor);
        canvas.drawRoundRect(x, y, x + bodyW, y + bodyH, radius, radius, paint);
        // Battery tip
        canvas.drawRect(x + bodyW, y + (bodyH - tipH) / 2, x + bodyW + tipW, y + (bodyH + tipH) / 2, paint);

        // Battery body - highlight (top-left)
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(highlightColor);
        canvas.drawRoundRect(x + dp(0.5f), y + dp(0.5f), x + bodyW - dp(0.5f), y + bodyH - dp(0.5f), radius - dp(0.5f), radius - dp(0.5f), paint);

        // Fill level
        if (percent > 0 || noBattery) {
            int fillW = noBattery ? bodyW - dp(4) : Math.max(dp(2), (int) ((bodyW - dp(4)) * percent / 100f));
            int fillX = x + dp(2);
            int fillY = y + dp(2);
            int fillH = bodyH - dp(4);
            int fillRadius = dp(1);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(fillColor);
            canvas.drawRoundRect(fillX, fillY, fillX + fillW, fillY + fillH, fillRadius, fillRadius, paint);
            // Fill highlight
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(0.5f));
            paint.setColor(lightenColor(fillColor, 0.3f));
            canvas.drawRoundRect(fillX + dp(0.5f), fillY + dp(0.5f), fillX + fillW - dp(0.5f), fillY + fillH - dp(0.5f), fillRadius - dp(0.5f), fillRadius - dp(0.5f), paint);
        }

        // Charging bolt
        if (charging && !noBattery) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFFFFF00);
            float boltX = x + bodyW / 2f;
            float boltY = y + bodyH / 2f;
            float boltSize = dp(3);
            Path boltPath = new Path();
            boltPath.moveTo(boltX, boltY - boltSize);
            boltPath.lineTo(boltX - boltSize / 2, boltY);
            boltPath.lineTo(boltX, boltY);
            boltPath.lineTo(boltX - boltSize / 2, boltY + boltSize);
            boltPath.lineTo(boltX + boltSize, boltY - boltSize / 2);
            boltPath.lineTo(boltX, boltY - boltSize / 2);
            boltPath.close();
            canvas.drawPath(boltPath, paint);
        }

        return bitmap;
    }

    private int lightenColor(int color, float factor) {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        r = Math.min(255, (int) (r + (255 - r) * factor));
        g = Math.min(255, (int) (g + (255 - g) * factor));
        b = Math.min(255, (int) (b + (255 - b) * factor));
        return Color.rgb(r, g, b);
    }

    private int darkenColor(int color, float factor) {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        r = (int) (r * (1 - factor));
        g = (int) (g * (1 - factor));
        b = (int) (b * (1 - factor));
        return Color.rgb(r, g, b);
    }

    private void updateFixedTopBarTheme() {
        if (fixedTopBar == null) return;
        // The fixedTopBar is a FrameLayout containing the bar and divider
        // Find the LinearLayout (bar) inside the FrameLayout
        if (fixedTopBar instanceof FrameLayout) {
            FrameLayout container = (FrameLayout) fixedTopBar;
            if (container.getChildCount() > 0) {
                View bar = container.getChildAt(0);
                if (bar instanceof LinearLayout) {
                    applyTopBarGradient(bar);
                    // Update text colors
                    updateTopBarTextColors((LinearLayout) bar);
                }
            }
        }
        // Update search icon for new theme
        updateSearchIcon();
        updateViewToggleIcon();
        updateEditButtonAppearance();
        // Update time text color
        if (timeView != null) {
            timeView.setTextColor(themeTextColor());
        }
    }

    private void updateTopBarTextColors(LinearLayout bar) {
        int textColor = themeTextColor();
        for (int i = 0; i < bar.getChildCount(); i++) {
            View child = bar.getChildAt(i);
            if (child instanceof TextView) {
                ((TextView) child).setTextColor(textColor);
            } else if (child instanceof EditText) {
                ((EditText) child).setTextColor(textColor);
                ((EditText) child).setHintTextColor(0x80FFFFFF);
            }
        }
    }

    private int keyboardHeightPx() {
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        return Math.max(dp(140), Math.round(screenHeight * 0.46f));
    }

    private DpadKeyboardView buildSearchKeyboard() {
        DpadKeyboardView keyboard = new DpadKeyboardView(this);
        keyboard.setListener(new DpadKeyboardView.Listener() {
            @Override
            public void onText(String text) {
                if (searchBox == null || searchBox.getVisibility() != View.VISIBLE) {
                    return;
                }
                searchBox.getText().append(text);
            }

            @Override
            public void onDelete() {
                if (searchBox == null) {
                    return;
                }
                Editable editable = searchBox.getText();
                if (editable.length() > 0) {
                    editable.delete(editable.length() - 1, editable.length());
                }
            }

            @Override
            public void onSubmit() {
                // Keep the current query and let the D-pad move around the
                // filtered grid; Back still closes/clears the search.
                hideSearchKeyboard();
                if (searchBox != null) {
                    searchBox.clearFocus();
                }
                if (gridContainer != null) {
                    gridContainer.requestFocus();
                }
            }

            @Override
            public void onCancel() {
                toggleSearch();
            }
        });
        return keyboard;
    }

    private boolean isSearchKeyboardVisible() {
        return searchKeyboard != null && searchKeyboard.getVisibility() == View.VISIBLE;
    }

    private boolean showSearchKeyboard() {
        if (searchKeyboard == null || searchBox == null
                || searchBox.getVisibility() != View.VISIBLE) {
            return false;
        }
        searchKeyboard.setVisibility(View.VISIBLE);
        searchKeyboard.bringToFront();
        if (searchBox.hasFocus()) {
            searchBox.setCursorVisible(true);
        }
        searchKeyboard.invalidate();
        return true;
    }

    private void hideSearchKeyboard() {
        if (searchKeyboard != null) {
            searchKeyboard.setVisibility(View.GONE);
        }
    }

    private boolean useSystemKeyboard() {
        return DisplayActivity.isSystemKeyboardEnabled(this);
    }

    private void showSystemKeyboard(android.view.View target) {
        if (target == null) {
            return;
        }
        target.requestFocus();
        target.post(() -> {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(target,
                        android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    private void toggleSearch() {
        if (searchBox == null) {
            return;
        }
        if (editMode) {
            // Reordering a filtered subset would make the saved layout
            // surprising, so search waits until edits are saved or discarded.
            Toast.makeText(this, getString(R.string.edit_hint), Toast.LENGTH_SHORT).show();
            return;
        }
        if (searchBox.getVisibility() == View.VISIBLE) {
            searchBox.setText("");
            searchBox.setVisibility(View.GONE);
            hideSearchKeyboard();
            if (titleView != null) {
                titleView.setVisibility(View.VISIBLE);
            }
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(searchBox.getWindowToken(), 0);
            }
            if (gridContainer != null) {
                gridContainer.post(() -> {
                    if (searchBox.getVisibility() != View.VISIBLE) {
                        gridContainer.requestFocus();
                    }
                });
            }
        } else {
            searchBox.setVisibility(View.VISIBLE);
            if (titleView != null) {
                titleView.setVisibility(View.GONE);
            }
            if (useSystemKeyboard()) {
                hideSearchKeyboard();
                searchBox.setShowSoftInputOnFocus(true);
                showSystemKeyboard(searchBox);
            } else {
                searchBox.setShowSoftInputOnFocus(false);
                showSearchKeyboard();
                searchBox.requestFocus();
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.hideSoftInputFromWindow(searchBox.getWindowToken(), 0);
                }
            }
        }
    }

    private View buildDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(0xFF3A3D45);
        return divider;
    }

    private void showOptionsMenu(View anchor) {
        if (menuPopup != null && menuPopup.isShowing()) {
            menuPopup.dismiss();
            menuPopup = null;
            return;
        }
        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setMinimumWidth(dp(180));
        menu.setPadding(dp(4), dp(4), dp(4), dp(4));
        GradientDrawable panel = new GradientDrawable();
        panel.setColor(0xF020242E);
        panel.setCornerRadius(dp(8));
        menu.setBackground(panel);

        for (int i = 0; i < menuItems.length; i++) {
            // Save Diagnostics - Dev use only!! Enable/Disable
            if (!SAVE_DIAGNOSTICS_ENABLED && i == MENU_INDEX_SAVE_DIAGNOSTICS) {
                continue;
            }
            final int menuIndex = i;
            String label = menuItems[i];
            TextView item = new TextView(this);
            item.setText(label);
            item.setTextColor(0xFFE8E8E8);
            item.setTextSize(16f);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(18), dp(14), dp(18), dp(14));
            item.setClickable(true);
            item.setFocusable(true);
            // The focused fill below is the only indicator; don't let the
            // platform draw its outline around the same item.
            FocusHighlight.disable(item);
            item.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    GradientDrawable focused = new GradientDrawable();
                    focused.setColor(0x663080FF);
                    focused.setCornerRadius(dp(6));
                    v.setBackground(focused);
                } else {
                    v.setBackground(null);
                }
            });
            item.setForeground(new RippleDrawable(
                    ColorStateList.valueOf(0x2FFFFFFF), null, null));
            item.setOnClickListener(v -> {
                if (menuPopup != null) {
                    menuPopup.dismiss();
                    menuPopup = null;
                }
                onMenuAction(menuIndex);
            });
            menu.addView(item, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        menu.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        PopupWindow popup = new PopupWindow(menu,
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setFocusable(true);
        popup.setElevation(dp(8));
        popup.showAsDropDown(anchor, -(menu.getMeasuredWidth() - anchor.getWidth()), 0);
        menu.post(() -> {
            if (menu.getChildCount() > 0) {
                menu.getChildAt(0).requestFocus();
            }
        });
        menuPopup = popup;
    }

    private void onMenuAction(int index) {
        switch (index) {
            case 0:
                pickImportFolder();
                break;
            case 1:
                startActivity(new Intent(this, KeyMappingActivity.class));
                break;
            case 2:
                startActivity(new Intent(this, DisplayActivity.class));
                break;
            case 3:
                startActivity(new Intent(this, ThemeActivity.class));
                break;
            case 4:
                showAboutDialog();
                break;
            case 5:
                releaseBgm();
                try {
                    opendoja.audio.mld.MLDPCMPlayer.releaseAll();
                    opendoja.audio.mld.MLDPCMPlayer.releaseGenerationsUpTo(Long.MAX_VALUE);
                } catch (Throwable ignored) {
                }
                try {
                    com.nttdocomo.ui.AudioPresenter.releaseAllSessions();
                    com.nttdocomo.ui.AudioPresenter.releaseAll();
                } catch (Throwable ignored) {
                }
                opendoja.host.OpenDoJaLog.info(HomeActivity.class, "Exit pressed: class=" + getClass().getName()
                        + " taskId=" + getTaskId() + " isTaskRoot=" + isTaskRoot());
                android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
                if (am != null) {
                    for (android.app.ActivityManager.AppTask task : am.getAppTasks()) {
                        try {
                            task.finishAndRemoveTask();
                        } catch (Exception ignored) {
                        }
                    }
                }
                finishAndRemoveTask();
                finishAffinity();
                System.exit(0);
                break;
            case 6:
                // Save Diagnostics - Dev use only!! Enable/Disable
                if (SAVE_DIAGNOSTICS_ENABLED) {
                    captureDiagnostics();
                }
                break;
            default:
                break;
        }
    }

    // ---- In-app diagnostics (no adb/terminal needed) ----

    private static final class TaskCpu {
        final String name;
        final long ticks;

        TaskCpu(String name, long ticks) {
            this.name = name;
            this.ticks = ticks;
        }
    }

    private void captureDiagnostics() {
        android.widget.Toast.makeText(this, "Sampling CPU for 2s...", android.widget.Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String report;
            java.io.File out = null;
            java.io.File logOut = null;
            try {
                report = buildDiagnosticsReport();
                out = writeDiagnostics(report);
                logOut = copyRuntimeLog();
            } catch (Throwable throwable) {
                report = "diagnostics failed: " + throwable;
            }
            final String finalReport = report;
            final java.io.File finalOut = out;
            final java.io.File finalLogOut = logOut;
            runOnUiThread(() -> {
                if (finalOut != null) {
                    String message = "Saved: " + finalOut.getAbsolutePath();
                    if (finalLogOut != null) {
                        message += "\nlog: " + finalLogOut.getAbsolutePath();
                    }
                    android.widget.Toast.makeText(this,
                            message, android.widget.Toast.LENGTH_LONG).show();
                }
                try {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_SUBJECT, "PocketDoJa diagnostics");
                    send.putExtra(Intent.EXTRA_TEXT, finalReport);
                    startActivity(Intent.createChooser(send, "Share diagnostics"));
                } catch (Throwable ignored) {
                }
            });
        }, "opendoja-diagnostics").start();
    }

    private String buildDiagnosticsReport() throws Exception {
        StringBuilder sb = new StringBuilder(16384);
        sb.append("PocketDoJa diagnostics\n");
        sb.append("build: ").append(DIAG_BUILD).append('\n');
        sb.append("time: ").append(new java.util.Date()).append('\n');
        try {
            android.content.pm.PackageInfo info = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            sb.append("installed: ").append(new java.util.Date(info.lastUpdateTime))
                    .append(" versionCode=").append(info.versionCode)
                    .append(" versionName=").append(info.versionName).append('\n');
        } catch (Throwable ignored) {
        }
        Runtime rt = Runtime.getRuntime();
        long mb = 1024L * 1024L;
        sb.append("memory: used=").append((rt.totalMemory() - rt.freeMemory()) / mb)
                .append("MB total=").append(rt.totalMemory() / mb)
                .append("MB max=").append(rt.maxMemory() / mb).append("MB\n");
        sb.append("java threads: ").append(Thread.getAllStackTraces().size()).append('\n');

        java.util.Map<String, TaskCpu> before = readProcTasks();
        Thread.sleep(2000L);
        java.util.Map<String, TaskCpu> after = readProcTasks();
        long totalDelta = 0L;
        java.util.List<long[]> deltas = new java.util.ArrayList<>();
        java.util.List<String> names = new java.util.ArrayList<>();
        java.util.List<String> tids = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, TaskCpu> entry : after.entrySet()) {
            TaskCpu now = entry.getValue();
            TaskCpu then = before.get(entry.getKey());
            long delta = now.ticks - (then == null ? 0L : then.ticks);
            if (delta < 0L) {
                delta = 0L;
            }
            totalDelta += delta;
            deltas.add(new long[]{delta});
            names.add(now.name);
            tids.add(entry.getKey());
        }
        Integer[] order = new Integer[deltas.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Long.compare(deltas.get(b)[0], deltas.get(a)[0]));
        sb.append(String.format(java.util.Locale.US, "total process CPU over 2s: %.1f%%%n", totalDelta / 2.0));
        sb.append("per-thread CPU (top 25):\n");
        for (int i = 0; i < Math.min(25, order.length); i++) {
            int idx = order[i];
            sb.append(String.format(java.util.Locale.US, "  %6.1f%%  %s (tid=%s)%n",
                    deltas.get(idx)[0] / 2.0, names.get(idx), tids.get(idx)));
        }

        // Stack traces of the busiest threads, so leftover game loops can be identified.
        // Match by name (the /proc tid is the native id and does not reliably equal
        // Thread.getId() on Android).
        java.util.Map<Thread, StackTraceElement[]> stacks = Thread.getAllStackTraces();
        java.util.Map<String, Thread> byName = new java.util.HashMap<>();
        for (Thread thread : stacks.keySet()) {
            byName.putIfAbsent(thread.getName(), thread);
        }
        sb.append("\nhot thread stacks:\n");
        for (int i = 0; i < Math.min(8, order.length); i++) {
            int idx = order[i];
            if (deltas.get(idx)[0] < 20L) {
                continue;
            }
            String comm = names.get(idx);
            Thread thread = byName.get(comm);
            if (thread == null) {
                for (Thread candidate : stacks.keySet()) {
                    String name = candidate.getName();
                    if (name.startsWith(comm) || comm.startsWith(name)) {
                        thread = candidate;
                        break;
                    }
                }
            }
            sb.append("--- ").append(comm).append(" tid=").append(tids.get(idx)).append('\n');
            StackTraceElement[] trace = thread != null ? stacks.get(thread) : null;
            if (trace == null || trace.length == 0) {
                sb.append("  (no java stack available)\n");
                continue;
            }
            for (int f = 0; f < Math.min(18, trace.length); f++) {
                sb.append("  at ").append(trace[f]).append('\n');
            }
        }

        ThemeRenderer renderer = themeRenderer;
        sb.append("\ntheme frames drawn: ").append(renderer != null ? renderer.drawCount() : -1L)
                .append("  surfaceCreated: ").append(renderer != null ? renderer.surfaceCreateCount() : -1L)
                .append("  tickActive: ").append(glFrameLoopActive)
                .append('\n');
        sb.append("theme events:\n").append(ThemeRenderer.recentEvents());

        try {
            java.io.File sdRoot = new java.io.File(getFilesDir(), ".opendoja/storage/ext0/SD_BIND");
            java.io.File[] sdFolders = sdRoot.listFiles();
            sb.append("\nSD_BIND folders:\n");
            if (sdFolders == null || sdFolders.length == 0) {
                sb.append("  (none)\n");
            } else {
                for (java.io.File folder : sdFolders) {
                    sb.append("  ").append(folder.getName())
                            .append(" (").append(countFiles(folder)).append(" files)\n");
                    appendFileNames(sb, folder, 20);
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            java.io.File stubTrace = new java.io.File(getFilesDir(), ".opendoja/pocketdoja-http-stub.txt");
            sb.append("\nhttp-stub trace:\n");
            if (stubTrace.exists()) {
                sb.append(tail(stubTrace, 60));
            } else {
                sb.append("  (none)\n");
            }
        } catch (Throwable ignored) {
        }
        try {
            java.io.File log = new java.io.File(getExternalFilesDir(null), "pocketdoja-log.txt");
            if (!log.exists()) {
                log = new java.io.File(getFilesDir(), "pocketdoja-log.txt");
            }
            if (log.exists()) {
                appendLogMatches(sb, log);
                sb.append("\nlog (").append(log.getName()).append("):\n").append(tail(log, 4000));
            } else {
                sb.append("\n(no runtime log file; enable Debugging to capture it)\n");
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    /** Surfaces manifest/HTTP diagnostics even when they are far outside the log tail. */
    private static void appendLogMatches(StringBuilder sb, java.io.File log) {
        sb.append("\nhttp/manifest log lines:\n");
        int shown = 0;
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(log))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String lower = line.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("manifest") || lower.contains("api.php") || lower.contains("zc=10")
                        || lower.contains("http request") || lower.contains("http response")
                        || lower.contains("appname") || lower.contains("package.lst")
                        || lower.contains("no runtime") || lower.contains("stub")
                        || lower.contains("text sample") || lower.contains("charset check")
                        || lower.contains("sjis")) {
                    if (shown < 80) {
                        sb.append("  ").append(line).append('\n');
                        shown++;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (shown == 0) {
            sb.append("  (none)\n");
        }
    }

    private static java.util.Map<String, TaskCpu> readProcTasks() {
        java.util.Map<String, TaskCpu> map = new java.util.HashMap<>();
        java.io.File[] tasks = new java.io.File("/proc/self/task").listFiles();
        if (tasks == null) {
            return map;
        }
        for (java.io.File task : tasks) {
            String tid = task.getName();
            try {
                String comm = readFirstLine(new java.io.File(task, "comm"));
                String stat = readFirstLine(new java.io.File(task, "stat"));
                int close = stat.lastIndexOf(')');
                if (close < 0) {
                    continue;
                }
                String[] tokens = stat.substring(close + 2).split(" ");
                long ticks = Long.parseLong(tokens[11]) + Long.parseLong(tokens[12]);
                map.put(tid, new TaskCpu(comm, ticks));
            } catch (Throwable ignored) {
            }
        }
        return map;
    }

    private static String readFirstLine(java.io.File file) throws java.io.IOException {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file))) {
            String line = reader.readLine();
            return line == null ? "" : line;
        }
    }

    private java.io.File writeDiagnostics(String report) {
        java.io.File[] candidates = {
                new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS), "pocketdoja-diagnostics.txt"),
                new java.io.File(getExternalFilesDir(null), "pocketdoja-diagnostics.txt"),
                new java.io.File(getFilesDir(), "pocketdoja-diagnostics.txt")
        };
        for (java.io.File candidate : candidates) {
            try {
                java.io.File parent = candidate.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                try (java.io.FileWriter writer = new java.io.FileWriter(candidate, false)) {
                    writer.write(report);
                }
                return candidate;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** Saves the complete current runtime log next to the diagnostics report. */
    private java.io.File copyRuntimeLog() {
        java.io.File source = new java.io.File(getExternalFilesDir(null), "pocketdoja-log.txt");
        if (!source.exists()) {
            source = new java.io.File(getFilesDir(), "pocketdoja-log.txt");
        }
        if (!source.exists()) {
            return null;
        }
        java.io.File[] candidates = {
                new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS), "pocketdoja-log.txt"),
                new java.io.File(getExternalFilesDir(null), "pocketdoja-log.txt"),
                new java.io.File(getFilesDir(), "pocketdoja-log.txt")
        };
        for (java.io.File candidate : candidates) {
            try {
                if (candidate.getCanonicalFile().equals(source.getCanonicalFile())) {
                    return candidate;
                }
                java.io.File parent = candidate.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                try (java.io.FileInputStream in = new java.io.FileInputStream(source);
                     java.io.FileOutputStream output = new java.io.FileOutputStream(candidate, false)) {
                    byte[] buffer = new byte[16384];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        output.write(buffer, 0, read);
                    }
                }
                return candidate;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** Writes a small diagnostic report to Downloads / app storage under the given name. */
    private java.io.File writeExternalReport(String name, String content) {
        java.io.File[] candidates = {
                new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS), name),
                new java.io.File(getExternalFilesDir(null), name),
                new java.io.File(getFilesDir(), name)
        };
        for (java.io.File candidate : candidates) {
            try {
                java.io.File parent = candidate.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                try (java.io.FileWriter writer = new java.io.FileWriter(candidate, false)) {
                    writer.write(content);
                }
                return candidate;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String tail(java.io.File file, int maxLines) {
        java.util.ArrayDeque<String> lines = new java.util.ArrayDeque<>();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.addLast(line);
                if (lines.size() > maxLines) {
                    lines.removeFirst();
                }
            }
        } catch (Throwable throwable) {
            return "could not read log: " + throwable + "\n";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    static void applyDisplayOrientation(Activity activity) {
        applyDisplayOrientation(activity, null);
    }

    static void applyDisplayOrientation(Activity activity, String gameId) {
        SharedPreferences prefs = activity.getSharedPreferences(DisplayActivity.PREFS_NAME, MODE_PRIVATE);
        String orientation;
        if (gameId != null && !gameId.isEmpty()) {
            String override = prefs.getString(gameId + "_" + DisplayActivity.KEY_ORIENTATION, DisplayActivity.INHERIT);
            opendoja.host.OpenDoJaLog.info(HomeActivity.class, "applyDisplayOrientation: gameId=" + gameId + " override=" + override);
            if (!override.equals(DisplayActivity.INHERIT)) {
                orientation = override;
            } else {
                orientation = prefs.getString(DisplayActivity.KEY_ORIENTATION, DisplayActivity.ORIENTATION_PORTRAIT);
            }
        } else {
            orientation = prefs.getString(DisplayActivity.KEY_ORIENTATION, DisplayActivity.ORIENTATION_PORTRAIT);
        }
        opendoja.host.OpenDoJaLog.info(HomeActivity.class, "applyDisplayOrientation: effective=" + orientation);
        int requested = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        if (DisplayActivity.ORIENTATION_LANDSCAPE.equals(orientation)) {
            requested = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        } else if (DisplayActivity.ORIENTATION_PORTRAIT_REV.equals(orientation)) {
            requested = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
        } else if (DisplayActivity.ORIENTATION_LANDSCAPE_REV.equals(orientation)) {
            requested = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
        }
opendoja.host.OpenDoJaLog.info(HomeActivity.class, "applyDisplayOrientation: requested=" + requested);
        activity.setRequestedOrientation(requested);
    }
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyDisplayOrientation(this);
        computeGridMetrics();
        applyTopBarScale();
        iconCache.clear();
        if (gridContainer != null) {
            refreshGames();
        }
    }

    private void showAboutDialog() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(16));

        android.graphics.Typeface regular = pixelifyTypeface(this, false);

        TextView title = new TextView(this);
        String versionName = "1.2.0"; // this one doesn't matter, it uses PackageManager versionName.
        try {
            versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        title.setText("About Pocket DoJa (" + versionName + ")");
        title.setTypeface(regular);
        title.setTextSize(18f);
        title.setTextColor(0xFFE0E0E0);
        content.addView(title);
    // About section for Pocket Doja
        TextView body = new TextView(this);
        body.setText("Pocket DoJa is an Android-based emulator project based on OpenDoJa, dedicated to exploring and preserving the experience of classic NTT DoCoMo i-mode Java games.\n\n" +
                "The project aims to make these nostalgic mobile games more accessible on modern Android devices, with features including game library management, customizable animated themes, and background music support.\n\n" +
                "Compatibility may vary depending on the game, DoJa version, and device. Some games or features may not work as expected.\n\n" +
                "Disclaimer:\n" +
                "Pocket DoJa does not provide or distribute game files. Users are responsible for obtaining their own files and ensuring their use complies with applicable laws and copyright. Pocket DoJa is not affiliated with or endorsed by NTT DOCOMO, game developers, or publishers. All trademarks belong to their respective owners.\n\n" +
                "Credits:\n" +
                "OpenDoJa - For the original project that made Pocket DoJa possible.\n" +
                "Wandering Hunter (\u6d41\u6d6a\u7684\u730e\u4eba) - For creating the original Android port.\n" +
                "Shibesan (aka DominicTei) - The main developer of Pocket DoJa.\n\n" +
                "Support the Project\n\n" +
                "If you enjoy Pocket DoJa and would like to support its continued development, you can make a voluntary donation using one of the options below.\n\n");
        body.setTypeface(regular);
        body.setTextSize(15f);
        body.setTextColor(0xFFE0E0E0);
        content.addView(body);

        // PayPal row
        LinearLayout paypalRow = new LinearLayout(this);
        paypalRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView paypalLabel = new TextView(this);
        paypalLabel.setText("PayPal: ");
        paypalLabel.setTypeface(regular);
        paypalLabel.setTextColor(0xFFE0E0E0);
        paypalLabel.setTextSize(15f);
        TextView paypalLink = new TextView(this);
        paypalLink.setText("https://www.paypal.com/ncp/payment/CCLQWPK38LFB4");
        paypalLink.setTypeface(regular);
        paypalLink.setTextColor(0xFF4FC3F7);
        paypalLink.setTextSize(15f);
        paypalLink.setOnClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("PayPal", "https://www.paypal.com/ncp/payment/CCLQWPK38LFB4"));
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        });
        paypalRow.addView(paypalLabel);
        paypalRow.addView(paypalLink);
        content.addView(paypalRow);

        // Crypto info
        TextView cryptoInfo = new TextView(this);
        cryptoInfo.setText("\nCryptocurrency:\nNetwork: ETH, BSC(BEP-20), and POLYGON based networks are supported!\nCurrency: USDT, BNB, ETH, and POLYGON accepted\n");
        cryptoInfo.setTypeface(regular);
        cryptoInfo.setTextSize(15f);
        cryptoInfo.setTextColor(0xFFE0E0E0);
        content.addView(cryptoInfo);

        // Wallet address row
        LinearLayout walletRow = new LinearLayout(this);
        walletRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView walletLabel = new TextView(this);
        walletLabel.setText("Wallet address: ");
        walletLabel.setTypeface(regular);
        walletLabel.setTextColor(0xFFE0E0E0);
        walletLabel.setTextSize(15f);
        TextView walletAddr = new TextView(this);
        walletAddr.setText("0xB3Edf36f3654779ACdE275744a8cC31C37BE9d6c");
        walletAddr.setTypeface(regular);
        walletAddr.setTextColor(0xFF4FC3F7);
        walletAddr.setTextSize(15f);
        walletAddr.setOnClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Wallet", "0xB3Edf36f3654779ACdE275744a8cC31C37BE9d6c"));
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        });
        walletRow.addView(walletLabel);
        walletRow.addView(walletAddr);
        content.addView(walletRow);

        // QR image right under wallet address
        try {
            android.graphics.Bitmap qr = android.graphics.BitmapFactory.decodeStream(getAssets().open("donation/QR.PNG"));
            if (qr != null) {
                ImageView qrView = new ImageView(this);
                qrView.setImageBitmap(qr);
                qrView.setAdjustViewBounds(true);
                qrView.setMaxWidth(dp(200));
                qrView.setMaxHeight(dp(200));
                LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                qrParams.topMargin = dp(8);
                content.addView(qrView, qrParams);
            }
        } catch (Exception ignored) {
        }

        TextView footer = new TextView(this);
        footer.setText("\nDonations are entirely optional and help support ongoing development and preservation efforts.\n\nThank you for your interest in Pocket DoJa!\n\n" +
                "\u00a9 2026 Pocket DoJa. Original themes, artwork, and branding are owned by the Pocket DoJa developer, except where otherwise stated.\n\n" +
                "Pocket DoJa is based on OpenDoJa and distributed under GNU GPLv3. Third-party components remain subject to their respective licenses.\n\n" +
                "Unofficial forks are not necessarily affiliated with or endorsed by the original Pocket DoJa project.");
        footer.setTypeface(regular);
        footer.setTextSize(15f);
        footer.setTextColor(0xFFE0E0E0);
        content.addView(footer);

        scroll.addView(content);
        new AlertDialog.Builder(this)
                .setTitle("About")
                .setView(scroll)
                .setPositiveButton("OK", null)
                .show();
    }
    /** Icon size derives from physical PPI; the grid is a fixed 5 columns per row. */
    private boolean isGridView() {
        SharedPreferences prefs = getSharedPreferences("home", MODE_PRIVATE);
        return prefs.getString("viewMode", "grid").equals("grid");
    }

    private void setGridView(boolean grid) {
        SharedPreferences prefs = getSharedPreferences("home", MODE_PRIVATE);
        prefs.edit().putString("viewMode", grid ? "grid" : "list").apply();
    }
    private static android.media.MediaPlayer bgmPlayer;
    private static MldBgmPlayer mldBgm;
    private static int bgmPosition = 0;
    private static boolean bgmPaused = false;
    private static String bgmCurrentPath = "";
    private static boolean bgmEnabled = true;
    public static volatile boolean gameRunning = false;

    private GLSurfaceView glView;
    private ThemeRenderer themeRenderer;
    // The themed home background is decorative and animated; drive it at ~30 fps on demand
    // instead of RENDERMODE_CONTINUOUSLY (which repaints at the display refresh rate, e.g.
    // 60/120 Hz, forever and keeps the device hot even while idle on the game list).
    private static final long GL_FRAME_INTERVAL_MS = 50L; // ~20 fps ambient background
    private android.os.Handler glFrameHandler;
    private Runnable glFrameTick;
    private volatile boolean glFrameLoopActive;
    private String appliedGlTheme;

    private void playBgm() {
        if (gameRunning || bgmSuppressed) {
            opendoja.host.OpenDoJaLog.info(HomeActivity.class, "playBgm: gameRunning, skip");
            return;
        }
        SharedPreferences prefs = getSharedPreferences(ThemeActivity.PREFS_NAME, MODE_PRIVATE);
        if (!prefs.getBoolean(ThemeActivity.KEY_BGM_ENABLED, true)) {
            bgmEnabled = false;
            stopBgmStatic();
            return;
        }
        bgmEnabled = true;
        String path = prefs.getString(ThemeActivity.KEY_BGM_PATH, "");
        if (MldBgmPlayer.isMldPath(path)) {
            playMldBgm(path);
            return;
        }
        if (mldBgm != null) {
            mldBgm.release();
            mldBgm = null;
        }
        if (bgmPlayer != null && !path.equals(bgmCurrentPath)) {
            releaseBgm();
        }
        if (bgmPlayer != null) {
            try {
                if (bgmPaused) {
                    bgmPlayer.seekTo(bgmPosition);
                    bgmPaused = false;
                }
                if (!bgmPlayer.isPlaying()) {
                    bgmPlayer.start();
                }
                opendoja.host.OpenDoJaLog.info(HomeActivity.class, "playBgm: resumed from " + bgmPosition);
            } catch (Exception e) {
                releaseBgm();
                createBgmPlayer(path);
                bgmCurrentPath = path;
            }
            return;
        }
        createBgmPlayer(path);
        bgmCurrentPath = path;
        opendoja.host.OpenDoJaLog.info(HomeActivity.class, "playBgm: created new player");
    }

    private void playMldBgm(String path) {
        if (bgmPlayer != null) {
            releaseBgm();
        }
        if (mldBgm != null && !path.equals(bgmCurrentPath)) {
            mldBgm.release();
            mldBgm = null;
        }
        if (mldBgm != null) {
            bgmPaused = false;
            mldBgm.resume();
            opendoja.host.OpenDoJaLog.info(HomeActivity.class, "playBgm: resumed MLD at " + bgmPosition);
            return;
        }
        try {
            java.io.File f = new java.io.File(path);
            if (!f.exists() || !f.canRead()) {
                createBgmPlayer("");
                bgmCurrentPath = "";
                return;
            }
            mldBgm = new MldBgmPlayer(f);
            mldBgm.start(bgmPosition);
            bgmPaused = false;
            bgmCurrentPath = path;
            opendoja.host.OpenDoJaLog.info(HomeActivity.class, "playBgm: created MLD player");
        } catch (Exception e) {
            opendoja.host.OpenDoJaLog.error(HomeActivity.class, "playBgm: MLD init failed", e);
            try { if (mldBgm != null) mldBgm.release(); } catch (Exception ignored) {}
            mldBgm = null;
            createBgmPlayer("");
            bgmCurrentPath = "";
        }
    }

    private void createBgmPlayer(String path) {
        try {
            bgmPlayer = new android.media.MediaPlayer();
            boolean useDefault = (path == null || path.isEmpty());
            if (!useDefault) {
                java.io.File f = new java.io.File(path);
                if (!f.exists() || !f.canRead()) useDefault = true;
            }
            if (useDefault) {
                android.content.res.AssetFileDescriptor afd = getResources().openRawResourceFd(R.raw.default_bgm);
                bgmPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                afd.close();
            } else {
                bgmPlayer.setDataSource(path);
            }
            bgmPlayer.setLooping(true);
            bgmPlayer.prepare();
            if (bgmPosition > 0) {
                bgmPlayer.seekTo(bgmPosition);
            }
            bgmPlayer.start();
            bgmPaused = false;
        } catch (Exception e) {
            try { if (bgmPlayer != null) bgmPlayer.release(); } catch (Exception ignored) {}
            bgmPlayer = null;
            if (path != null && !path.isEmpty()) {
                createBgmPlayer("");
                bgmCurrentPath = "";
            }
        }
    }

    private void releaseBgm() {
        stopBgmStatic();
    }

    public static void stopBgmStatic() {
        if (mldBgm != null) {
            mldBgm.release();
            mldBgm = null;
        }
        if (bgmPlayer != null) {
            try {
                bgmPlayer.stop();
            } catch (Exception ignored) {
            }
            try {
                bgmPlayer.release();
            } catch (Exception ignored) {
            }
            bgmPlayer = null;
        }
        bgmPosition = 0;
        bgmPaused = false;
        bgmCurrentPath = "";
    }

    private void stopBgm() {
        if (mldBgm != null) {
            if (mldBgm.isPlaying()) {
                bgmPosition = mldBgm.getCurrentTimeMillis();
                mldBgm.pause();
                bgmPaused = true;
                opendoja.host.OpenDoJaLog.info(HomeActivity.class, "stopBgm: paused MLD at " + bgmPosition);
            }
            return;
        }
        if (bgmPlayer != null) {
            try {
                if (bgmPlayer.isPlaying()) {
                    bgmPosition = bgmPlayer.getCurrentPosition();
                    bgmPlayer.pause();
                    bgmPaused = true;
                    opendoja.host.OpenDoJaLog.info(HomeActivity.class, "stopBgm: paused at " + bgmPosition);
                }
            } catch (Exception ignored) {
            }
        }
    }

    public static void pauseBgmStatic() {
        if (mldBgm != null) {
            if (mldBgm.isPlaying()) {
                bgmPosition = mldBgm.getCurrentTimeMillis();
                mldBgm.pause();
                bgmPaused = true;
            }
            return;
        }
        if (bgmPlayer != null) {
            try {
                if (bgmPlayer.isPlaying()) {
                    bgmPosition = bgmPlayer.getCurrentPosition();
                    bgmPlayer.pause();
                    bgmPaused = true;
                }
            } catch (Exception ignored) {
            }
        }
    }

    public static void resumeBgmStatic() {
        if (gameRunning) return;
        if (!bgmEnabled) {
            stopBgmStatic();
            return;
        }
        if (mldBgm != null) {
            mldBgm.resume();
            bgmPaused = false;
            return;
        }
        if (bgmPlayer != null) {
            try {
                if (bgmPaused) {
                    bgmPlayer.seekTo(bgmPosition);
                    bgmPaused = false;
                }
                if (!bgmPlayer.isPlaying()) {
                    bgmPlayer.start();
                }
            } catch (Exception ignored) {
            }
        }
    }
    public static void playBgmStatic() {
        if (instance != null) {
            instance.playBgm();
        }
    }

    private int themeTextColor() {
        SharedPreferences prefs = getSharedPreferences(ThemeActivity.PREFS_NAME, MODE_PRIVATE);
        String theme = prefs.getString(ThemeActivity.KEY_THEME, "flow");
        if (theme.equals("flow-midnight")) return 0xFFFFFFFF;
        return 0xFF1A1A1A;
    }

    private int themeSurfaceColor() {
        SharedPreferences prefs = getSharedPreferences(ThemeActivity.PREFS_NAME, MODE_PRIVATE);
        String theme = prefs.getString(ThemeActivity.KEY_THEME, "flow");
        switch (theme) {
            case "flow-azure": return 0x99B3E5FC;
            case "flow-green": return 0x99DCEDC8;
            case "flow-midnight": return 0x99B0BEC5;
            case "flow-dream": return 0x99F8BBD0;
            default: return 0x99FFFFFF;
        }
    }
    private void computeGridMetrics() {
        // Read metrics from the live window display instead of Resources:
        // the LocaleHelper configuration context can freeze Resources to the
        // metrics captured at attach time, which reports stale width values
        // after an orientation change and breaks grid reflow.
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(dm);
        float xdpi = dm.xdpi > 1f ? dm.xdpi : dm.densityDpi;
        columns = 5;
        float usable = dm.widthPixels - 24f * dm.density;
        int cellPx = (int) (usable / columns);
        int icon = Math.min(Math.round(15f * xdpi / 25.4f), (int) (cellPx * 0.78f));
        iconSizePx = Math.max(icon, dp(48));
    }

    private void refreshGames() {
        refreshGames(false);
    }

    /**
     * @param forceScan true when edit mode has just been entered and the
     *                  filtered list must be replaced by the full library.
     *                  Otherwise, while editing, the in-memory order wins over
     *                  the persisted order so an activity pause/resume (for
     *                  example opening Theme) cannot silently discard the
     *                  rearrangement the user has not saved yet.
     */
    private void refreshGames(boolean forceScan) {
        if (!forceScan && editMode && !currentGames.isEmpty()) {
            populateGrid(new ArrayList<>(currentGames));
            return;
        }
        worker.execute(() -> {
            List<GameEntry> games = scanGames();
            if (isFinishing() || isDestroyed()) {
                return;
            }
            runOnUiThread(() -> populateGrid(games));
        });
    }

    private List<GameEntry> scanGames() {
        File gamesDir = JamAssets.gamesDir(this);
        File[] jams = gamesDir.listFiles((dir, name) ->
                name.toLowerCase(Locale.ROOT).endsWith(".jam"));
        if (jams == null) {
            jams = new File[0];
        }
        List<GameEntry> games = new ArrayList<>(jams.length);
        for (File jam : jams) {
            String name = stripExtension(jam.getName());
            try {
                Properties properties = JamMetadataResolver.loadJamProperties(java.nio.file.Paths.get(jam.getAbsolutePath()));
                String appName = properties.getProperty("AppName");
                if (appName != null && !appName.isBlank()) {
                    name = appName.trim();
                }
            } catch (IOException | RuntimeException exception) {
                opendoja.host.OpenDoJaLog.warn(HomeActivity.class, "cannot read jam metadata: " + jam.getName(), exception);
            }
            File nameFile = new File(jam.getParentFile(), stripExtension(jam.getName()) + ".name.txt");
            if (nameFile.exists()) {
                try {
                    String custom = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(nameFile.getAbsolutePath()))).trim();
                    if (!custom.isEmpty()) {
                        name = custom;
                    }
                } catch (IOException ignored) {
                }
            }
            games.add(new GameEntry(jam, name, loadGameIcon(jam)));
        }
        games.sort(Comparator.comparingLong((GameEntry e) -> e.jam.lastModified()).reversed());
        applySavedGameOrder(games);
        if (!searchQuery.isEmpty()) {
            String q = searchQuery.toLowerCase(Locale.ROOT);
            List<GameEntry> filtered = new ArrayList<>();
            for (GameEntry g : games) {
                if (g.displayName != null && g.displayName.toLowerCase(Locale.ROOT).contains(q)) {
                    filtered.add(g);
                }
            }
            games = filtered;
        }
        return games;
    }

    /**
     * Applies the order saved by edit mode. Matches by file name rather than
     * absolute path so a library moved between storage locations keeps its
     * layout. Games that are new (or were imported since the last save) keep
     * their last-modified order and are appended after the saved entries.
     */
    private void applySavedGameOrder(List<GameEntry> games) {
        String saved = getSharedPreferences(HOME_PREFS, MODE_PRIVATE)
                .getString(KEY_GAME_ORDER, "");
        if (saved == null || saved.isEmpty() || games.isEmpty()) {
            return;
        }
        Map<String, GameEntry> remaining = new LinkedHashMap<>();
        for (GameEntry game : games) {
            remaining.put(game.jam.getName(), game);
        }
        List<GameEntry> ordered = new ArrayList<>(games.size());
        for (String name : saved.split("\n")) {
            if (name.isEmpty()) {
                continue;
            }
            GameEntry game = remaining.remove(name);
            if (game != null) {
                ordered.add(game);
            }
        }
        ordered.addAll(remaining.values());
        games.clear();
        games.addAll(ordered);
    }

    private void persistGameOrder() {
        StringBuilder value = new StringBuilder();
        for (GameEntry game : currentGames) {
            if (value.length() > 0) {
                value.append('\n');
            }
            value.append(game.jam.getName());
        }
        getSharedPreferences(HOME_PREFS, MODE_PRIVATE)
                .edit().putString(KEY_GAME_ORDER, value.toString()).apply();
    }

    private Bitmap loadGameIcon(File jam) {
        File customIcon = new File(jam.getParentFile(), stripExtension(jam.getName()) + ".icon.png");
        if (customIcon.exists()) {
            Bitmap bmp = BitmapFactory.decodeFile(customIcon.getAbsolutePath());
            if (bmp != null) {
                Bitmap prepared = prepareGameIcon(bmp);
                if (prepared != bmp) {
                    bmp.recycle();
                }
                return prepared;
            }
        }
        File jar = siblingFile(jam, ".jar");
        String key = jam.getAbsolutePath() + ":" + jam.lastModified()
                + ":" + (jar != null && jar.exists() ? jar.lastModified() : 0);
        Bitmap cached = iconCache.get(key);
        if (cached != null) {
            return cached;
        }
        Bitmap icon = null;
        if (jar != null && jar.exists()) {
            try {
                Properties properties = JamMetadataResolver.loadJamProperties(java.nio.file.Paths.get(jam.getAbsolutePath()));
                String appIcon = properties.getProperty("AppIcon");
                if (appIcon != null && !appIcon.isBlank()) {
                    String[] candidates = appIcon.split(",");
                    // DoJa lists 48x48 first and 96x96 second: try the bigger one first.
                    for (int i = candidates.length - 1; i >= 0; i--) {
                        icon = decodeJarImage(jar, candidates[i].trim());
                        if (icon != null) {
                            break;
                        }
                    }
                }
            } catch (IOException | RuntimeException exception) {
                opendoja.host.OpenDoJaLog.warn(HomeActivity.class, "cannot read app icon: " + jam.getName(), exception);
            }
        }
        if (icon == null) {
            // No icon in the JAM: use the Pocket DoJa app icon, so resetting a
            // custom icon always returns to the same recognizable default.
            return defaultGameIconBitmap();
        }
        Bitmap prepared = prepareGameIcon(icon);
        if (prepared != icon) {
            icon.recycle();
        }
        iconCache.put(key, prepared);
        return prepared;
    }

    /**
     * Renders an icon into the square tile format used by the launcher, with
     * soft squircle corners instead of sharp square edges.
     */
    private Bitmap prepareGameIcon(Bitmap source) {
        int size = Math.max(1, iconSizePx);
        Bitmap output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Path clip = new Path();
        float radius = size * 0.235f;
        clip.addRoundRect(new RectF(0f, 0f, size, size), radius, radius, Path.Direction.CW);
        canvas.clipPath(clip);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        float scale = Math.min(size / (float) source.getWidth(),
                size / (float) source.getHeight());
        float width = source.getWidth() * scale;
        float height = source.getHeight() * scale;
        float left = (size - width) / 2f;
        float top = (size - height) / 2f;
        canvas.drawBitmap(source, null,
                new RectF(left, top, left + width, top + height), paint);
        return output;
    }

    private Bitmap defaultGameIconBitmap() {
        if (defaultGameIcon != null && defaultGameIcon.getWidth() == iconSizePx
                && !defaultGameIcon.isRecycled()) {
            return defaultGameIcon;
        }
        Bitmap raw = loadDefaultGameIconArtwork();
        defaultGameIcon = prepareGameIcon(raw);
        if (raw != defaultGameIcon) {
            raw.recycle();
        }
        return defaultGameIcon;
    }

    /**
     * Loads the full-square artwork used for games that ship no icon. The tile
     * mask is applied by {@link #prepareGameIcon(Bitmap)}, so the source keeps
     * its background in the corners; the pre-masked launcher icon does not and
     * would end up looking rounded instead of squircle.
     */
    private Bitmap loadDefaultGameIconArtwork() {
        int size = Math.max(1, iconSizePx);
        try {
            Bitmap artwork = BitmapFactory.decodeResource(getResources(), R.drawable.default_game_icon);
            if (artwork != null) {
                return artwork;
            }
        } catch (RuntimeException ignored) {
        }
        try {
            android.graphics.drawable.Drawable drawable =
                    getPackageManager().getApplicationIcon(getPackageName());
            if (drawable != null) {
                Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                drawable.setBounds(0, 0, size, size);
                drawable.draw(canvas);
                return bitmap;
            }
        } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException ignored) {
        }
        android.graphics.drawable.Drawable fallback = getDrawable(R.mipmap.ic_launcher);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        if (fallback != null) {
            Canvas canvas = new Canvas(bitmap);
            fallback.setBounds(0, 0, size, size);
            fallback.draw(canvas);
        }
        return bitmap;
    }

    private Bitmap decodeJarImage(File jar, String entryName) {
        try (ZipFile zip = new ZipFile(jar)) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                return null;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                Bitmap bitmap = BitmapFactory.decodeStream(in);
                if (bitmap == null) {
                    return null;
                }
                int width = bitmap.getWidth();
                int height = bitmap.getHeight();
                float scale = iconSizePx / (float) Math.max(width, height);
                if (Math.abs(scale - 1f) < 0.02f) {
                    return bitmap;
                }
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap,
                        Math.max(1, Math.round(width * scale)),
                        Math.max(1, Math.round(height * scale)), true);
                if (scaled != bitmap) {
                    bitmap.recycle();
                }
                return scaled;
            }
        } catch (IOException | RuntimeException exception) {
            opendoja.host.OpenDoJaLog.warn(HomeActivity.class, "cannot decode " + entryName + " from " + jar.getName(), exception);
            return null;
        }
    }

    private void saveCustomIcon(GameEntry game, Uri uri) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Cannot open selected file");
            byte[] data = readAllBytes(in);
            saveCustomIconBytes(game, data);
        }
    }

    private void saveCustomIconFromFile(GameEntry game, File file) throws IOException {
        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] data = readAllBytes(in);
            saveCustomIconBytes(game, data);
        }
    }

    private void saveCustomIconBytes(GameEntry game, byte[] data) throws IOException {
        Bitmap bmp = decodeIconBytes(data);
        if (bmp == null) throw new IOException("Unsupported or invalid image");
        Bitmap scaled = scaleIcon(bmp);
        File out = new File(game.jam.getParentFile(), stripExtension(game.jam.getName()) + ".icon.png");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            scaled.compress(Bitmap.CompressFormat.PNG, 100, fos);
        }
        if (scaled != bmp) scaled.recycle();
        bmp.recycle();
    }

    private Bitmap decodeIconBytes(byte[] data) {
        // Try standard decode first
        Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
        if (bmp != null) return bmp;
        // Try ICO: find largest PNG or BMP entry
        if (data.length > 6 && data[0] == 0 && data[1] == 0 && data[2] == 1 && data[3] == 0) {
            // ICO header: reserved(2), type(2), count(2)
            int count = (data[5] & 0xFF) << 8 | (data[4] & 0xFF);
            int bestSize = 0;
            int bestOffset = 0;
            int bestLen = 0;
            int off = 6;
            for (int i = 0; i < count; i++) {
                int w = data[off] == 0 ? 256 : (data[off] & 0xFF);
                int h = data[off + 1] == 0 ? 256 : (data[off + 1] & 0xFF);
                int size = (data[off + 8] & 0xFF) | ((data[off + 9] & 0xFF) << 8)
                        | ((data[off + 10] & 0xFF) << 16) | ((data[off + 11] & 0xFF) << 24);
                int imgOff = (data[off + 12] & 0xFF) | ((data[off + 13] & 0xFF) << 8)
                        | ((data[off + 14] & 0xFF) << 16) | ((data[off + 15] & 0xFF) << 24);
                if (size > bestSize) {
                    bestSize = size;
                    bestOffset = imgOff;
                    bestLen = size;
                }
                off += 16;
            }
            if (bestLen > 0) {
                Bitmap b = BitmapFactory.decodeByteArray(data, bestOffset, bestLen);
                if (b != null) return b;
            }
        }
        return null;
    }

    private Bitmap scaleIcon(Bitmap bmp) {
        int size = iconSizePx;
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        float scale = size / (float) Math.max(w, h);
        if (Math.abs(scale - 1f) < 0.02f) return bmp;
        return Bitmap.createScaledBitmap(bmp, Math.max(1, Math.round(w * scale)),
                Math.max(1, Math.round(h * scale)), true);
    }

    private byte[] readAllBytes(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) baos.write(buf, 0, n);
        return baos.toByteArray();
    }
    private void populateGrid(List<GameEntry> games) {
        String previousSelection = selectedGamePath;
        gridContainer.removeAllViews();
        currentGames.clear();
        gameItemViews.clear();
        if (games.isEmpty()) {
            selectedGamePath = null;
            emptyView.setVisibility(View.VISIBLE);
            gridContainer.setVisibility(View.GONE);
            return;
        }
        emptyView.setVisibility(View.GONE);
        gridContainer.setVisibility(View.VISIBLE);
        currentGames.addAll(games);

        if (isGridView()) {
            int marginPx = dp(5);
            // Live display width; see computeGridMetrics for why Resources is avoided.
            DisplayMetrics dm = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getMetrics(dm);
            int contentWidth = dm.widthPixels - dp(48);
            int cellSize = (contentWidth - (columns + 1) * marginPx) / columns;
            if (cellSize < dp(48)) cellSize = dp(48);
            LinearLayout row = null;
            for (int i = 0; i < games.size(); i++) {
                if (i % columns == 0) {
                    row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.START);
                    gridContainer.addView(row, matchWrap());
                }
                LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(cellSize, iconSizePx + dp(56));
                cellParams.setMargins(marginPx, marginPx, marginPx, marginPx);
                GameEntry game = games.get(i);
                LinearLayout cell = createGameCell(game);
                cell.setLayoutParams(cellParams);
                row.addView(cell);
                gameItemViews.put(game.jam.getAbsolutePath(), cell);
            }
        } else {
            for (int i = 0; i < games.size(); i++) {
                GameEntry game = games.get(i);
                LinearLayout row = createGameListRow(game, i + 1);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, 0, 0, dp(8));
                row.setLayoutParams(lp);
                gridContainer.addView(row);
                gameItemViews.put(game.jam.getAbsolutePath(), row);
            }
        }

        applyEditModePresentation(games);

        // Restore by path (never by list index/object identity): the previous
        // selection, then the last launched game, then the first game.
        String restore = previousSelection;
        if (restore == null || !gameItemViews.containsKey(restore)) {
            restore = getSharedPreferences(HOME_PREFS, MODE_PRIVATE).getString(KEY_LAST_GAME, null);
        }
        if (restore == null || !gameItemViews.containsKey(restore)) {
            restore = games.get(0).jam.getAbsolutePath();
        }
        selectedGamePath = restore;
        updateGameHighlight();
        gridContainer.post(() -> {
            if (searchBox == null || searchBox.getVisibility() != View.VISIBLE) {
                gridContainer.requestFocus();
            }
            ensureSelectedGameVisible();
        });
    }

    private LinearLayout createGameListRow(GameEntry game, int index) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable tile = new GradientDrawable();
        tile.setColor(themeSurfaceColor());
        tile.setCornerRadius(dp(8));
        tile.setStroke(dp(1), 0xFF2A2E36);
        row.setBackground(tile);

        TextView number = new TextView(this);
        number.setText(index + ".");
        number.setTextColor(0xFF888888);
        number.setTextSize(14f);
        number.setPadding(0, 0, dp(8), 0);
        row.addView(number);

        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setImageBitmap(game.icon);
        row.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));

        TextView name = new TextView(this);
        name.setText(game.displayName);
        name.setTextColor(themeTextColor());
        name.setTypeface(pixelifyTypeface(this, false));
        name.setTextSize(14f);
        name.setPadding(dp(8), 0, dp(8), 0);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(name, nameParams);

        File jar = siblingFile(game.jam, ".jar");
        String sizeText = (jar != null && jar.exists()) ? (jar.length() / 1024) + " KB" : "";
        TextView size = new TextView(this);
        size.setText(sizeText);
        size.setTextColor(0xFF888888);
        size.setTextSize(12f);
        row.addView(size);

        row.setTag(game.jam.getAbsolutePath());
        row.setClickable(true);
        row.setFocusable(false);
        row.setOnClickListener(v -> onGameTapped(game));
        row.setOnLongClickListener(v -> {
            // Move the selection to the pressed game first so the highlight
            // matches the item whose options are about to open.
            selectedGamePath = game.jam.getAbsolutePath();
            updateGameHighlight();
            ensureSelectedGameVisible();
            showGameOptions(game);
            return true;
        });
        return row;
    }

    private void confirmResetGameData(GameEntry game) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.reset_data_title))
                .setMessage(getString(R.string.reset_data_message))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> resetGameData(game))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void resetGameData(GameEntry game) {
        File jam = game.jam;
        String base = stripExtension(jam.getName());
        File dir = jam.getParentFile();
        File sp = new File(dir, base + ".sp");
        File orig = new File(dir, base + ".sp.orig");
        // Prefer the pristine import-time snapshot; fall back to the bundled sp/ copy.
        File source = orig.exists() ? orig : new File(new File(dir, "sp"), base + ".sp");
        if (!source.exists()) {
            Toast.makeText(this, getString(R.string.reset_data_unavailable), Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            java.nio.file.Files.copy(source.toPath(), sp.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            try {
                java.nio.file.Files.deleteIfExists(new File(sp.getAbsolutePath() + ".undo").toPath());
            } catch (IOException ignored) {
            }
            Toast.makeText(this, getString(R.string.reset_data_done), Toast.LENGTH_SHORT).show();
        } catch (IOException exception) {
            Toast.makeText(this, getString(R.string.reset_data_failed) + " " + exception.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
        refreshGames();
    }

    private void showRenameDialog(GameEntry game) {
        if (game == null) {
            return;
        }
        if (DisplayActivity.isSystemKeyboardEnabled(this)) {
            showRenameDialogWithSystemKeyboard(game);
        } else {
            showRenameDialogWithDpadKeyboard(game);
        }
    }

    private void showRenameDialogWithDpadKeyboard(GameEntry game) {
        if (rootView == null) {
            return;
        }
        closeRenameOverlay();
        renameTarget = game;
        renameText = game.displayName == null ? "" : game.displayName;

        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xB3000000);
        overlay.setClickable(true);
        overlay.setFocusable(true);
        FocusHighlight.disable(overlay);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(16), dp(20), dp(16));
        GradientDrawable panelBackground = new GradientDrawable();
        panelBackground.setColor(0xF020242E);
        panelBackground.setCornerRadius(dp(12));
        panel.setBackground(panelBackground);

        TextView title = new TextView(this);
        title.setText(R.string.rename_title);
        title.setTextColor(themeTextColor());
        title.setTextSize(20f);
        title.setTypeface(null, Typeface.BOLD);
        panel.addView(title, matchWrap());

        renameDisplay = new TextView(this);
        renameDisplay.setText(renameText);
        renameDisplay.setTextColor(0xFFE8E8E8);
        renameDisplay.setTextSize(18f);
        renameDisplay.setSingleLine(true);
        renameDisplay.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams displayParams = matchWrap();
        displayParams.topMargin = dp(10);
        panel.addView(renameDisplay, displayParams);

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        panelParams.gravity = Gravity.TOP;
        panelParams.setMargins(dp(16), dp(64), dp(16), 0);
        overlay.addView(panel, panelParams);

        DpadKeyboardView keyboard = new DpadKeyboardView(this);
        keyboard.setListener(new DpadKeyboardView.Listener() {
            @Override
            public void onText(String text) {
                renameText = renameText + text;
                updateRenameDisplay();
            }

            @Override
            public void onDelete() {
                if (!renameText.isEmpty()) {
                    renameText = renameText.substring(0, renameText.length() - 1);
                    updateRenameDisplay();
                }
            }

            @Override
            public void onSubmit() {
                commitRename();
            }

            @Override
            public void onCancel() {
                closeRenameOverlay();
            }
        });
        FrameLayout.LayoutParams keyboardParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, keyboardHeightPx());
        keyboardParams.gravity = Gravity.BOTTOM;
        overlay.addView(keyboard, keyboardParams);

        renameKeyboard = keyboard;
        renameOverlay = overlay;
        rootView.addView(overlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        overlay.requestFocus();
    }

    private void showRenameDialogWithSystemKeyboard(GameEntry game) {
        final EditText input = new EditText(this);
        input.setText(game.displayName == null ? "" : game.displayName);
        input.setSingleLine(true);
        input.setSelection(input.getText().length());
        input.setShowSoftInputOnFocus(true);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.rename_title)
                .setView(input)
                .setPositiveButton(R.string.edit_buttons_ok, (d, which) ->
                        saveRename(game, input.getText().toString()))
                .setNegativeButton(R.string.edit_buttons_cancel, null)
                .create();
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                saveRename(game, input.getText().toString());
                dialog.dismiss();
                return true;
            }
            return false;
        });
        dialog.setOnShowListener(d -> {
            input.requestFocus();
            input.setSelection(input.getText().length());
            input.postDelayed(() ->
                    showSystemKeyboard(input), 150);
        });
        dialog.show();
    }

    private void updateRenameDisplay() {
        if (renameDisplay != null) {
            renameDisplay.setText(renameText);
        }
    }

    private void commitRename() {
        GameEntry target = renameTarget;
        String newName = renameText;
        closeRenameOverlay();
        saveRename(target, newName);
    }

    private void saveRename(GameEntry target, String rawName) {
        String newName = rawName == null ? "" : rawName.trim();
        if (target == null || newName.isEmpty()) {
            return;
        }
        File nameFile = new File(target.jam.getParentFile(),
                stripExtension(target.jam.getName()) + ".name.txt");
        try (FileOutputStream fos = new FileOutputStream(nameFile)) {
            fos.write(newName.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
        refreshGames();
    }

    private void closeRenameOverlay() {
        if (renameOverlay != null && rootView != null) {
            rootView.removeView(renameOverlay);
        }
        renameOverlay = null;
        renameKeyboard = null;
        renameDisplay = null;
        renameTarget = null;
        renameText = "";
        if (gridContainer != null) {
            gridContainer.requestFocus();
        }
    }
    private void showGameOptions(GameEntry game) {
        if (editMode) {
            return;
        }
        new AlertDialog.Builder(this)
                .setItems(gameOptions, (dialog, which) -> {
                    if (which == 0) {
                        // Always use the built-in picker so the flow is identical
                        // on devices with and without DocumentsUI.
                        pickImageFileCustom(game);
                    } else if (which == 1) {
                        File customIcon = new File(game.jam.getParentFile(), stripExtension(game.jam.getName()) + ".icon.png");
                        if (customIcon.exists()) customIcon.delete();
                        refreshGames();
                    } else if (which == 2) {
                        showRenameDialog(game);
                    } else if (which == 3) {
                        Intent intent = new Intent(this, DisplayActivity.class);
                        intent.putExtra("gameId", game.jam.getName());
                        startActivity(intent);
                    } else if (which == 4) {
                        startExportSave(game);
                    } else if (which == 5) {
                        startImportSave(game);
                    } else if (which == 6) {
                        Intent kmIntent = new Intent(this, KeyMappingActivity.class);
                        kmIntent.putExtra("gameId", game.jam.getName());
                        startActivity(kmIntent);
                    } else if (which == 7) {
                        confirmResetGameData(game);
                    } else if (which == 8) {
                        pickSdDataFolder(game);
                    } else {
                        File jam = game.jam;
                        File jar = siblingFile(jam, ".jar");
                        if (jam.exists()) jam.delete();
                        if (jar != null && jar.exists()) jar.delete();
                        File customIcon = new File(jam.getParentFile(), stripExtension(jam.getName()) + ".icon.png");
                        if (customIcon.exists()) customIcon.delete();
                        File nameFile = new File(jam.getParentFile(), stripExtension(jam.getName()) + ".name.txt");
                        if (nameFile.exists()) nameFile.delete();
                        refreshGames();
                    }
                })
                .show();
    }
    private void startExportSave(GameEntry game) {
        // Built-in folder picker first: it is fully D-pad navigable. The
        // system CREATE_DOCUMENT screen is only a fallback for devices where
        // direct shared-storage access cannot be granted.
        runWithStorageAccess(true,
                () -> exportSaveCustom(game),
                () -> launchSafExportSave(game));
    }

    private void exportSaveCustom(GameEntry game) {
        CustomFilePicker.pickFolder(this, "Choose export folder", dir -> {
            final GameEntry target = game;
            new Thread(() -> {
                try {
                    File out = new File(dir, stripExtension(target.jam.getName()) + ".pdsave");
                    exportSaveToUri(target, Uri.fromFile(out));
                    runOnUiThread(() -> Toast.makeText(this,
                            "Save exported to " + out.getAbsolutePath(), Toast.LENGTH_LONG).show());
                } catch (Exception exception) {
                    runOnUiThread(() -> Toast.makeText(this,
                            "Export failed: " + exception.getMessage(), Toast.LENGTH_LONG).show());
                }
            }).start();
        });
    }

    private void launchSafExportSave(GameEntry game) {
        pendingSaveGame = game;
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/zip");
            intent.putExtra(Intent.EXTRA_TITLE,
                    stripExtension(game.jam.getName()) + ".pdsave");
            startActivityForResult(intent, REQUEST_EXPORT_SAVE);
        } catch (Exception exception) {
            pendingSaveGame = null;
            Toast.makeText(this, "No system file picker available", Toast.LENGTH_LONG).show();
        }
    }

    private void startImportSave(GameEntry game) {
        // Built-in file picker (filtered to save archives); fall back to the
        // system OPEN_DOCUMENT screen when direct storage is unavailable.
        runWithStorageAccess(false,
                () -> importSaveCustom(game),
                () -> launchSafImportSave(game));
    }

    private void importSaveCustom(GameEntry game) {
        CustomFilePicker.pickFile(this, "Select save file", new String[]{".pdsave", ".zip"}, file -> {
            final GameEntry target = game;
            new Thread(() -> {
                try {
                    importSaveFromUri(target, Uri.fromFile(file));
                    runOnUiThread(() -> Toast.makeText(this, "Save imported", Toast.LENGTH_SHORT).show());
                } catch (Exception exception) {
                    runOnUiThread(() -> Toast.makeText(this,
                            "Import failed: " + exception.getMessage(), Toast.LENGTH_LONG).show());
                }
            }).start();
        });
    }

    private void launchSafImportSave(GameEntry game) {
        pendingSaveGame = game;
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, REQUEST_IMPORT_SAVE);
        } catch (Exception exception) {
            pendingSaveGame = null;
            Toast.makeText(this, "No system file picker available", Toast.LENGTH_LONG).show();
        }
    }

    private void exportSaveToUri(GameEntry game, Uri uri) throws Exception {
        File jam = game.jam;
        String base = stripExtension(jam.getName());
        File sp = siblingFile(jam, ".sp");

        opendoja.host.LaunchConfig config = null;
        try {
            config = opendoja.host.JamLauncher.buildLaunchConfig(
                    java.nio.file.Paths.get(jam.getAbsolutePath()), false, java.nio.file.Paths.get(getFilesDir().getAbsolutePath()).resolve("scratchpad"));
        } catch (Exception ignored) {
        }
        String namespace = null;
        if (config != null) {
            String raw = config.sourceUrl();
            if (raw == null || raw.isBlank()) {
                raw = config.applicationClass() == null ? null : config.applicationClass().getName();
            }
            if (raw == null || raw.isBlank()) {
                raw = config.parameters().get("AppName");
            }
            if (raw != null && !raw.isBlank()) {
                namespace = java.util.UUID.nameUUIDFromBytes(
                        raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            }
        }

        java.util.List<String[]> entries = new java.util.ArrayList<>(); // relPath, absPath
        if (sp.exists()) {
            entries.add(new String[]{"games/" + base + ".sp", sp.getAbsolutePath()});
        }
        File scratchDir = new File(getFilesDir(), "scratchpad");
        if (scratchDir.isDirectory() && namespace != null) {
            final String ns = namespace;
            File[] files = scratchDir.listFiles((d, n) ->
                    n.startsWith("sp-" + ns + "-") && n.endsWith(".bin"));
            if (files != null) {
                for (File f : files) {
                    entries.add(new String[]{"scratchpad/" + f.getName(), f.getAbsolutePath()});
                }
            }
        }

        // SD storage scope (best effort)
        String scopeKey = null;
        String scopeDisplayName = null;
        if (config != null) {
            scopeKey = config.sourceUrl();
            if (scopeKey == null || scopeKey.isBlank()) {
                scopeKey = config.applicationClass() == null ? null : config.applicationClass().getName();
            }
            if (scopeKey == null || scopeKey.isBlank()) {
                scopeKey = config.parameters().get("AppName");
            }
            scopeDisplayName = config.parameters().get("AppName");
            if (scopeDisplayName == null || scopeDisplayName.isBlank()) {
                scopeDisplayName = config.applicationClass() == null ? null
                        : config.applicationClass().getSimpleName();
            }
        }
        if (scopeKey != null && !scopeKey.isBlank()) {
            String stableId = java.util.UUID.nameUUIDFromBytes(
                    scopeKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString()
                    .replace("-", "").toLowerCase(java.util.Locale.ROOT);
            File bindingRoot = new File(getFilesDir(), ".opendoja/storage/ext0");
            java.util.List<String> candidateDirs = new java.util.ArrayList<>();
            String sanitized = scopeDisplayName == null ? null : scopeDisplayName.trim();
            if (sanitized != null && !sanitized.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (char ch : sanitized.toCharArray()) {
                    if (Character.isLetterOrDigit(ch) || ch == '-' || ch == '_' || ch == '.') {
                        sb.append(ch);
                    } else {
                        sb.append('_');
                    }
                }
                candidateDirs.add(sb.toString());
            }
            candidateDirs.add("app-" + stableId);
            if (bindingRoot.isDirectory()) {
                for (String cand : candidateDirs) {
                    File scopeDir = new File(bindingRoot, cand);
                    if (scopeDir.isDirectory()) {
                        collectFiles(scopeDir, scopeDir, "storage/ext0/" + cand, entries);
                    }
                }
            }
        }

        if (entries.isEmpty()) {
            throw new IOException("No save data found for this game");
        }

        java.util.Properties manifest = new java.util.Properties();
        manifest.setProperty("pocketdoja.save.version", "1");
        manifest.setProperty("game.baseName", base);
        manifest.setProperty("game.jamName", jam.getName());
        manifest.setProperty("game.displayName", game.displayName == null ? "" : game.displayName);
        if (namespace != null) manifest.setProperty("legacy.namespace", namespace);

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos);
        // manifest
        java.util.zip.ZipEntry me = new java.util.zip.ZipEntry("manifest.properties");
        zos.putNextEntry(me);
        java.io.ByteArrayOutputStream mbos = new java.io.ByteArrayOutputStream();
        manifest.store(mbos, null);
        zos.write(mbos.toByteArray());
        zos.closeEntry();
        for (String[] e : entries) {
            File src = new File(e[1]);
            if (!src.isFile()) continue;
            java.util.zip.ZipEntry ze = new java.util.zip.ZipEntry(e[0]);
            zos.putNextEntry(ze);
            java.io.FileInputStream fis = new java.io.FileInputStream(src);
            byte[] buf = new byte[8192];
            int r;
            while ((r = fis.read(buf)) > 0) zos.write(buf, 0, r);
            fis.close();
            zos.closeEntry();
        }
        zos.finish();
        zos.close();

        try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new IOException("Cannot open destination");
            baos.writeTo(out);
            out.flush();
        }
    }

    private void collectFiles(File root, File dir, String prefix, java.util.List<String[]> entries) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File c : children) {
            if (c.isDirectory()) {
                collectFiles(root, c, prefix + "/" + c.getName(), entries);
            } else if (c.isFile()) {
                entries.add(new String[]{prefix + "/" + c.getName(), c.getAbsolutePath()});
            }
        }
    }

    private void importSaveFromUri(GameEntry game, Uri uri) throws Exception {
        File jam = game.jam;
        String base = stripExtension(jam.getName());
        java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(getContentResolver().openInputStream(uri));
        java.util.Properties manifest = null;
        java.util.Map<String, byte[]> files = new java.util.LinkedHashMap<>();
        java.util.zip.ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            if (entry.isDirectory()) { zis.closeEntry(); continue; }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = zis.read(buf)) > 0) bos.write(buf, 0, r);
            if ("manifest.properties".equals(entry.getName())) {
                manifest = new java.util.Properties();
                manifest.load(new java.io.ByteArrayInputStream(bos.toByteArray()));
            } else {
                files.put(entry.getName(), bos.toByteArray());
            }
            zis.closeEntry();
        }
        zis.close();
        if (manifest == null) {
            throw new IOException("Invalid save file (missing manifest)");
        }
        String savedBase = manifest.getProperty("game.baseName");
        if (savedBase == null || !savedBase.equals(base)) {
            throw new IOException("Save file is for a different game");
        }
        String savedJam = manifest.getProperty("game.jamName");
        if (savedJam != null && !savedJam.isEmpty() && !savedJam.equals(jam.getName())) {
            throw new IOException("Save file is for a different game");
        }

        // Extract files
        for (java.util.Map.Entry<String, byte[]> fe : files.entrySet()) {
            String rel = fe.getKey();
            if (rel.contains("..")) throw new IOException("Invalid entry path");
            File target;
            if (rel.startsWith("games/")) {
                target = new File(JamAssets.gamesDir(this), rel.substring("games/".length()));
            } else if (rel.startsWith("scratchpad/")) {
                target = new File(getFilesDir(), rel);
            } else if (rel.startsWith("storage/ext0/") || rel.startsWith(".opendoja/")) {
                // normalize
                String path = rel;
                if (path.startsWith("storage/")) {
                    path = ".opendoja/" + path;
                }
                target = new File(getFilesDir(), path);
            } else if (rel.startsWith(".opendoja/")) {
                target = new File(getFilesDir(), rel);
            } else {
                // skip unknown
                continue;
            }
            File parent = target.getParentFile();
            if (parent != null) parent.mkdirs();
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(target)) {
                fos.write(fe.getValue());
            }
        }
        // A freshly imported save must not be rolled back by a journal left over from the
        // session that corrupted the old save.
        try {
            File undo = new File(JamAssets.gamesDir(this), base + ".sp.undo");
            if (undo.exists()) {
                undo.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    private LinearLayout createGameCell(GameEntry game) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(dp(6), dp(10), dp(6), dp(10));
        cell.setClickable(true);
        cell.setFocusable(false);

        GradientDrawable tile = new GradientDrawable();
        tile.setColor(themeSurfaceColor());
        tile.setCornerRadius(dp(10));
        tile.setStroke(dp(1), 0xFF2A2E36);
        cell.setBackground(tile);

        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setImageBitmap(game.icon);
        cell.addView(icon, new LinearLayout.LayoutParams(iconSizePx, iconSizePx));

        TextView name = new TextView(this);
        name.setText(game.displayName);
        name.setTextColor(themeTextColor());
        name.setTypeface(pixelifyTypeface(this, false));
        name.setTextSize(13f);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(1);
        name.setSingleLine(true);
        name.setHorizontallyScrolling(true);
        name.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        // Scroll long names once instead of forever: an infinite marquee on
        // every grid cell keeps the whole grid redrawing constantly, which
        // burns CPU and heats up the device.
        name.setMarqueeRepeatLimit(1);
        name.setSelected(true);
        LinearLayout.LayoutParams nameParams = matchWrap();
        nameParams.topMargin = dp(6);
        cell.addView(name, nameParams);

        cell.setTag(game.jam.getAbsolutePath());
        cell.setOnClickListener(v -> onGameTapped(game));
        cell.setOnLongClickListener(v -> {
            // Move the selection to the pressed game first so the highlight
            // matches the item whose options are about to open.
            selectedGamePath = game.jam.getAbsolutePath();
            updateGameHighlight();
            ensureSelectedGameVisible();
            showGameOptions(game);
            return true;
        });
        LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(iconSizePx + dp(40), iconSizePx + dp(56));
        cellLp.setMargins(dp(5), dp(5), dp(5), dp(5));
        cell.setLayoutParams(cellLp);
        return cell;
    }

    // ------------------------------------------------------------------
    // Home-screen game navigation (touch + DPad/controller)
    // ------------------------------------------------------------------
    private void onGameTapped(GameEntry game) {
        String path = game.jam.getAbsolutePath();
        if (editMode) {
            // Selecting is still useful for the controller, but launching and
            // the game options menu stay disabled until editing finishes.
            selectedGamePath = path;
            updateGameHighlight();
            ensureSelectedGameVisible();
            if (gridContainer != null) {
                gridContainer.requestFocus();
            }
            return;
        }
        if (path.equals(selectedGamePath)) {
            launchJam(game.jam);
            return;
        }
        selectedGamePath = path;
        updateGameHighlight();
        ensureSelectedGameVisible();
        if (gridContainer != null) {
            gridContainer.requestFocus();
        }
    }

    private void updateGameHighlight() {
        boolean gridFocused = topBarFocusIndex < 0;
        for (Map.Entry<String, View> entry : gameItemViews.entrySet()) {
            boolean selected = gridFocused && entry.getKey().equals(selectedGamePath);
            boolean dropTarget = editMode
                    && entry.getKey().equals(dragHoverPath)
                    && !entry.getKey().equals(dragGamePath);
            applyGameItemBackground(entry.getValue(), selected, dropTarget);
        }
    }

    private void applyGameItemBackground(View item, boolean selected, boolean dropTarget) {
        if (item == null) {
            return;
        }
        // List rows are direct children of the grid container; grid cells are
        // children of a per-row LinearLayout.
        boolean listStyle = item.getParent() == gridContainer;
        GradientDrawable tile = new GradientDrawable();
        tile.setColor(themeSurfaceColor());
        tile.setCornerRadius(dp(listStyle ? 8 : 10));
        int strokeColor = selected ? 0xFFFFD600 : (dropTarget ? 0xFF4FC3F7 : 0xFF2A2E36);
        int strokeWidth = (selected || dropTarget) ? 4 : 1;
        tile.setStroke(dp(strokeWidth), strokeColor);
        item.setBackground(tile);
    }

    // ------------------------------------------------------------------
    // Layout edit mode (touch + controller)
    // ------------------------------------------------------------------
    private void onEditButtonClicked() {
        if (editMode) {
            requestExitEditMode();
        } else {
            enterEditMode();
        }
    }

    private void enterEditMode() {
        if (currentGames.isEmpty() && searchQuery.isEmpty()) {
            return;
        }
        // A filtered list would save an incomplete order, so search is closed
        // before editing starts.
        if (searchBox != null && searchBox.getVisibility() == View.VISIBLE) {
            toggleSearch();
        }
        searchQuery = "";
        editMode = true;
        editOrderDirty = false;
        pendingEditEntrance = true;
        topBarFocusIndex = -1;
        updateTopBarFocus();
        updateEditButtonAppearance();
        updateGameHighlight();
        if (gridContainer != null) {
            gridContainer.requestFocus();
        }
        refreshGames(true);
        Toast.makeText(this, getString(R.string.edit_hint), Toast.LENGTH_LONG).show();
        playNavSound();
    }

    private void requestExitEditMode() {
        if (!editMode) {
            return;
        }
        if (dragOverlay != null) {
            finishTouchDrag(null);
        }
        if (controllerDragging) {
            endControllerDrag();
        }
        if (!editOrderDirty) {
            exitEditMode();
            return;
        }
        // Let the tiles rest while the decision dialog is open.
        stopWobbleAnimations();
        new AlertDialog.Builder(this)
                .setTitle(R.string.save_changes_title)
                .setMessage(R.string.save_changes_message)
                .setPositiveButton(R.string.save_changes_save, (dialog, which) -> {
                    persistGameOrder();
                    exitEditMode();
                })
                .setNegativeButton(R.string.save_changes_discard, (dialog, which) -> {
                    exitEditMode();
                    refreshGames();
                })
                .setNeutralButton(android.R.string.cancel, null)
                .setOnDismissListener(dialog -> {
                    if (editMode) {
                        startWobbleAnimations();
                    }
                })
                .show();
    }

    private void exitEditMode() {
        editMode = false;
        editOrderDirty = false;
        pendingEditEntrance = false;
        dragHoverPath = null;
        controllerDragging = false;
        controllerDragPath = null;
        controllerDragMoved = false;
        stopWobbleAnimations();
        updateEditButtonAppearance();
        updateTopBarFocus();
        updateGameHighlight();
        if (!currentGames.isEmpty()) {
            populateGrid(new ArrayList<>(currentGames));
        }
        if (gridContainer != null) {
            gridContainer.requestFocus();
        }
        playNavSound();
    }

    private void applyEditModePresentation(List<GameEntry> games) {
        if (!editMode) {
            return;
        }
        for (GameEntry game : games) {
            View item = gameItemViews.get(game.jam.getAbsolutePath());
            if (item == null) {
                continue;
            }
            item.setOnLongClickListener(v -> {
                beginTouchDrag(game, item);
                return true;
            });
        }
        startWobbleAnimations();
        if (pendingEditEntrance) {
            pendingEditEntrance = false;
            animateEditEntrance();
        }
        if (controllerDragging && controllerDragPath != null) {
            View lifted = gameItemViews.get(controllerDragPath);
            if (lifted != null) {
                lifted.setScaleX(1.06f);
                lifted.setScaleY(1.06f);
                lifted.setAlpha(0.94f);
                lifted.setElevation(dp(8));
            }
        }
    }

    private void startWobbleAnimations() {
        cancelWobbleAnimations();
        if (!editMode) {
            return;
        }
        int index = 0;
        for (Map.Entry<String, View> entry : gameItemViews.entrySet()) {
            View icon = findGameIconView(entry.getValue());
            if (icon == null) {
                continue;
            }
            PropertyValuesHolder rotation = PropertyValuesHolder.ofFloat(
                    View.ROTATION, -2.8f, 2.8f);
            PropertyValuesHolder scaleX = PropertyValuesHolder.ofFloat(
                    View.SCALE_X, 1f, 1.025f);
            PropertyValuesHolder scaleY = PropertyValuesHolder.ofFloat(
                    View.SCALE_Y, 1f, 1.025f);
            ObjectAnimator animator = ObjectAnimator.ofPropertyValuesHolder(
                    icon, rotation, scaleX, scaleY);
            animator.setDuration(210L + (index % 4) * 45L);
            animator.setStartDelay((index % 6) * 35L);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setRepeatMode(ValueAnimator.REVERSE);
            animator.setInterpolator(new AccelerateDecelerateInterpolator());
            animator.start();
            wobbleAnimators.put(entry.getKey(), animator);
            index++;
        }
    }

    private void cancelWobbleAnimations() {
        for (Animator animator : wobbleAnimators.values()) {
            animator.cancel();
        }
        wobbleAnimators.clear();
    }

    private void stopWobbleAnimations() {
        cancelWobbleAnimations();
        for (View item : gameItemViews.values()) {
            View icon = findGameIconView(item);
            if (icon != null) {
                icon.animate().cancel();
                icon.setRotation(0f);
                icon.setScaleX(1f);
                icon.setScaleY(1f);
            }
            item.animate().cancel();
            item.setAlpha(1f);
            item.setScaleX(1f);
            item.setScaleY(1f);
            item.setElevation(0f);
        }
    }

    private View findGameIconView(View item) {
        if (item instanceof ImageView) {
            return item;
        }
        if (item instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) item;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (group.getChildAt(i) instanceof ImageView) {
                    return group.getChildAt(i);
                }
            }
        }
        return null;
    }

    private void animateEditEntrance() {
        int index = 0;
        for (View item : gameItemViews.values()) {
            item.animate().cancel();
            item.setAlpha(0.35f);
            item.setScaleX(0.92f);
            item.setScaleY(0.92f);
            item.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setStartDelay(Math.min(index * 20L, 320L))
                    .setDuration(300L)
                    .setInterpolator(new OvershootInterpolator(2.2f))
                    .start();
            index++;
        }
    }

    private void animateSettle(String path) {
        View item = gameItemViews.get(path);
        if (item == null) {
            return;
        }
        item.animate().cancel();
        item.setScaleX(0.9f);
        item.setScaleY(0.9f);
        item.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(260L)
                .setInterpolator(new OvershootInterpolator(2.6f))
                .start();
    }

    // ---- Touch: long-press a wobbling tile and drop it on another ----

    private void beginTouchDrag(GameEntry game, View source) {
        if (!editMode || controllerDragging || game == null || source == null) {
            return;
        }
        removeDragOverlayNow();
        dragGamePath = game.jam.getAbsolutePath();
        dragSourceView = source;
        dragHoverPath = null;
        selectedGamePath = dragGamePath;
        source.setAlpha(0.25f);
        updateGameHighlight();
        ButtonHaptics.tick(this);
        playNavSound();
        createDragOverlay(game, lastTouchRawX, lastTouchRawY);
    }

    private void createDragOverlay(GameEntry game, float rawX, float rawY) {
        if (rootView == null) {
            return;
        }
        int size = Math.max(dp(56), Math.min(iconSizePx + dp(18), dp(96)));
        dragOverlaySizePx = size;
        ImageView bubble = new ImageView(this);
        bubble.setScaleType(ImageView.ScaleType.FIT_CENTER);
        bubble.setImageBitmap(game.icon);
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(0xF2FFFFFF);
        background.setStroke(dp(2), 0xFFFFD600);
        bubble.setBackground(background);
        bubble.setPadding(dp(10), dp(10), dp(10), dp(10));
        bubble.setElevation(dp(12));
        bubble.setAlpha(0f);
        bubble.setScaleX(0.7f);
        bubble.setScaleY(0.7f);
        rootView.addView(bubble, new FrameLayout.LayoutParams(size, size));
        dragOverlay = bubble;
        positionDragOverlay(rawX, rawY);
        bubble.animate()
                .alpha(0.96f)
                .scaleX(1.06f)
                .scaleY(1.06f)
                .setDuration(140L)
                .setInterpolator(new OvershootInterpolator(2f))
                .start();
    }

    private void positionDragOverlay(float rawX, float rawY) {
        if (dragOverlay == null || rootView == null) {
            return;
        }
        int[] location = new int[2];
        rootView.getLocationOnScreen(location);
        float half = dragOverlaySizePx / 2f;
        dragOverlay.setX(rawX - location[0] - half);
        dragOverlay.setY(rawY - location[1] - half);
    }

    private boolean handleTouchDragEvent(android.view.MotionEvent event) {
        switch (event.getActionMasked()) {
            case android.view.MotionEvent.ACTION_MOVE:
                lastTouchRawX = event.getRawX();
                lastTouchRawY = event.getRawY();
                autoScrollDuringDrag(lastTouchRawY);
                positionDragOverlay(lastTouchRawX, lastTouchRawY);
                updateDragHover(findDropTarget(lastTouchRawX, lastTouchRawY));
                return true;
            case android.view.MotionEvent.ACTION_UP:
                lastTouchRawX = event.getRawX();
                lastTouchRawY = event.getRawY();
                finishTouchDrag(findDropTarget(lastTouchRawX, lastTouchRawY));
                return true;
            case android.view.MotionEvent.ACTION_CANCEL:
                finishTouchDrag(null);
                return true;
            default:
                return true;
        }
    }

    private void updateDragHover(String targetPath) {
        if (TextUtils.equals(dragHoverPath, targetPath)) {
            return;
        }
        dragHoverPath = targetPath;
        updateGameHighlight();
        if (targetPath != null) {
            playNavSound();
        }
    }

    private void autoScrollDuringDrag(float rawY) {
        if (gameScrollView == null) {
            return;
        }
        int[] location = new int[2];
        gameScrollView.getLocationOnScreen(location);
        int top = location[1] + dp(56) + dp(8);
        int bottom = location[1] + gameScrollView.getHeight() - dp(8);
        int edge = dp(16);
        int step = dp(18);
        if (rawY < top + edge) {
            gameScrollView.scrollBy(0, -step);
        } else if (rawY > bottom - edge) {
            gameScrollView.scrollBy(0, step);
        }
    }

    private String findDropTarget(float rawX, float rawY) {
        int[] location = new int[2];
        int padding = dp(6);
        for (Map.Entry<String, View> entry : gameItemViews.entrySet()) {
            View item = entry.getValue();
            if (item == null || item == dragSourceView) {
                continue;
            }
            item.getLocationOnScreen(location);
            int left = location[0] - padding;
            int top = location[1] - padding;
            int right = location[0] + item.getWidth() + padding;
            int bottom = location[1] + item.getHeight() + padding;
            if (rawX >= left && rawX <= right && rawY >= top && rawY <= bottom) {
                return entry.getKey();
            }
        }
        return null;
    }

    private void finishTouchDrag(String targetPath) {
        String draggedPath = dragGamePath;
        View source = dragSourceView;
        if (source != null) {
            source.setAlpha(1f);
        }
        dragGamePath = null;
        dragSourceView = null;
        dragHoverPath = null;
        animateDragOverlayOut();
        if (draggedPath == null) {
            updateGameHighlight();
            return;
        }
        if (targetPath != null && !targetPath.equals(draggedPath)) {
            int targetIndex = indexOfGame(targetPath);
            if (targetIndex >= 0 && moveGameToIndex(draggedPath, targetIndex)) {
                editOrderDirty = true;
                selectedGamePath = draggedPath;
                populateGrid(new ArrayList<>(currentGames));
                animateSettle(draggedPath);
                ensureSelectedGameVisible();
                ButtonHaptics.tick(this);
                playNavSound();
                return;
            }
        }
        updateGameHighlight();
    }

    private void animateDragOverlayOut() {
        final ImageView overlay = dragOverlay;
        if (overlay == null) {
            return;
        }
        dragOverlay = null;
        overlay.animate().cancel();
        overlay.animate()
                .alpha(0f)
                .scaleX(0.72f)
                .scaleY(0.72f)
                .setDuration(120L)
                .withEndAction(() -> {
                    if (overlay.getParent() instanceof android.view.ViewGroup) {
                        ((android.view.ViewGroup) overlay.getParent()).removeView(overlay);
                    }
                })
                .start();
    }

    private void removeDragOverlayNow() {
        if (dragOverlay == null) {
            return;
        }
        ImageView overlay = dragOverlay;
        dragOverlay = null;
        overlay.animate().cancel();
        if (overlay.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) overlay.getParent()).removeView(overlay);
        }
    }

    private boolean moveGameToIndex(String path, int targetIndex) {
        int from = indexOfGame(path);
        if (from < 0 || targetIndex < 0 || targetIndex >= currentGames.size() || from == targetIndex) {
            return false;
        }
        GameEntry game = currentGames.remove(from);
        currentGames.add(targetIndex, game);
        return true;
    }

    // ---- Controller: hold Select, move with the D-pad, release to settle ----

    private void beginControllerDrag() {
        if (!editMode || controllerDragging || selectedGamePath == null) {
            return;
        }
        if (indexOfGame(selectedGamePath) < 0) {
            return;
        }
        controllerDragging = true;
        controllerDragPath = selectedGamePath;
        controllerDragMoved = false;
        navSelectPressed = true;
        navSelectLongPressed = false;
        ButtonHaptics.tick(this);
        playNavSound();
        View item = gameItemViews.get(controllerDragPath);
        if (item != null) {
            item.animate().cancel();
            item.animate()
                    .scaleX(1.06f)
                    .scaleY(1.06f)
                    .alpha(0.94f)
                    .setDuration(140L)
                    .start();
            item.setElevation(dp(8));
        }
    }

    private void moveControllerDrag(int keyCode) {
        int index = indexOfGame(controllerDragPath);
        if (index < 0) {
            return;
        }
        int count = currentGames.size();
        int next = index;
        if (isGridView()) {
            int column = index % columns;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (column > 0) {
                    next = index - 1;
                }
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (column < columns - 1 && index + 1 < count) {
                    next = index + 1;
                }
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                if (index - columns >= 0) {
                    next = index - columns;
                }
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (index + columns < count) {
                    next = index + columns;
                }
            }
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            next = Math.max(0, index - 1);
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            next = Math.min(count - 1, index + 1);
        }
        if (next == index) {
            return;
        }
        GameEntry game = currentGames.remove(index);
        currentGames.add(next, game);
        controllerDragMoved = true;
        editOrderDirty = true;
        selectedGamePath = controllerDragPath;
        populateGrid(new ArrayList<>(currentGames));
        ensureSelectedGameVisible();
        playNavSound();
    }

    private void endControllerDrag() {
        if (!controllerDragging) {
            return;
        }
        String path = controllerDragPath != null ? controllerDragPath : selectedGamePath;
        boolean moved = controllerDragMoved;
        controllerDragging = false;
        controllerDragPath = null;
        controllerDragMoved = false;
        View item = gameItemViews.get(path);
        if (item != null) {
            item.animate().cancel();
            item.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(220L)
                    .setInterpolator(new OvershootInterpolator(2.5f))
                    .start();
            item.setElevation(0f);
        }
        updateGameHighlight();
        if (moved) {
            ButtonHaptics.tick(this);
        }
    }

    private void ensureSelectedGameVisible() {
        if (gameScrollView == null || selectedGamePath == null) {
            return;
        }
        View target = gameItemViews.get(selectedGamePath);
        if (target == null || target.getWidth() == 0) {
            return;
        }
        // offsetDescendantRectToMyCoords returns the item's bounds in the
        // ScrollView's CONTENT coordinates (the ScrollView's own scroll is not
        // subtracted), so the visible bounds must include getScrollY().
        Rect rect = new Rect();
        target.getDrawingRect(rect);
        gameScrollView.offsetDescendantRectToMyCoords(target, rect);
        int topBound = gameScrollView.getScrollY() + dp(56) + dp(8);
        int bottomBound = gameScrollView.getScrollY() + gameScrollView.getHeight() - dp(8);
        int delta = 0;
        if (rect.top < topBound) {
            delta = rect.top - topBound;
        } else if (rect.bottom > bottomBound) {
            delta = rect.bottom - bottomBound;
        }
        if (delta != 0) {
            // Scroll to an absolute target position so rapid key presses stay
            // accurate even while the smooth scroll from a previous press is
            // still animating (recomputed deltas never compound).
            gameScrollView.smoothScrollTo(0, gameScrollView.getScrollY() + delta);
        }
    }

    private void moveSelection(int dx, int dy) {
        if (currentGames.isEmpty()) {
            return;
        }
        int index = indexOfGame(selectedGamePath);
        if (index < 0) {
            index = 0;
        }
        int count = currentGames.size();
        int next = index;
        if (isGridView()) {
            int column = index % columns;
            if (dx < 0) {
                if (column > 0) {
                    next = index - 1;
                }
            } else if (dx > 0) {
                if (column < columns - 1 && index + 1 < count) {
                    next = index + 1;
                }
            }
            if (dy < 0) {
                if (index - columns >= 0) {
                    next = index - columns;
                }
            } else if (dy > 0 && index + columns < count) {
                next = index + columns;
            }
        } else if (dy != 0) {
            next = Math.max(0, Math.min(count - 1, index + dy));
        }
        if (next == index) {
            ensureSelectedGameVisible();
            return;
        }
        selectedGamePath = currentGames.get(next).jam.getAbsolutePath();
        updateGameHighlight();
        ensureSelectedGameVisible();
        playNavSound();
    }

    private int indexOfGame(String path) {
        if (path == null) {
            return -1;
        }
        for (int i = 0; i < currentGames.size(); i++) {
            if (path.equals(currentGames.get(i).jam.getAbsolutePath())) {
                return i;
            }
        }
        return -1;
    }

    private void launchSelectedGame() {
        if (editMode || selectedGamePath == null) {
            return;
        }
        for (GameEntry game : currentGames) {
            if (selectedGamePath.equals(game.jam.getAbsolutePath())) {
                launchJam(game.jam);
                return;
            }
        }
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        if (event != null) {
            int action = event.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                lastTouchRawX = event.getRawX();
                lastTouchRawY = event.getRawY();
            }
            // Once a tile has been picked up, the rest of the gesture belongs
            // to the drag overlay rather than to the tile underneath.
            if (dragOverlay != null) {
                return handleTouchDragEvent(event);
            }
            // Any touch takes focus away from the remote-only top bar highlight.
            if (action == android.view.MotionEvent.ACTION_DOWN && topBarFocusIndex >= 0) {
                setTopBarFocus(-1, false);
            }
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event == null) {
            return super.dispatchKeyEvent(event);
        }
        int keyCode = event.getKeyCode();
        int action = event.getAction();
        // Rename uses a full-screen in-app keyboard overlay. It consumes the
        // whole input until the user submits or cancels.
        if (renameOverlay != null) {
            if (renameKeyboard != null && renameKeyboard.handleKeyEvent(event)) {
                return true;
            }
            if (action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_BACK) {
                closeRenameOverlay();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP
                    || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                return super.dispatchKeyEvent(event);
            }
            return true;
        }
        // While the in-app search keyboard is visible, D-pad navigation and
        // typing belong to it.
        if (isSearchKeyboardVisible()) {
            if (action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_BACK) {
                toggleSearch();
                return true;
            }
            if (searchKeyboard.handleKeyEvent(event)) {
                return true;
            }
        }
        // Back closes the search bar instead of leaving the app.
        if (action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_BACK
                && searchBox != null && searchBox.getVisibility() == View.VISIBLE) {
            toggleSearch();
            return true;
        }
        // Back from the top bar returns focus to the game grid.
        if (action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_BACK
                && topBarFocusIndex >= 0) {
            setTopBarFocus(-1, true);
            return true;
        }
        // Back also leaves layout editing, with the save/discard prompt.
        if (action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_BACK && editMode) {
            requestExitEditMode();
            return true;
        }
        if (!isGameNavigationKey(keyCode)) {
            return super.dispatchKeyEvent(event);
        }
        // Let the search field keep typing/cursor movement while it is open.
        if (searchBox != null && searchBox.getVisibility() == View.VISIBLE && searchBox.hasFocus()) {
            return super.dispatchKeyEvent(event);
        }
        if (menuPopup != null && menuPopup.isShowing()) {
            return super.dispatchKeyEvent(event);
        }
        if (topBarFocusIndex >= 0) {
            return handleTopBarKeyEvent(event, keyCode, action);
        }
        if (action == KeyEvent.ACTION_DOWN) {
            boolean repeating = event.getRepeatCount() > 0;
            long eventTime = event.getEventTime();
            if (!repeating) {
                if (keyCode == lastNavDownKey && eventTime - lastNavDownTime < NAV_DUPLICATE_DOWN_MS) {
                    return true;
                }
                lastNavDownKey = keyCode;
                lastNavDownTime = eventTime;
            }
            if (isGameMovementKey(keyCode)) {
                if (editMode && controllerDragging) {
                    // The held game travels with the D-pad instead of moving
                    // the highlight onto a different tile.
                    moveControllerDrag(keyCode);
                } else {
                    switch (keyCode) {
                        case KeyEvent.KEYCODE_DPAD_UP:
                            // Up from the first row moves focus to the top bar
                            // (Search / view toggle / edit / menu) instead of stopping.
                            if (currentGames.isEmpty() || isSelectionInFirstRow()) {
                                setTopBarFocus(0, true);
                            } else {
                                moveSelection(0, -1);
                            }
                            break;
                        case KeyEvent.KEYCODE_DPAD_DOWN:
                            moveSelection(0, 1);
                            break;
                        case KeyEvent.KEYCODE_DPAD_LEFT:
                            moveSelection(-1, 0);
                            break;
                        case KeyEvent.KEYCODE_DPAD_RIGHT:
                            moveSelection(1, 0);
                            break;
                        default:
                            break;
                    }
                }
            } else if (!repeating) {
                if (editMode) {
                    // Press and hold Select to pick the highlighted game up.
                    beginControllerDrag();
                } else {
                    // Confirm/Select (D-pad center, OK, Enter): launch on release
                    // so a long press can open the game options menu instead.
                    navSelectLongPressed = false;
                    navSelectPressed = true;
                    armNavSelectLongPress();
                }
            } else if (editMode && controllerDragging) {
                // Repeats belong to the held tile; ignore them.
            } else if (navSelectPressed && !navSelectLongPressed) {
                // Fallback: a repeat means the key is being held even if the
                // Handler above has not fired yet.
                navSelectLongPressed = true;
                cancelNavSelectLongPress();
                showSelectedGameOptions();
            }
            return true;
        }
        if (action == KeyEvent.ACTION_UP) {
            if (isGameMovementKey(keyCode)) {
                return true;
            }
            boolean wasPressed = navSelectPressed;
            boolean longPressed = navSelectLongPressed;
            navSelectPressed = false;
            navSelectLongPressed = false;
            cancelNavSelectLongPress();
            if (editMode) {
                // Releasing Select settles the held game in its new slot.
                endControllerDrag();
                return true;
            }
            if (wasPressed && !longPressed) {
                launchSelectedGame();
            }
            return true;
        }
        // Consume both DOWN and UP so neither the ScrollView nor a focused
        // view can act on the same key press.
        return true;
    }

    private void armNavSelectLongPress() {
        if (navKeyHandler == null) {
            navKeyHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        }
        if (navSelectLongPressRunnable == null) {
            navSelectLongPressRunnable = () -> {
                if (!navSelectPressed) {
                    return;
                }
                navSelectLongPressed = true;
                showSelectedGameOptions();
            };
        }
        navKeyHandler.removeCallbacks(navSelectLongPressRunnable);
        navKeyHandler.postDelayed(navSelectLongPressRunnable, NAV_LONG_PRESS_MS);
    }

    private void cancelNavSelectLongPress() {
        if (navKeyHandler != null && navSelectLongPressRunnable != null) {
            navKeyHandler.removeCallbacks(navSelectLongPressRunnable);
        }
    }

    /** Opens the options for the D-pad-highlighted game (remote long press). */
    private void showSelectedGameOptions() {
        if (editMode || selectedGamePath == null) {
            return;
        }
        for (GameEntry game : currentGames) {
            if (selectedGamePath.equals(game.jam.getAbsolutePath())) {
                showGameOptions(game);
                return;
            }
        }
    }

    // ---- D-pad focus for the fixed top bar ----

    private boolean handleTopBarKeyEvent(KeyEvent event, int keyCode, int action) {
        if (action == KeyEvent.ACTION_DOWN) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    moveTopBarFocus(-1);
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    moveTopBarFocus(1);
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    setTopBarFocus(-1, true);
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    break;
                default:
                    if (isGameSelectKey(keyCode) && event.getRepeatCount() == 0) {
                        navSelectPressed = true;
                    }
                    break;
            }
            return true;
        }
        if (action == KeyEvent.ACTION_UP) {
            if (isGameSelectKey(keyCode)) {
                boolean wasPressed = navSelectPressed;
                navSelectPressed = false;
                if (wasPressed) {
                    activateTopBarFocus();
                }
            }
            return true;
        }
        return true;
    }

    private void moveTopBarFocus(int delta) {
        int next = Math.max(0, Math.min(3, topBarFocusIndex + delta));
        if (next == topBarFocusIndex) {
            return;
        }
        setTopBarFocus(next, true);
    }

    private void setTopBarFocus(int index, boolean playSound) {
        if (index < 0) {
            if (topBarFocusIndex < 0) {
                return;
            }
            topBarFocusIndex = -1;
        } else {
            int clamped = Math.min(3, index);
            if (clamped == topBarFocusIndex) {
                return;
            }
            topBarFocusIndex = clamped;
        }
        updateTopBarFocus();
        updateGameHighlight();
        if (topBarFocusIndex < 0) {
            ensureSelectedGameVisible();
        }
        if (playSound) {
            playNavSound();
        }
    }

    private void updateTopBarFocus() {
        setTopBarButtonFocused(searchButton, topBarFocusIndex == 0);
        setTopBarButtonFocused(viewToggleButton, topBarFocusIndex == 1);
        setTopBarButtonFocused(editButton, topBarFocusIndex == 2);
        setTopBarButtonFocused(menuButton, topBarFocusIndex == 3);
    }

    private void setTopBarButtonFocused(TextView button, boolean focused) {
        if (button == null) {
            return;
        }
        if (focused) {
            GradientDrawable highlight = new GradientDrawable();
            highlight.setColor(0x663080FF);
            highlight.setCornerRadius(dp(8));
            button.setForeground(highlight);
        } else {
            button.setForeground(null);
        }
    }

    private void activateTopBarFocus() {
        int index = topBarFocusIndex;
        if (index < 0) {
            return;
        }
        if (index == 0) {
            // toggleSearch() explains why search is unavailable while editing;
            // keep the top-bar focus in that case so Edit stays reachable.
            if (!editMode) {
                setTopBarFocus(-1, false);
            }
            toggleSearch();
        } else if (index == 1) {
            if (viewToggleButton != null) {
                viewToggleButton.performClick();
            }
        } else if (index == 2) {
            setTopBarFocus(-1, false);
            onEditButtonClicked();
        } else if (menuButton != null) {
            setTopBarFocus(-1, false);
            showOptionsMenu(menuButton);
        }
    }

    private boolean isSelectionInFirstRow() {
        if (currentGames.isEmpty()) {
            return true;
        }
        int index = indexOfGame(selectedGamePath);
        if (index < 0) {
            index = 0;
        }
        return isGridView() ? index < columns : index == 0;
    }

    private static boolean isGameSelectKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                || keyCode == KeyEvent.KEYCODE_BUTTON_A;
    }

    private static boolean isGameMovementKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    private static boolean isGameNavigationKey(int keyCode) {
        return isGameMovementKey(keyCode) || isGameSelectKey(keyCode);
    }

    private void initNavSound() {
        if (navSoundPool != null) {
            return;
        }
        try {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            navSoundPool = new SoundPool.Builder()
                    .setMaxStreams(2)
                    .setAudioAttributes(attributes)
                    .build();
            navSoundPool.setOnLoadCompleteListener((pool, sampleId, status) -> {
                navSoundId = sampleId;
                navSoundReady = status == 0;
            });
            AssetFileDescriptor descriptor = getAssets().openFd("nav.mp3");
            try {
                navSoundPool.load(descriptor, 1);
            } finally {
                descriptor.close();
            }
        } catch (Exception exception) {
            opendoja.host.OpenDoJaLog.warn(HomeActivity.class, "navigation sound unavailable", exception);
            releaseNavSound();
        }
    }

    private void playNavSound() {
        if (!navSoundReady || navSoundPool == null || navSoundId == 0) {
            return;
        }
        try {
            navSoundPool.play(navSoundId, 0.5f, 0.5f, 1, 0, 1f);
        } catch (RuntimeException ignored) {
        }
    }

    private void releaseNavSound() {
        navSoundReady = false;
        navSoundId = 0;
        if (navSoundPool != null) {
            try {
                navSoundPool.release();
            } catch (RuntimeException ignored) {
            }
            navSoundPool = null;
        }
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** dp value multiplied by the top bar's proportional scale factor. */
    private int scaledDp(float value, float scale) {
        return Math.round(value * scale * getResources().getDisplayMetrics().density);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private void onLoadJamClicked() {
        showJamPicker();
    }

    private void showJamPicker() {
        File gamesDir = JamAssets.gamesDir(this);
        File[] jams = gamesDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".jam"));
        if (jams == null) {
            jams = new File[0];
        }
        Arrays.sort(jams, Comparator.comparing(File::getName));
        List<File> jamList = Arrays.asList(jams);

        String[] labels = new String[jamList.size() + 1];
        for (int i = 0; i < jamList.size(); i++) {
            labels[i] = describeJam(jamList.get(i));
        }
        labels[jamList.size()] = "Import from folder...";

        new AlertDialog.Builder(this)
                .setTitle("Load JAM")
                .setItems(labels, (dialog, which) -> {
                    if (which < jamList.size()) {
                        launchJam(jamList.get(which));
                    } else {
                        pickImportFolder();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String describeJam(File jam) {
        String name = jam.getName();
        try {
            Properties properties = JamMetadataResolver.loadJamProperties(java.nio.file.Paths.get(jam.getAbsolutePath()));
            String appName = properties.getProperty("AppName");
            if (appName != null && !appName.isBlank()) {
                return appName + " (" + name + ")";
            }
        } catch (IOException ignored) {
        }
        return name;
    }

    private void launchJam(File jam) {
        if (jam == null) {
            return;
        }
        String path = jam.getAbsolutePath();
        selectedGamePath = path;
        getSharedPreferences(HOME_PREFS, MODE_PRIVATE).edit()
                .putString(KEY_LAST_GAME, path).apply();
        Intent intent = new Intent(this, EntryActivity.class);
        intent.putExtra("jamPath", path);
        startActivity(intent);
    }

    private void onDeleteClicked() {
        File gamesDir = JamAssets.gamesDir(this);
        File[] found = gamesDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".jam"));
        final File[] jams = found == null ? new File[0] : found;
        Arrays.sort(jams, Comparator.comparing(File::getName));
        if (jams.length == 0) {
            Toast.makeText(this, "????????", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[jams.length];
        for (int i = 0; i < jams.length; i++) {
            labels[i] = describeJam(jams[i]);
        }
        new AlertDialog.Builder(this)
                .setTitle("????")
                .setItems(labels, (dialog, which) -> confirmDelete(jams[which]))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDelete(File jam) {
        new AlertDialog.Builder(this)
                .setTitle("Delete")
                .setMessage("Delete " + describeJam(jam) + "?")
                .setPositiveButton("Delete", (dialog, which) -> {
                    int deleted = deleteGameFiles(jam);
                    Toast.makeText(this, "Deleted " + deleted + " files", Toast.LENGTH_SHORT).show();
                    refreshGames();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Removes the jam plus every sibling/cache file that shares its basename. */
    private int deleteGameFiles(File jam) {
        String base = stripExtension(jam.getName()).toLowerCase();
        int deleted = 0;
        File[] siblings = jam.getParentFile().listFiles();
        if (siblings != null) {
            for (File sibling : siblings) {
                String name = sibling.getName().toLowerCase();
                if (name.equals(base + ".jam") || name.equals(base + ".jar")
                        || name.equals(base + ".sp") || name.equals(base + ".dex")
                        || name.equals(base + ".sp.undo") || name.equals(base + ".sp.orig")
                        || name.equals(base + ".jar.dex.ok") || name.equals(base + ".jar.sjisfix.jar")) {
                    if (sibling.delete()) {
                        deleted++;
                    }
                }
            }
        }
        File dexCache = new File(getCodeCacheDir(), "jam-dex");
        deleted += deleteMatchingRecursive(dexCache, base);
        return deleted;
    }

    private static int deleteMatchingRecursive(File file, String baseLower) {
        if (!file.exists()) {
            return 0;
        }
        if (file.isDirectory()) {
            int deleted = 0;
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (child.getName().toLowerCase().startsWith(baseLower)) {
                        deleted += deleteRecursively(child);
                    } else {
                        deleted += deleteMatchingRecursive(child, baseLower);
                    }
                }
            }
            return deleted;
        }
        return file.getName().toLowerCase().startsWith(baseLower) && file.delete() ? 1 : 0;
    }

    private static int deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        return file.delete() ? 1 : 0;
    }

    private void pickImportFolder() {
        // Built-in folder picker first: DocumentsUI's "Use this folder" button
        // cannot be reached with a D-pad. The system picker is only a fallback
        // for devices where direct shared-storage access cannot be granted.
        runWithStorageAccess(false,
                () -> CustomFilePicker.pickFolder(this, "Select folder containing games",
                        dir -> startBatchImportFromFiles(dir)),
                this::launchSafImportPicker);
    }

    /** Import a game's external SD-card data (e.g. a "sdcarddata" folder) into its SD folder. */
    private void pickSdDataFolder(GameEntry game) {
        pickSdFolderCustom(game.jam);
    }

    private void pickSdFolderCustom(File jam) {
        // Built-in folder picker, same UI and D-pad support as the other flows.
        runWithStorageAccess(false,
                () -> pickSdFolderDirect(jam),
                () -> launchSafSdData(jam));
    }

    private void pickSdFolderDirect(File jam) {
        CustomFilePicker.pickFolder(this, "Select SD data folder", dir -> {
            final File jamFile = jam;
            new Thread(() -> {
                try {
                    String appName = readJamAppName(jamFile);
                    if (appName == null) {
                        appName = stripExtension(jamFile.getName());
                    }
                    File target = new File(getFilesDir(),
                            ".opendoja/storage/ext0/SD_BIND/" + sanitizeSdName(appName));
                    if (!target.exists() && !target.mkdirs()) {
                        throw new java.io.IOException("cannot create " + target);
                    }
                    File source = new File(dir, "sdcarddata");
                    if (!source.isDirectory()) {
                        source = dir;
                    }
                    int count = copyDirectoryTo(source, target);
                    final int copied = count;
                    writeExternalReport("pocketdoja-sd-import.txt",
                            "source=File\ndir=" + dir.getAbsolutePath()
                                    + "\nusedSource=" + source.getAbsolutePath()
                                    + "\ntarget=" + target.getAbsolutePath()
                                    + "\ncopied=" + count + "\n");
                    runOnUiThread(() -> {
                        Toast.makeText(this,
                                "Imported " + copied + " SD file(s) into " + target.getAbsolutePath(),
                                Toast.LENGTH_LONG).show();
                        if (copied == 0) {
                            Toast.makeText(this,
                                    "No files found in that folder (select the folder containing the .BIN files).",
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (Throwable throwable) {
                    runOnUiThread(() -> Toast.makeText(this,
                            "SD data import failed: " + throwable.getMessage(), Toast.LENGTH_LONG).show());
                }
            }).start();
        });
    }

    private void launchSafSdData(File jam) {
        pendingSdDataGame = jam;
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            startActivityForResult(intent, REQUEST_IMPORT_SD_DATA);
        } catch (Exception exception) {
            pendingSdDataGame = null;
            Toast.makeText(this, "No system folder picker available", Toast.LENGTH_LONG).show();
        }
    }

    private static int copyDirectoryTo(File sourceDir, File destDir) throws java.io.IOException {
        destDir.mkdirs();
        File[] children = sourceDir.listFiles();
        if (children == null) {
            return 0;
        }
        int copied = 0;
        for (File child : children) {
            File out = new File(destDir, child.getName());
            if (child.isDirectory()) {
                copied += copyDirectoryTo(child, out);
            } else {
                try (java.io.InputStream in = new java.io.FileInputStream(child);
                     java.io.OutputStream os = new java.io.FileOutputStream(out)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        os.write(buffer, 0, read);
                    }
                    copied++;
                }
            }
        }
        return copied;
    }

    private static String readJamAppName(File jam) {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(jam))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq > 0 && line.substring(0, eq).trim().equalsIgnoreCase("AppName")) {
                    String value = line.substring(eq + 1).trim();
                    return value.isEmpty() ? null : value;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String sanitizeSdName(String value) {
        StringBuilder builder = new StringBuilder();
        boolean replaced = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '-' || ch == '.') {
                builder.append(ch);
                replaced = false;
            } else if (!replaced) {
                builder.append('_');
                replaced = true;
            }
        }
        String sanitized = builder.toString();
        return sanitized.isEmpty() ? "app" : sanitized;
    }

    /** Finds a child directory named "sdcarddata" (so picking the game folder still works). */
    private String findSdcardDataChild(Uri treeUri, String parentDocId) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId);
        try (android.database.Cursor cursor = getContentResolver().query(children, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (cursor == null) {
                return null;
            }
            while (cursor.moveToNext()) {
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)
                        && name != null && name.equalsIgnoreCase("sdcarddata")) {
                    return cursor.getString(0);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static int countFiles(File dir) {
        File[] children = dir.listFiles();
        if (children == null) {
            return 0;
        }
        int count = 0;
        for (File child : children) {
            count += child.isDirectory() ? countFiles(child) : 1;
        }
        return count;
    }

    private static void appendFileNames(StringBuilder sb, File dir, int max) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        java.util.Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        int shown = 0;
        for (File child : children) {
            if (shown >= max) {
                sb.append("    ...\n");
                break;
            }
            sb.append("    ").append(child.isDirectory() ? child.getName() + "/" : child.getName()).append('\n');
            shown++;
        }
    }

    private int copyTreeToDir(Uri treeUri, String parentDocId, File destDir, int depth) throws Exception {
        if (depth > 8) {
            return 0;
        }
        destDir.mkdirs();
        int copied = 0;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId);
        try (android.database.Cursor cursor = getContentResolver().query(children, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (cursor == null) {
                return 0;
            }
            while (cursor.moveToNext()) {
                String docId = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                if (name == null || name.isEmpty()) {
                    continue;
                }
                File out = new File(destDir, name);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    copied += copyTreeToDir(treeUri, docId, out, depth + 1);
                } else {
                    Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                    try (java.io.InputStream in = getContentResolver().openInputStream(docUri);
                         java.io.OutputStream os = new java.io.FileOutputStream(out)) {
                        if (in != null) {
                            byte[] buffer = new byte[8192];
                            int read;
                            while ((read = in.read(buffer)) > 0) {
                                os.write(buffer, 0, read);
                            }
                            copied++;
                        }
                    }
                }
            }
        }
        return copied;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_IMPORT_SD_DATA) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null && pendingSdDataGame != null) {
                final Uri treeUri = data.getData();
                final File jam = pendingSdDataGame;
                try {
                    getContentResolver().takePersistableUriPermission(treeUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Throwable ignored) {
                }
                new Thread(() -> {
                    try {
                        String appName = readJamAppName(jam);
                        if (appName == null) {
                            appName = stripExtension(jam.getName());
                        }
                        File target = new File(getFilesDir(),
                                ".opendoja/storage/ext0/SD_BIND/" + sanitizeSdName(appName));
                        if (!target.exists() && !target.mkdirs()) {
                            throw new java.io.IOException("cannot create " + target);
                        }
                        String rootId = DocumentsContract.getTreeDocumentId(treeUri);
                        String sdChild = findSdcardDataChild(treeUri, rootId);
                        String sourceId = sdChild != null ? sdChild : rootId;
                        int count = copyTreeToDir(treeUri, sourceId, target, 0);
                        final int copied = count;
                        writeExternalReport("pocketdoja-sd-import.txt",
                                "source=SAF\ntreeDocId=" + rootId
                                        + "\nsdcardChild=" + sdChild
                                        + "\ntarget=" + target.getAbsolutePath()
                                        + "\ncopied=" + count + "\n");
                        runOnUiThread(() -> {
                            Toast.makeText(this,
                                    "Imported " + copied + " SD file(s) into " + target.getAbsolutePath(),
                                    Toast.LENGTH_LONG).show();
                            if (copied == 0) {
                                Toast.makeText(this,
                                        "No files found there. Pick the folder that contains the .BIN files.",
                                        Toast.LENGTH_LONG).show();
                                pickSdFolderCustom(jam);
                            }
                        });
                    } catch (Throwable throwable) {
                        writeExternalReport("pocketdoja-sd-import.txt",
                                "source=SAF\nerror=" + throwable + "\n");
                        runOnUiThread(() -> Toast.makeText(this,
                                "SD data import failed: " + throwable.getMessage(),
                                Toast.LENGTH_LONG).show());
                    }
                }).start();
            }
            pendingSdDataGame = null;
            return;
        }
        if (requestCode == REQUEST_CHANGE_ICON) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null && pendingIconGame != null) {
                try {
                    saveCustomIcon(pendingIconGame, data.getData());
                    refreshGames();
                } catch (Exception e) {
                    Toast.makeText(this, "Invalid image: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
            pendingIconGame = null;
            return;
        }
        if (requestCode == REQUEST_EXPORT_SAVE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null && pendingSaveGame != null) {
                final GameEntry game = pendingSaveGame;
                final Uri uri = data.getData();
                new Thread(() -> {
                    try {
                        exportSaveToUri(game, uri);
                        runOnUiThread(() -> Toast.makeText(this, "Save exported", Toast.LENGTH_SHORT).show());
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
                    }
                }).start();
            }
            pendingSaveGame = null;
            return;
        }
        if (requestCode == REQUEST_IMPORT_SAVE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null && pendingSaveGame != null) {
                final GameEntry game = pendingSaveGame;
                final Uri uri = data.getData();
                new Thread(() -> {
                    try {
                        importSaveFromUri(game, uri);
                        runOnUiThread(() -> Toast.makeText(this, "Save imported successfully", Toast.LENGTH_SHORT).show());
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(this, "Import failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
                    }
                }).start();
            }
            pendingSaveGame = null;
            return;
        }
        if (requestCode != REQUEST_IMPORT_FOLDER || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri treeUri = data.getData();
        if (treeUri == null) {
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        android.app.ProgressDialog scanning = new android.app.ProgressDialog(this);
        scanning.setMessage("Scanning for games...");
        scanning.setIndeterminate(true);
        scanning.show();
        new Thread(() -> {
            List<DiscoveredFolder> folders = scanJamFolders(treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri), 0, new int[]{0});
            runOnUiThread(() -> {
                scanning.dismiss();
                if (folders.isEmpty()) {
                    Toast.makeText(this, "No valid games found", Toast.LENGTH_LONG).show();
                    return;
                }
                new AlertDialog.Builder(this)
                        .setTitle("Import " + folders.size() + " game(s)?")
                        .setMessage("Found " + folders.size() + " game folder(s). Import them now?")
                        .setPositiveButton("Import", (dialog, which) ->
                                startBatchImport(treeUri, folders))
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        }).start();
        return;
    }

    private void startBatchImport(Uri treeUri, List<DiscoveredFolder> folders) {
        android.app.ProgressDialog progress = new android.app.ProgressDialog(this);
        progress.setMessage("Importing games...");
        progress.setIndeterminate(true);
        progress.show();
        new Thread(() -> {
            ImportResult total = new ImportResult();
            for (DiscoveredFolder folder : folders) {
                ImportResult r = importJamFolder(treeUri, folder.documentId);
                total.games += r.games;
                total.skipped += r.skipped;
                total.failed += r.failed;
                total.files += r.files;
                total.errors.addAll(r.errors);
            }
            runOnUiThread(() -> {
                progress.dismiss();
                StringBuilder msg = new StringBuilder();
                msg.append("Imported ").append(total.games).append(" game(s)");
                if (total.skipped > 0) {
                    msg.append(", skipped ").append(total.skipped).append(" duplicate(s)");
                }
                if (total.failed > 0) {
                    msg.append(", failed ").append(total.failed);
                }
                if (!total.errors.isEmpty()) {
                    msg.append("\n").append(String.join("\n", total.errors));
                }
                Toast.makeText(this, msg.toString(),
                        total.errors.isEmpty() ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                refreshGames();
            });
        }).start();
    }

    private List<DiscoveredFolder> scanJamFolders(Uri treeUri, String parentDocId, int depth, int[] counter) {
        List<DiscoveredFolder> result = new ArrayList<>();
        if (depth > 8 || counter[0] > 2000 || parentDocId == null) {
            return result;
        }
        ContentResolver resolver = getContentResolver();
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId);
        List<String[]> dirs = new ArrayList<>();
        boolean hasJam = false;
        String firstJamDocId = null;
        try (Cursor cursor = resolver.query(childrenUri, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
        }, null, null, null)) {
            if (cursor == null) {
                return result;
            }
            while (cursor.moveToNext()) {
                counter[0]++;
                if (counter[0] > 2000) {
                    break;
                }
                String docId = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                if (docId == null || name == null || mime == null) {
                    continue;
                }
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    dirs.add(new String[]{docId, name});
                } else if (name.toLowerCase().endsWith(".jam")) {
                    hasJam = true;
                    if (firstJamDocId == null) {
                        firstJamDocId = docId;
                    }
                }
            }
        } catch (Exception exception) {
            opendoja.host.OpenDoJaLog.warn(HomeActivity.class, "scan failed", exception);
            return result;
        }
        if (hasJam) {
            String displayName = firstJamDocId != null ? readJamAppName(treeUri, firstJamDocId) : null;
            if (displayName == null || displayName.isEmpty()) {
                displayName = "Game folder";
            }
            result.add(new DiscoveredFolder(parentDocId, displayName));
        }
        for (String[] dir : dirs) {
            result.addAll(scanJamFolders(treeUri, dir[0], depth + 1, counter));
        }
        return result;
    }

    private String readJamAppName(Uri treeUri, String jamDocId) {
        Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, jamDocId);
        try (InputStream in = getContentResolver().openInputStream(documentUri)) {
            if (in == null) {
                return null;
            }
            Properties properties = new Properties();
            properties.load(in);
            String appName = properties.getProperty("AppName");
            if (appName != null && !appName.isBlank()) {
                return appName.trim();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Imports games from the picked folder. A game is only imported when its
     * .jam, .jar and (if the jam declares SPsize) .sp files all live in that same
     * folder - i-mode titles load their assets from the scratchpad .sp file, so an
     * incomplete folder would install a broken game. Imported files are renamed to
     * a canonical shared basename so the runtime can resolve jar/sp next to the jam.
     */
    private ImportResult importJamFolder(Uri treeUri, String targetDocumentId) {
        ImportResult result = new ImportResult();
        ContentResolver resolver = getContentResolver();
        String treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
        String targetId = (targetDocumentId == null || targetDocumentId.isEmpty())
                ? treeDocumentId : targetDocumentId;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, targetId);

        Map<String, Child> childDocs = new LinkedHashMap<>();
        try (Cursor cursor = resolver.query(children, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
        }, null, null, null)) {
            if (cursor == null) {
                result.errors.add("?????????");
                return result;
            }
            while (cursor.moveToNext()) {
                String documentId = cursor.getString(0);
                String displayName = cursor.getString(1);
                String mimeType = cursor.getString(2);
                if (displayName == null || mimeType == null
                        || DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                    continue;
                }
                childDocs.put(displayName.toLowerCase(), new Child(documentId, displayName));
            }
        } catch (RuntimeException exception) {
            opendoja.host.OpenDoJaLog.warn(HomeActivity.class, "folder listing failed", exception);
            result.errors.add("?????????: " + exception.getMessage());
            return result;
        }

        List<String> jamNames = namesEndingIn(childDocs, ".jam");
        if (jamNames.isEmpty()) {
            result.errors.add("?????????.jam ??");
            return result;
        }
        List<String> jarNames = namesEndingIn(childDocs, ".jar");

        // destName(lower) -> source child; canonical names keep jam/jar/sp resolvable.
        Map<String, Child> toCopy = new LinkedHashMap<>();
        for (String jamName : jamNames) {
            String base = stripExtension(jamName);
            // Duplicate detection: skip if a game with the same jam name is already imported.
            File existingJam = new File(JamAssets.gamesDir(this), jamName.toLowerCase(java.util.Locale.ROOT));
            if (existingJam.exists()) {
                result.skipped++;
                result.errors.add(jamName + ": already imported, skipped");
                continue;
            }
            List<String> reasons = new ArrayList<>();

            String jarName = findChild(childDocs, base + ".jar");
            if (jarName == null) {
                if (jarNames.isEmpty()) {
                    reasons.add("?? .jar");
                } else if (jarNames.size() == 1) {
                    jarName = jarNames.get(0);
                } else {
                    reasons.add("???? .jam ????.jar");
                }
            }

            // Also check sp/ subfolder for .sp files
            String spFolderId = null;
            for (Map.Entry<String, Child> e : childDocs.entrySet()) {
                if (e.getKey().equalsIgnoreCase("sp")) {
                    spFolderId = e.getValue().documentId;
                    break;
                }
            }
            if (spFolderId != null) {
                Uri spChildren = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, spFolderId);
                try (Cursor spCursor = resolver.query(spChildren, new String[]{
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                }, null, null, null)) {
                    if (spCursor != null) {
                        while (spCursor.moveToNext()) {
                            String spDocId = spCursor.getString(0);
                            String spName = spCursor.getString(1);
                            if (spName != null && spName.toLowerCase().endsWith(".sp")) {
                                childDocs.put(("sp/" + spName).toLowerCase(), new Child(spDocId, spName));
                            }
                        }
                    }
                }
            }
            String spName = findChild(childDocs, base + ".sp");
            if (spName == null) {
                spName = findChild(childDocs, "sp/" + base + ".sp");
            }
            if (spName == null && jamDeclaresScratchpad(treeUri, childDocs.get(jamName.toLowerCase()))) {
                reasons.add("Invalid .sp next to jam or missing SPsize");
            }

            if (!reasons.isEmpty()) {
                result.failed++;
                result.errors.add(jamName + ": " + String.join(", ", reasons));
                continue;
            }

            toCopy.put(jamName.toLowerCase(), childDocs.get(jamName.toLowerCase()));
            toCopy.put((base + ".jar").toLowerCase(), childDocs.get(jarName.toLowerCase()));
            if (spName != null) {
                toCopy.put((base + ".sp").toLowerCase(), childDocs.get(spName.toLowerCase()));
            }
            result.games++;
        }

        if (toCopy.isEmpty()) {
            return result;
        }

        File gamesDir = JamAssets.gamesDir(this);
        for (Map.Entry<String, Child> entry : toCopy.entrySet()) {
            String destName = entry.getKey();
            Child source = entry.getValue();
            Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, source.documentId);
            try (InputStream in = resolver.openInputStream(documentUri);
                 OutputStream out = new FileOutputStream(new File(gamesDir, destName))) {
                if (in == null) {
                    result.errors.add("???? " + source.displayName);
                    continue;
                }
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
                result.files++;
            } catch (IOException | SecurityException exception) {
                opendoja.host.OpenDoJaLog.warn(HomeActivity.class, "cannot import " + source.displayName, exception);
                result.failed++;
                result.errors.add("????: " + source.displayName);
                continue;
            }
        }
        // Keep a pristine copy of each imported scratchpad so a later corrupt or
        // network-gated .sp can be restored without re-importing the game.
        for (String destName : toCopy.keySet()) {
            if (!destName.endsWith(".sp")) {
                continue;
            }
            File live = new File(gamesDir, destName);
            File snapshot = new File(gamesDir, destName + ".orig");
            if (live.exists() && !snapshot.exists()) {
                try {
                    java.nio.file.Files.copy(live.toPath(), snapshot.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                }
            }
        }
        return result;
    }

    private boolean jamDeclaresScratchpad(Uri treeUri, Child jamChild) {
        if (jamChild == null) {
            return true;
        }
        Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, jamChild.documentId);
        try (InputStream in = getContentResolver().openInputStream(documentUri)) {
            if (in == null) {
                return true;
            }
            Properties properties = new Properties();
            properties.load(in);
            String spSize = properties.getProperty("SPsize");
            return spSize != null && !spSize.isBlank();
        } catch (IOException | RuntimeException exception) {
            opendoja.host.OpenDoJaLog.warn(HomeActivity.class, "cannot read jam properties of " + jamChild.displayName, exception);
            // Be strict: when in doubt the game needs its .sp next to the .jam.
            return true;
        }
    }

    private static File siblingFile(File jam, String extension) {
        String name = stripExtension(jam.getName());
        return new File(jam.getParentFile(), name + extension);
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static List<String> namesEndingIn(Map<String, Child> childDocs, String suffix) {
        List<String> names = new ArrayList<>();
        for (Child child : childDocs.values()) {
            if (child.displayName.toLowerCase().endsWith(suffix)) {
                names.add(child.displayName);
            }
        }
        return names;
    }

    private static String findChild(Map<String, Child> childDocs, String lowerName) {
        Child child = childDocs.get(lowerName.toLowerCase());
        return child == null ? null : child.displayName;
    }

    private static final class DiscoveredFolder {
        final String documentId;
        final String displayName;

        DiscoveredFolder(String documentId, String displayName) {
            this.documentId = documentId;
            this.displayName = displayName;
        }
    }

    private static final class GameEntry {
        final File jam;
        final String displayName;
        final Bitmap icon;

        GameEntry(File jam, String displayName, Bitmap icon) {
            this.jam = jam;
            this.displayName = displayName;
            this.icon = icon;
        }
    }

    private static final class Child {
        final String documentId;
        final String displayName;

        Child(String documentId, String displayName) {
            this.documentId = documentId;
            this.displayName = displayName;
        }
    }

    // ------------------------------------------------------------------
    // Direct-filesystem picker plus a storage-access gate shared by every
    // entry point: Import .JAM, Export Save, Import Save, Change Icon,
    // Import SD File, and the custom BGM picker in ThemeActivity.
    // ------------------------------------------------------------------

    private boolean hasDirectStorageAccess(boolean needWrite) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return android.os.Environment.isExternalStorageManager();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            if (needWrite && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /**
     * Runs a direct-filesystem picker when shared storage is available.
     * Otherwise asks for permission, or offers the system picker (SAF) as a
     * fallback for devices that still have DocumentsUI.
     */
    private void runWithStorageAccess(boolean needWrite, Runnable directAction, Runnable safAction) {
        if (hasDirectStorageAccess(needWrite)) {
            clearPendingStorageAction();
            directAction.run();
            return;
        }
        pendingStorageAction = directAction;
        pendingSafAction = safAction;
        pendingStorageWrite = needWrite;
        waitingForStorageSettings = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            showStorageAccessChoice();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (needWrite) {
                requestPermissions(new String[]{
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        REQUEST_STORAGE_PERMISSION);
            } else {
                requestPermissions(new String[]{
                        android.Manifest.permission.READ_EXTERNAL_STORAGE},
                        REQUEST_STORAGE_PERMISSION);
            }
        }
    }

    /**
     * Android 11+ hides the shared filesystem behind "All files access". Some
     * devices never expose that switch (or an OEM ROM refuses to grant it), so
     * offer the system picker as a permission-free fallback instead of
     * dead-ending on the Settings screen. That fallback needs DocumentsUI,
     * which is exactly what this app avoids on devices that lack it; those
     * users should choose "Grant access".
     */
    private void showStorageAccessChoice() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Storage access")
                .setMessage("The built-in file browser needs 'All files access' to browse shared storage. "
                        + "Grant it in Settings, or use the system file picker instead (if available).")
                .setPositiveButton("Grant access", (d, which) -> {
                    if (!openAllFilesAccessSettings()) {
                        Toast.makeText(this, "All files access is not available; opening the system picker",
                                Toast.LENGTH_LONG).show();
                        runPendingSafAction();
                    }
                })
                .setNeutralButton("System picker", (d, which) -> runPendingSafAction())
                .setNegativeButton("Cancel", (d, which) -> clearPendingStorageAction())
                .create();
        dialog.setOnCancelListener(d -> clearPendingStorageAction());
        dialog.show();
    }

    private void showStoragePermissionDenied() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Storage access")
                .setMessage("Storage permission was denied. Allow it in the app settings to use the built-in "
                        + "browser, or use the system file picker instead (if available).")
                .setPositiveButton("App settings", (d, which) -> openAppDetailsSettings())
                .setNeutralButton("System picker", (d, which) -> runPendingSafAction())
                .setNegativeButton("Cancel", (d, which) -> clearPendingStorageAction())
                .create();
        dialog.setOnCancelListener(d -> clearPendingStorageAction());
        dialog.show();
    }

    private boolean openAllFilesAccessSettings() {
        waitingForStorageSettings = true;
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            return true;
        } catch (Exception first) {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                return true;
            } catch (Exception ignored) {
                waitingForStorageSettings = false;
                return false;
            }
        }
    }

    private void openAppDetailsSettings() {
        waitingForStorageSettings = true;
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception exception) {
            waitingForStorageSettings = false;
            Toast.makeText(this, "App settings are not available", Toast.LENGTH_LONG).show();
        }
    }

    private void runPendingSafAction() {
        Runnable safAction = pendingSafAction;
        clearPendingStorageAction();
        if (safAction != null) {
            safAction.run();
        }
    }

    private void clearPendingStorageAction() {
        pendingStorageAction = null;
        pendingSafAction = null;
        pendingStorageWrite = false;
        waitingForStorageSettings = false;
    }

    /** System folder picker (SAF): imports through content URIs and needs no storage permission. */
    private void launchSafImportPicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            startActivityForResult(intent, REQUEST_IMPORT_FOLDER);
        } catch (Exception exception) {
            Toast.makeText(this, "No system file picker available", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_STORAGE_PERMISSION || pendingStorageAction == null) {
            return;
        }
        if (hasDirectStorageAccess(pendingStorageWrite)) {
            Runnable action = pendingStorageAction;
            clearPendingStorageAction();
            action.run();
            return;
        }
        // Keep the pending action: the user can grant access in Settings and
        // come back, or use the system picker fallback.
        showStoragePermissionDenied();
    }

    private void pickImageFileCustom(GameEntry game) {
        runWithStorageAccess(false,
                () -> pickImageFileDirect(game),
                () -> launchSafChangeIcon(game));
    }

    private void pickImageFileDirect(GameEntry game) {
        pendingIconGame = game;
        // Built-in picker filtered to image files; works with or without
        // DocumentsUI and is fully D-pad navigable.
        CustomFilePicker.pickFile(this, "Select icon image",
                new String[]{".png", ".jpg", ".jpeg", ".webp", ".ico", ".bmp", ".gif"},
                file -> {
                    GameEntry target = pendingIconGame;
                    pendingIconGame = null;
                    if (target == null) {
                        return;
                    }
                    try {
                        saveCustomIconFromFile(target, file);
                        refreshGames();
                        Toast.makeText(this, "Icon updated", Toast.LENGTH_SHORT).show();
                    } catch (Exception exception) {
                        Toast.makeText(this, "Invalid image: " + exception.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void launchSafChangeIcon(GameEntry game) {
        pendingIconGame = game;
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            startActivityForResult(intent, REQUEST_CHANGE_ICON);
        } catch (Exception exception) {
            pendingIconGame = null;
            Toast.makeText(this, "No system file picker available", Toast.LENGTH_LONG).show();
        }
    }

    private void startBatchImportFromFiles(File root) {
        android.app.ProgressDialog scanning = new android.app.ProgressDialog(this);
        scanning.setMessage("Scanning for games...");
        scanning.setIndeterminate(true);
        scanning.show();
        new Thread(() -> {
            List<File> folders = scanJamFoldersFiles(root);
            runOnUiThread(() -> {
                scanning.dismiss();
                if (folders.isEmpty()) {
                    Toast.makeText(this, "No valid games found", Toast.LENGTH_LONG).show();
                    return;
                }
                new AlertDialog.Builder(this)
                        .setTitle("Import " + folders.size() + " game(s)?")
                        .setMessage("Found " + folders.size() + " game folder(s). Import them now?")
                        .setPositiveButton("Import", (dialog, which) -> {
                            android.app.ProgressDialog progress = new android.app.ProgressDialog(this);
                            progress.setMessage("Importing games...");
                            progress.setIndeterminate(true);
                            progress.show();
                            new Thread(() -> {
                                ImportResult total = new ImportResult();
                                for (File folder : folders) {
                                    ImportResult r = importJamFolderFromFile(folder);
                                    total.games += r.games;
                                    total.skipped += r.skipped;
                                    total.failed += r.failed;
                                    total.files += r.files;
                                    total.errors.addAll(r.errors);
                                }
                                runOnUiThread(() -> {
                                    progress.dismiss();
                                    StringBuilder msg = new StringBuilder();
                                    msg.append("Imported ").append(total.games).append(" game(s)");
                                    if (total.skipped > 0) msg.append(", skipped ").append(total.skipped).append(" duplicate(s)");
                                    if (total.failed > 0) msg.append(", failed ").append(total.failed);
                                    if (!total.errors.isEmpty()) msg.append("\n").append(String.join("\n", total.errors));
                                    Toast.makeText(this, msg.toString(),
                                            total.errors.isEmpty() ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                                    refreshGames();
                                });
                            }).start();
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        }).start();
    }

    private List<File> scanJamFoldersFiles(File root) {
        List<File> result = new ArrayList<>();
        int[] counter = new int[]{0};
        scanJamFoldersFiles(root, 0, counter, result);
        return result;
    }

    private void scanJamFoldersFiles(File dir, int depth, int[] counter, List<File> result) {
        if (dir == null || !dir.isDirectory() || depth > 8 || counter[0] > 2000) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        boolean hasJam = false;
        List<File> subdirs = new ArrayList<>();
        for (File c : children) {
            counter[0]++;
            if (counter[0] > 2000) break;
            if (c.isDirectory()) subdirs.add(c);
            else if (c.isFile() && c.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".jam")) hasJam = true;
        }
        if (hasJam) result.add(dir);
        for (File d : subdirs) scanJamFoldersFiles(d, depth + 1, counter, result);
    }

    private ImportResult importJamFolderFromFile(File folder) {
        ImportResult result = new ImportResult();
        File[] children = folder.listFiles();
        if (children == null) {
            result.errors.add("cannot list folder");
            return result;
        }
        Map<String, File> childFiles = new LinkedHashMap<>();
        File spDir = null;
        List<String> jamNames = new ArrayList<>();
        List<String> jarNames = new ArrayList<>();
        for (File c : children) {
            if (c.isDirectory()) {
                if (c.getName().equalsIgnoreCase("sp")) spDir = c;
            } else {
                childFiles.put(c.getName().toLowerCase(java.util.Locale.ROOT), c);
                String lower = c.getName().toLowerCase(java.util.Locale.ROOT);
                if (lower.endsWith(".jam")) jamNames.add(c.getName());
                if (lower.endsWith(".jar")) jarNames.add(c.getName());
            }
        }
        if (jamNames.isEmpty()) {
            result.errors.add("no .jam in folder");
            return result;
        }

        Map<String, File> toCopy = new LinkedHashMap<>();
        for (String jamName : jamNames) {
            String base = stripExtension(jamName);
            File existingJam = new File(JamAssets.gamesDir(this), jamName.toLowerCase(java.util.Locale.ROOT));
            if (existingJam.exists()) {
                result.skipped++;
                result.errors.add(jamName + ": already imported, skipped");
                continue;
            }
            List<String> reasons = new ArrayList<>();
            String jarName = null;
            File jarFile = childFiles.get((base + ".jar").toLowerCase(java.util.Locale.ROOT));
            if (jarFile != null) {
                jarName = jarFile.getName();
            } else if (jarNames.isEmpty()) {
                reasons.add("missing .jar");
            } else if (jarNames.size() == 1) {
                jarName = jarNames.get(0);
            } else {
                reasons.add("multiple .jar, cannot match");
            }

            File spSrc = childFiles.get((base + ".sp").toLowerCase(java.util.Locale.ROOT));
            if (spSrc == null && spDir != null) {
                File[] spEntries = spDir.listFiles();
                if (spEntries != null) {
                    for (File spCandidate : spEntries) {
                        if (spCandidate.isFile() && spCandidate.getName().equalsIgnoreCase(base + ".sp")) {
                            spSrc = spCandidate;
                            break;
                        }
                    }
                }
                if (spSrc == null) {
                    File cand = new File(spDir, base + ".sp");
                    if (cand.isFile()) spSrc = cand;
                }
            }
            boolean spDeclared = jamDeclaresScratchpadFromFile(new File(folder, jamName));
            if (spDeclared && spSrc == null) {
                reasons.add("missing .sp (SPsize declared)");
            }

            if (!reasons.isEmpty()) {
                result.failed++;
                result.errors.add(jamName + ": " + String.join(", ", reasons));
                continue;
            }

            toCopy.put(jamName.toLowerCase(java.util.Locale.ROOT), childFiles.get(jamName.toLowerCase(java.util.Locale.ROOT)));
            if (jarName != null) {
                File jf = childFiles.get(jarName.toLowerCase(java.util.Locale.ROOT));
                if (jf != null) toCopy.put((base + ".jar").toLowerCase(java.util.Locale.ROOT), jf);
            }
            if (spSrc != null) {
                toCopy.put((base + ".sp").toLowerCase(java.util.Locale.ROOT), spSrc);
            }
            result.games++;
        }

        if (toCopy.isEmpty()) return result;

        File gamesDir = JamAssets.gamesDir(this);
        for (Map.Entry<String, File> entry : toCopy.entrySet()) {
            String destName = entry.getKey();
            File src = entry.getValue();
            try (java.io.InputStream in = new java.io.FileInputStream(src);
                 java.io.OutputStream out = new java.io.FileOutputStream(new File(gamesDir, destName))) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
                result.files++;
            } catch (java.io.IOException | SecurityException exception) {
                opendoja.host.OpenDoJaLog.warn(HomeActivity.class, "cannot import " + src.getName(), exception);
                result.failed++;
                result.errors.add("import failed: " + src.getName());
            }
        }
        return result;
    }

    private boolean jamDeclaresScratchpadFromFile(File jamFile) {
        try (java.io.InputStream in = new java.io.FileInputStream(jamFile)) {
            Properties p = new Properties();
            p.load(in);
            String spSize = p.getProperty("SPsize");
            return spSize != null && !spSize.isBlank();
        } catch (Exception e) {
            return true;
        }
    }

    // Handle pending custom import after returning from settings (Android 11+).
    // (onResume hook added separately.)

    private static final class ImportResult {
        int games;
        int files;
        int skipped;
        int failed;
        final List<String> errors = new ArrayList<>();
    }
}
