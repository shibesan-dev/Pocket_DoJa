package opendoja.android;

import android.content.SharedPreferences;
import android.app.Activity;
import android.app.AlertDialog;
import android.opengl.GLSurfaceView;
import android.view.Gravity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
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
    private static final int REQUEST_CHANGE_ICON = 2;
    private static final int REQUEST_IMPORT_FOLDER = 1;
    private GameEntry pendingIconGame;
    private static final String[] MENU_ITEMS = {"Import .JAM", "Key Mapping", "Display", "Theme", "About", "Exit"};

    private final Map<String, Bitmap> iconCache = new ConcurrentHashMap<>();
    private ExecutorService worker = Executors.newFixedThreadPool(2);
    private LinearLayout topBar;
    private EditText searchBox;
    private TextView titleView;
    private String searchQuery = "";
    private LinearLayout gridContainer;
    private TextView emptyView;
    private PopupWindow menuPopup;
    private int columns = 4;
    private int iconSizePx;

    private static HomeActivity instance;
    public static volatile boolean bgmSuppressed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;
        // Cancel any stale restart alarm from the previous process-restart implementation
        try {
            Intent restart = new Intent(this, HomeActivity.class);
            restart.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                    this, 0, restart, android.app.PendingIntent.FLAG_ONE_SHOT | android.app.PendingIntent.FLAG_IMMUTABLE);
            android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ACTIVITY_SERVICE);
            if (am != null) {
                am.cancel(pi);
            }
            pi.cancel();
        } catch (Exception ignored) {
        }
        android.util.Log.i("OpenDoJa", "HomeActivity.onCreate pid=" + android.os.Process.myPid() + " taskId=" + getTaskId());
        applyDisplayOrientation(this);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(themeSurfaceColor()));
        computeGridMetrics();

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
        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        SharedPreferences themePrefs = getSharedPreferences(ThemeActivity.PREFS_NAME, MODE_PRIVATE);
        String theme = themePrefs.getString(ThemeActivity.KEY_THEME, "flow");
        renderer.setTheme(theme);
        FrameLayout.LayoutParams glParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        root.addView(glView, glParams);

        this.glView = glView;
        this.themeRenderer = renderer;

        this.glView = glView;
        this.themeRenderer = renderer;

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(buildTitleBar(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        content.addView(buildDivider(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        int pad = dp(12);
        content.setPadding(pad, pad, pad, pad);

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
        content.addView(gridContainer, matchWrap());

        scroll.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // icon picker registered via startActivityForResult (see onActivityResult)
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyDisplayOrientation(this);
        refreshGames();
        if (glView != null) {
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
            glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
            SharedPreferences themePrefs = getSharedPreferences(ThemeActivity.PREFS_NAME, MODE_PRIVATE);
            String theme = themePrefs.getString(ThemeActivity.KEY_THEME, "flow");
            newRenderer.setTheme(theme);
            FrameLayout.LayoutParams glParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
            if (parent != null) {
                parent.addView(glView, 0, glParams);
            }
            this.glView = glView;
            this.themeRenderer = newRenderer;
            glView.onResume();
            glView.requestRender();
            android.util.Log.i("OpenDoJa", "HomeActivity.onResume: GLSurfaceView recreated");
        }
        if (topBar != null) {
            int color = themeSurfaceColor();
            topBar.setBackgroundColor(color);
            android.util.Log.i("PocketDoJa", "onResume: topBar color=" + Integer.toHexString(color) + " theme=" + getSharedPreferences(ThemeActivity.PREFS_NAME, MODE_PRIVATE).getString(ThemeActivity.KEY_THEME, "flow"));
        }
        if (!gameRunning) playBgm();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (glView != null) {
            glView.onPause();
            android.util.Log.i("OpenDoJa", "HomeActivity.onPause: glView.onPause() called");
        }
        stopBgm();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private LinearLayout buildTitleBar() {
        topBar = new LinearLayout(this);
        LinearLayout bar = topBar;
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(themeSurfaceColor());
        bar.setPadding(0, 0, dp(4), 0);

        titleView = new TextView(this);
        titleView.setText("Pocket DoJa");
        titleView.setTextColor(themeTextColor());
        titleView.setTextSize(24f);
        titleView.setTypeface(android.graphics.Typeface.createFromAsset(getAssets(), "fonts/PixelifySans-Bold.ttf"));
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setGravity(Gravity.CENTER_VERTICAL);
        titleView.setPadding(dp(16), 0, dp(8), 0);
        bar.addView(titleView, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        TextView searchButton = new TextView(this);
        searchButton.setText("\uD83D\uDD0D");
        searchButton.setTextSize(20f);
        searchButton.setTextColor(themeTextColor());
        searchButton.setGravity(Gravity.CENTER);
        searchButton.setPadding(dp(8), 0, dp(8), 0);
        searchButton.setClickable(true);
        searchButton.setFocusable(true);
        searchButton.setOnClickListener(v -> toggleSearch());

        searchBox = new EditText(this);
        searchBox.setHint("Search games");
        searchBox.setTextColor(themeTextColor());
        searchBox.setHintTextColor(0x80FFFFFF);
        searchBox.setSingleLine(true);
        searchBox.setVisibility(View.GONE);
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

        TextView viewToggleButton = new TextView(this);
        viewToggleButton.setText(isGridView() ? "\u25A6" : "\u2630");
        viewToggleButton.setTextSize(22f);
        viewToggleButton.setTextColor(themeTextColor());
        viewToggleButton.setGravity(Gravity.CENTER);
        viewToggleButton.setPadding(dp(16), 0, dp(8), 0);
        viewToggleButton.setClickable(true);
        viewToggleButton.setFocusable(true);
        viewToggleButton.setOnClickListener(v -> {
            setGridView(!isGridView());
            viewToggleButton.setText(isGridView() ? "\u25A6" : "\u2630");
            refreshGames();
        });
        bar.addView(viewToggleButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));

        TextView menuButton = new TextView(this);
        menuButton.setText("...");
        menuButton.setTextSize(26f);
        menuButton.setTextColor(themeTextColor());
        menuButton.setGravity(Gravity.CENTER);
        menuButton.setPadding(dp(16), 0, dp(16), 0);
        menuButton.setClickable(true);
        menuButton.setFocusable(true);
        menuButton.setBackground(new RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF), null, new ColorDrawable(0x1FFFFFFF)));
        menuButton.setOnClickListener(this::showOptionsMenu);
        bar.addView(menuButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));
        return bar;
    }

    private void toggleSearch() {
        if (searchBox == null) {
            return;
        }
        if (searchBox.getVisibility() == View.VISIBLE) {
            searchBox.setText("");
            searchBox.setVisibility(View.GONE);
            if (titleView != null) {
                titleView.setVisibility(View.VISIBLE);
            }
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(searchBox.getWindowToken(), 0);
            }
        } else {
            searchBox.setVisibility(View.VISIBLE);
            if (titleView != null) {
                titleView.setVisibility(View.GONE);
            }
            searchBox.requestFocus();
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(searchBox, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
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

        for (String label : MENU_ITEMS) {
            TextView item = new TextView(this);
            item.setText(label);
            item.setTextColor(0xFFE8E8E8);
            item.setTextSize(16f);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(18), dp(14), dp(18), dp(14));
            item.setClickable(true);
            item.setForeground(new RippleDrawable(
                    ColorStateList.valueOf(0x2FFFFFFF), null, null));
            item.setOnClickListener(v -> {
                if (menuPopup != null) {
                    menuPopup.dismiss();
                    menuPopup = null;
                }
                onMenuAction(label);
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
        menuPopup = popup;
    }

    private void onMenuAction(String label) {
        switch (label) {
            case "Import .JAM":
                pickImportFolder();
                break;
            case "Key Mapping":
                startActivity(new Intent(this, KeyMappingActivity.class));
                break;
            case "Display":
                startActivity(new Intent(this, DisplayActivity.class));
                break;
            case "Theme":
                startActivity(new Intent(this, ThemeActivity.class));
                break;
            case "About":
                showAboutDialog();
                break;
            case "Exit":
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
                android.util.Log.i("OpenDoJa", "Exit pressed: class=" + getClass().getName()
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
            default:
                break;
        }
    }

    static void applyDisplayOrientation(Activity activity) {
        applyDisplayOrientation(activity, null);
    }

    static void applyDisplayOrientation(Activity activity, String gameId) {
        SharedPreferences prefs = activity.getSharedPreferences(DisplayActivity.PREFS_NAME, MODE_PRIVATE);
        String orientation;
        if (gameId != null && !gameId.isEmpty()) {
            String override = prefs.getString(gameId + "_" + DisplayActivity.KEY_ORIENTATION, DisplayActivity.INHERIT);
            android.util.Log.i("OpenDoJa", "applyDisplayOrientation: gameId=" + gameId + " override=" + override);
            if (!override.equals(DisplayActivity.INHERIT)) {
                orientation = override;
            } else {
                orientation = prefs.getString(DisplayActivity.KEY_ORIENTATION, DisplayActivity.ORIENTATION_PORTRAIT);
            }
        } else {
            orientation = prefs.getString(DisplayActivity.KEY_ORIENTATION, DisplayActivity.ORIENTATION_PORTRAIT);
        }
        android.util.Log.i("OpenDoJa", "applyDisplayOrientation: effective=" + orientation);
        int requested = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        if (DisplayActivity.ORIENTATION_LANDSCAPE.equals(orientation)) {
            requested = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        } else if (DisplayActivity.ORIENTATION_PORTRAIT_REV.equals(orientation)) {
            requested = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
        } else if (DisplayActivity.ORIENTATION_LANDSCAPE_REV.equals(orientation)) {
            requested = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
        }
android.util.Log.i("OpenDoJa", "applyDisplayOrientation: requested=" + requested);
        activity.setRequestedOrientation(requested);
    }
    private void showAboutDialog() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(16));

        android.graphics.Typeface regular = android.graphics.Typeface.createFromAsset(getAssets(), "fonts/PixelifySans-Regular.ttf");

        TextView title = new TextView(this);
        title.setText("About Pocket DoJa v1.0");
        title.setTypeface(regular);
        title.setTextSize(18f);
        title.setTextColor(0xFFE0E0E0);
        content.addView(title);

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
    private static int bgmPosition = 0;
    private static boolean bgmPaused = false;
    private static String bgmCurrentPath = "";
    private static boolean bgmEnabled = true;
    public static volatile boolean gameRunning = false;

    private GLSurfaceView glView;
    private ThemeRenderer themeRenderer;

    private void playBgm() {
        if (gameRunning || bgmSuppressed) {
            android.util.Log.i("PocketDoJa", "playBgm: gameRunning, skip");
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
                android.util.Log.i("PocketDoJa", "playBgm: resumed from " + bgmPosition);
            } catch (Exception e) {
                releaseBgm();
                createBgmPlayer(path);
                bgmCurrentPath = path;
            }
            return;
        }
        createBgmPlayer(path);
        bgmCurrentPath = path;
        android.util.Log.i("PocketDoJa", "playBgm: created new player");
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
        if (bgmPlayer != null) {
            try {
                if (bgmPlayer.isPlaying()) {
                    bgmPosition = bgmPlayer.getCurrentPosition();
                    bgmPlayer.pause();
                    bgmPaused = true;
                    android.util.Log.i("PocketDoJa", "stopBgm: paused at " + bgmPosition);
                }
            } catch (Exception ignored) {
            }
        }
    }

    public static void pauseBgmStatic() {
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
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float xdpi = dm.xdpi > 1f ? dm.xdpi : dm.densityDpi;
        columns = 5;
        float usable = dm.widthPixels - 24f * dm.density;
        int cellPx = (int) (usable / columns);
        int icon = Math.min(Math.round(15f * xdpi / 25.4f), (int) (cellPx * 0.78f));
        iconSizePx = Math.max(icon, dp(48));
    }

    private void refreshGames() {
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
                Properties properties = JamMetadataResolver.loadJamProperties(jam.toPath());
                String appName = properties.getProperty("AppName");
                if (appName != null && !appName.isBlank()) {
                    name = appName.trim();
                }
            } catch (IOException | RuntimeException exception) {
                Log.w(TAG, "cannot read jam metadata: " + jam.getName(), exception);
            }
            File nameFile = new File(jam.getParentFile(), stripExtension(jam.getName()) + ".name.txt");
            if (nameFile.exists()) {
                try {
                    String custom = new String(java.nio.file.Files.readAllBytes(nameFile.toPath())).trim();
                    if (!custom.isEmpty()) {
                        name = custom;
                    }
                } catch (IOException ignored) {
                }
            }
            games.add(new GameEntry(jam, name, loadGameIcon(jam, name)));
        }
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
        games.sort(Comparator.comparingLong((GameEntry e) -> e.jam.lastModified()).reversed());
        return games;
    }

    private Bitmap loadGameIcon(File jam, String displayName) {
        File customIcon = new File(jam.getParentFile(), stripExtension(jam.getName()) + ".icon.png");
        if (customIcon.exists()) {
            Bitmap bmp = BitmapFactory.decodeFile(customIcon.getAbsolutePath());
            if (bmp != null) {
                return bmp;
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
                Properties properties = JamMetadataResolver.loadJamProperties(jam.toPath());
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
                Log.w(TAG, "cannot read app icon: " + jam.getName(), exception);
            }
        }
        if (icon == null) {
            icon = placeholderIcon(displayName);
        }
        iconCache.put(key, icon);
        return icon;
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
            Log.w(TAG, "cannot decode " + entryName + " from " + jar.getName(), exception);
            return null;
        }
    }

    private void saveCustomIcon(GameEntry game, Uri uri) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Cannot open selected file");
            byte[] data = readAllBytes(in);
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

    private Bitmap placeholderIcon(String displayName) {
        int size = iconSizePx;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        float[] hsv = new float[3];
        hsv[0] = Math.floorMod(displayName.hashCode(), 360);
        hsv[1] = 0.45f;
        hsv[2] = 0.62f;
        Paint tilePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tilePaint.setColor(Color.HSVToColor(hsv));
        float radius = size / 6f;
        canvas.drawRoundRect(0f, 0f, size - 1f, size - 1f, radius, radius, tilePaint);

        String initial = displayName.isEmpty()
                ? "?"
                : new StringBuilder().appendCodePoint(displayName.codePointAt(0)).toString();
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(size * 0.46f);
        textPaint.setFakeBoldText(true);
        textPaint.setTextAlign(Paint.Align.CENTER);
        float baseline = size / 2f - (textPaint.descent() + textPaint.ascent()) / 2f;
        canvas.drawText(initial, size / 2f, baseline, textPaint);
        return bitmap;
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
        gridContainer.removeAllViews();
        if (games.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            gridContainer.setVisibility(View.GONE);
            return;
        }
        emptyView.setVisibility(View.GONE);
        gridContainer.setVisibility(View.VISIBLE);

        if (isGridView()) {
            int marginPx = dp(5);
            int contentWidth = getResources().getDisplayMetrics().widthPixels - dp(48);
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
                LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(cellSize, (int)(cellSize * 1.15f));
                cellParams.setMargins(marginPx, marginPx, marginPx, marginPx);
                LinearLayout cell = createGameCell(games.get(i));
                cell.setLayoutParams(cellParams);
                row.addView(cell);
            }
        } else {
            for (int i = 0; i < games.size(); i++) {
                LinearLayout row = createGameListRow(games.get(i), i + 1);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, 0, 0, dp(8));
                row.setLayoutParams(lp);
                gridContainer.addView(row);
            }
        }
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
        name.setTypeface(android.graphics.Typeface.createFromAsset(getAssets(), "fonts/PixelifySans-Regular.ttf"));
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

        row.setOnClickListener(v -> launchJam(game.jam));
        row.setOnLongClickListener(v -> {
            showGameOptions(game);
            return true;
        });
        return row;
    }

    private void showRenameDialog(GameEntry game) {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setText(game.displayName);
        new AlertDialog.Builder(this)
                .setTitle("Rename")
                .setView(input)
                .setPositiveButton("OK", (dialog, which) -> {
                    String newName = input.getText().toString().trim();
                    if (newName.isEmpty()) return;
                    File nameFile = new File(game.jam.getParentFile(), stripExtension(game.jam.getName()) + ".name.txt");
                    try (FileOutputStream fos = new FileOutputStream(nameFile)) {
                        fos.write(newName.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    } catch (IOException ignored) {
                    }
                    refreshGames();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
        String[] options = {"Change Icon", "Reset Icon", "Rename", "Display", "Remove From Library"};
    private void showGameOptions(GameEntry game) {
        new AlertDialog.Builder(this)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        pendingIconGame = game;
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("*/*");
                        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/png", "image/jpeg", "image/x-icon", "image/vnd.microsoft.icon"});
                        startActivityForResult(intent, REQUEST_CHANGE_ICON);
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
    private LinearLayout createGameCell(GameEntry game) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(dp(6), dp(10), dp(6), dp(10));
        cell.setClickable(true);
        cell.setFocusable(true);

        GradientDrawable tile = new GradientDrawable();
        tile.setColor(themeSurfaceColor());
        tile.setCornerRadius(dp(10));
        tile.setStroke(dp(1), 0xFF2A2E36);
        cell.setBackground(tile);
        cell.setForeground(new RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF), null, tile));

        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setImageBitmap(game.icon);
        cell.addView(icon, new LinearLayout.LayoutParams(iconSizePx, iconSizePx));

        TextView name = new TextView(this);
        name.setText(game.displayName);
        name.setTextColor(themeTextColor());
        name.setTypeface(android.graphics.Typeface.createFromAsset(getAssets(), "fonts/PixelifySans-Regular.ttf"));
        name.setTextSize(13f);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(1);
        name.setSingleLine(true);
        name.setHorizontallyScrolling(true);
        name.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        name.setMarqueeRepeatLimit(-1);
        name.setSelected(true);
        LinearLayout.LayoutParams nameParams = matchWrap();
        nameParams.topMargin = dp(6);
        cell.addView(name, nameParams);

        cell.setOnClickListener(v -> launchJam(game.jam));
        cell.setOnLongClickListener(v -> {
            showGameOptions(game);
            return true;
        });
        LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(dp(iconSizePx + 40), dp(iconSizePx + 50));
        cellLp.setMargins(dp(5), dp(5), dp(5), dp(5));
        cell.setLayoutParams(cellLp);
        return cell;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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
            Properties properties = JamMetadataResolver.loadJamProperties(jam.toPath());
            String appName = properties.getProperty("AppName");
            if (appName != null && !appName.isBlank()) {
                return appName + " (" + name + ")";
            }
        } catch (IOException ignored) {
        }
        return name;
    }

    private void launchJam(File jam) {
        Intent intent = new Intent(this, EntryActivity.class);
        intent.putExtra("jamPath", jam.getAbsolutePath());
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
                        || name.equals(base + ".sp") || name.equals(base + ".dex")) {
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
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        try {
            startActivityForResult(intent, REQUEST_IMPORT_FOLDER);
        } catch (RuntimeException exception) {
            Toast.makeText(this, "Invalid image", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
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
            Log.w(TAG, "scan failed", exception);
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
            Log.w(TAG, "folder listing failed", exception);
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
            File existingJam = new File(JamAssets.gamesDir(this), jamName);
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
                Log.w(TAG, "cannot import " + source.displayName, exception);
                result.failed++;
                result.errors.add("????: " + source.displayName);
                continue;
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
            Log.w(TAG, "cannot read jam properties of " + jamChild.displayName, exception);
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

    private static final class ImportResult {
        int games;
        int files;
        int skipped;
        int failed;
        final List<String> errors = new ArrayList<>();
    }
}
