package opendoja.android;

import android.app.Activity;
import android.app.AlertDialog;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import android.net.Uri;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ThemeActivity extends Activity {
    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    static final String PREFS_NAME = "theme_bgm";
    static final String KEY_THEME = "theme";
    static final String KEY_BGM_ENABLED = "bgm_enabled";
    static final String KEY_BGM_PATH = "bgm_path";
    static final String KEY_BGM_NAME = "bgm_name";
    static final String KEY_SKIN = "skin";

    static final String[] THEMES = {"flow", "flow-dream", "flow-azure", "flow-green", "flow-midnight"};
    static final String[] THEME_LABELS = {"Flow", "Flow Dream", "Flow Azure", "Flow Green", "Flow Midnight"};

    static final String[] SKINS = {"silver", "blue", "red", "green", "orange"};
    static final String[] SKIN_LABELS = {"Silver (Metallic)", "Blue", "Red", "Green", "Orange"};

    private static final int REQUEST_PICK_BGM_SAF = 1;
    private static final int REQUEST_STORAGE_PERMISSION = 9001;
    private Runnable pendingStorageAction;
    private Runnable pendingSafAction;
    private boolean waitingForStorageSettings;

    private SharedPreferences prefs;
    private String pendingTheme;
    private boolean pendingBgmEnabled;
    private String pendingBgmPath;
    private String pendingBgmName;
    private String pendingSkin;
    private TextView bgmLabel;
    private TextView defaultBgmRow;
    private TextView selectBgmButton;
    private TextView noBgmRow;
    private TextView firstThemeRow;
    private boolean initialFocusApplied;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        pendingTheme = prefs.getString(KEY_THEME, "flow");
        pendingBgmEnabled = prefs.getBoolean(KEY_BGM_ENABLED, true);
        pendingBgmPath = prefs.getString(KEY_BGM_PATH, "");
        pendingBgmName = prefs.getString(KEY_BGM_NAME, "");
        pendingSkin = prefs.getString(KEY_SKIN, "silver");

        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(R.string.theme_title);
        title.setTextSize(24f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title, matchWrap());

        TextView section1 = new TextView(this);
        section1.setText(R.string.theme);
        section1.setTextSize(18f);
        section1.setTypeface(Typeface.DEFAULT_BOLD);
        section1.setPadding(0, pad, 0, pad / 2);
        root.addView(section1, matchWrap());

        List<TextView> themeRows = new ArrayList<>();
        for (int i = 0; i < THEMES.length; i++) {
            final String theme = THEMES[i];
            TextView row = new TextView(this);
            row.setText(THEME_LABELS[i]);
            row.setTextSize(16f);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setOnClickListener(v -> {
                pendingTheme = theme;
                updateHighlights(themeRows, pendingTheme);
            });
            row.setTag(theme);
            addFocusHighlight(row);
            themeRows.add(row);
            root.addView(row, matchWrap());
        }
        updateHighlights(themeRows, pendingTheme);
        firstThemeRow = themeRows.get(0);

        TextView sectionSkin = new TextView(this);
        sectionSkin.setText("Skin");
        sectionSkin.setTextSize(18f);
        sectionSkin.setTypeface(Typeface.DEFAULT_BOLD);
        sectionSkin.setPadding(0, pad, 0, pad / 2);
        root.addView(sectionSkin, matchWrap());

        List<TextView> skinRows = new ArrayList<>();
        for (int i = 0; i < SKINS.length; i++) {
            final String skin = SKINS[i];
            TextView row = new TextView(this);
            row.setText(SKIN_LABELS[i]);
            row.setTextSize(16f);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setOnClickListener(v -> {
                pendingSkin = skin;
                updateHighlights(skinRows, pendingSkin);
            });
            row.setTag(skin);
            addFocusHighlight(row);
            skinRows.add(row);
            root.addView(row, matchWrap());
        }
        updateHighlights(skinRows, pendingSkin);

        TextView section2 = new TextView(this);
        section2.setText(R.string.bgm);
        section2.setTextSize(18f);
        section2.setTypeface(Typeface.DEFAULT_BOLD);
        section2.setPadding(0, pad, 0, pad / 2);
        root.addView(section2, matchWrap());

        bgmLabel = new TextView(this);
        bgmLabel.setText(pendingBgmName.isEmpty() ? getString(R.string.no_track_selected) : pendingBgmName);
        bgmLabel.setTextSize(14f);
        bgmLabel.setPadding(0, 0, 0, dp(8));
        root.addView(bgmLabel, matchWrap());

        defaultBgmRow = new TextView(this);
        defaultBgmRow.setText(R.string.default_builtin);
        defaultBgmRow.setTextSize(16f);
        defaultBgmRow.setPadding(dp(12), dp(10), dp(12), dp(10));
        defaultBgmRow.setOnClickListener(v -> {
            pendingBgmEnabled = true;
            pendingBgmPath = "";
            pendingBgmName = "";
            HomeActivity.stopBgmStatic();
            updateBgmSelection();
        });
        addFocusHighlight(defaultBgmRow);
        root.addView(defaultBgmRow, matchWrap());

        selectBgmButton = new TextView(this);
        selectBgmButton.setText(R.string.select_audio);
        selectBgmButton.setTextSize(16f);
        selectBgmButton.setPadding(dp(12), dp(10), dp(12), dp(10));
        selectBgmButton.setOnClickListener(v -> {
            // Always use the built-in picker so the file list is fully
            // D-pad navigable and does not depend on DocumentsUI.
            pickBgmCustom();
        });
        addFocusHighlight(selectBgmButton);
        root.addView(selectBgmButton, matchWrap());

        noBgmRow = new TextView(this);
        noBgmRow.setText(R.string.no_bgm);
        noBgmRow.setTextSize(16f);
        noBgmRow.setPadding(dp(12), dp(10), dp(12), dp(10));
        noBgmRow.setOnClickListener(v -> {
            pendingBgmEnabled = false;
            pendingBgmPath = "";
            pendingBgmName = "";
            HomeActivity.stopBgmStatic();
            updateBgmSelection();
        });
        addFocusHighlight(noBgmRow);
        root.addView(noBgmRow, matchWrap());
        updateBgmSelection();

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        TextView ok = new TextView(this);
        ok.setText(R.string.ok);
        ok.setTextSize(16f);
        ok.setPadding(dp(16), dp(10), dp(16), dp(10));
        ok.setOnClickListener(v -> commitAndFinish());
        addFocusHighlight(ok);
        buttons.addView(ok);
        TextView cancel = new TextView(this);
        cancel.setText(R.string.cancel);
        cancel.setTextSize(16f);
        cancel.setPadding(dp(16), dp(10), dp(16), dp(10));
        cancel.setOnClickListener(v -> finish());
        addFocusHighlight(cancel);
        buttons.addView(cancel);
        root.addView(buttons, matchWrap());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        // Let individual rows take D-pad focus instead of the ScrollView.
        scroll.setFocusable(false);
        scroll.addView(root);
        setContentView(scroll);
        scroll.post(() -> {
            if (firstThemeRow != null) {
                firstThemeRow.requestFocus();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingStorageAction != null) {
            if (hasDirectStorageAccess()) {
                Runnable action = pendingStorageAction;
                clearPendingStorageAction();
                action.run();
            } else if (waitingForStorageSettings) {
                waitingForStorageSettings = false;
                clearPendingStorageAction();
                Toast.makeText(this, "Storage access was not granted", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // The post() request above can run before the window gains focus (for
        // example when this screen is opened with the D-pad), leaving no view
        // focused so the first key press is swallowed. Claim the initial focus
        // once the window is actually focusable, like the Settings screen.
        if (hasFocus && !initialFocusApplied && firstThemeRow != null) {
            initialFocusApplied = true;
            firstThemeRow.requestFocus();
        }
    }

    private void updateHighlights(List<TextView> rows, String selected) {
        for (TextView row : rows) {
            boolean isSelected = selected != null && selected.equals(row.getTag());
            row.setBackgroundResource(isSelected
                    ? R.drawable.option_row_selected_background
                    : R.drawable.option_row_background);
        }
    }

    private void commitAndFinish() {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(KEY_THEME, pendingTheme);
        editor.putBoolean(KEY_BGM_ENABLED, pendingBgmEnabled);
        editor.putString(KEY_BGM_PATH, pendingBgmPath);
        editor.putString(KEY_BGM_NAME, pendingBgmName);
        editor.putString(KEY_SKIN, pendingSkin);
        editor.commit();
        opendoja.host.OpenDoJaLog.info(ThemeActivity.class, "Theme saved: " + pendingTheme);
        finish();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_BGM_SAF
                && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            // Some pickers return URIs without a file name/extension; resolve the display name
            // and identify the format from the content itself.
            String name = queryDisplayName(uri);
            if (name == null || name.isEmpty()) name = uri.getLastPathSegment();
            if (name == null) name = "track";
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in != null) {
                    installBgmFromStream(in, name);
                }
            } catch (Exception e) {
                Toast.makeText(this, R.string.bgm_import_failed, Toast.LENGTH_LONG).show();
            }
        }
    }

    /** Built-in picker: works with or without DocumentsUI and is D-pad navigable. */
    private void pickBgmCustom() {
        runWithStorageAccess(
                () -> CustomFilePicker.pickFile(this, "Select audio file",
                        new String[]{".mp3", ".ogg", ".mld"}, file -> {
                            try (InputStream in = new FileInputStream(file)) {
                                installBgmFromStream(in, file.getName());
                            } catch (Exception e) {
                                Toast.makeText(this, R.string.bgm_import_failed, Toast.LENGTH_LONG).show();
                            }
                        }),
                this::launchSafBgmPicker);
    }

    private boolean hasDirectStorageAccess() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            return android.os.Environment.isExternalStorageManager();
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void runWithStorageAccess(Runnable directAction, Runnable safAction) {
        if (hasDirectStorageAccess()) {
            clearPendingStorageAction();
            directAction.run();
            return;
        }
        pendingStorageAction = directAction;
        pendingSafAction = safAction;
        waitingForStorageSettings = false;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            showStorageAccessChoice();
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            requestPermissions(new String[]{
                    android.Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQUEST_STORAGE_PERMISSION);
        }
    }

    /**
     * The BGM picker shares the same storage gate as the launcher. Devices
     * without DocumentsUI must use "Grant access"; the system picker entry is
     * only a fallback for devices that still provide one.
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
        waitingForStorageSettings = false;
    }

    /** System picker fallback (SAF): copies through a content URI and needs no storage permission. */
    private void launchSafBgmPicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(intent, REQUEST_PICK_BGM_SAF);
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
        if (hasDirectStorageAccess()) {
            Runnable action = pendingStorageAction;
            clearPendingStorageAction();
            action.run();
            return;
        }
        // Keep the pending action: the user can grant access in Settings and
        // come back, or use the system picker fallback.
        showStoragePermissionDenied();
    }

    private void installBgmFromStream(InputStream in, String name) {
        // Copy to app-private storage right away: the picker's URI grant does not outlive
        // this screen, and type detection needs the bytes anyway.
        File dir = new File(getFilesDir(), "bgm");
        dir.mkdirs();
        File tmp = new File(dir, "bgm_track.tmp");
        try (InputStream source = in; FileOutputStream fos = new FileOutputStream(tmp)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = source.read(buf)) > 0) fos.write(buf, 0, n);
        } catch (Exception e) {
            tmp.delete();
            Toast.makeText(this, R.string.bgm_import_failed, Toast.LENGTH_LONG).show();
            return;
        }

        String lower = name.toLowerCase(Locale.ROOT);
        byte[] magic = readMagic(tmp);
        String ext;
        if (lower.endsWith(".mld") || isMeloMagic(magic)) {
            ext = ".mld";
        } else if (lower.endsWith(".ogg") || isOggMagic(magic)) {
            ext = ".ogg";
        } else if (lower.endsWith(".mp3") || isMp3Magic(magic)) {
            ext = ".mp3";
        } else {
            tmp.delete();
            Toast.makeText(this, R.string.unsupported_audio, Toast.LENGTH_LONG).show();
            return;
        }

        File out = new File(dir, "bgm_track" + ext);
        if (out.exists()) out.delete();
        if (!tmp.renameTo(out)) {
            tmp.delete();
            Toast.makeText(this, R.string.bgm_import_failed, Toast.LENGTH_LONG).show();
            return;
        }
        if (ext.equals(".mld") && !looksLikeMld(out)) {
            out.delete();
            Toast.makeText(this, R.string.invalid_mld, Toast.LENGTH_LONG).show();
            return;
        }
        pendingBgmPath = out.getAbsolutePath();
        pendingBgmEnabled = true;
        pendingBgmName = name;
        HomeActivity.stopBgmStatic();
        updateBgmSelection();
    }

    /** Resolves a picker URI to its display name; many providers omit it from the path. */
    private String queryDisplayName(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String value = cursor.getString(idx);
                    if (value != null && !value.isEmpty()) return value;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean looksLikeMld(File file) {
        return isMeloMagic(readMagic(file));
    }

    private static byte[] readMagic(File file) {
        byte[] magic = new byte[4];
        try (FileInputStream in = new FileInputStream(file)) {
            int got = in.read(magic);
            if (got < magic.length) return new byte[0];
            return magic;
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static boolean isMeloMagic(byte[] m) {
        return m.length >= 4 && m[0] == 'm' && m[1] == 'e' && m[2] == 'l' && m[3] == 'o';
    }

    private static boolean isOggMagic(byte[] m) {
        return m.length >= 4 && m[0] == 'O' && m[1] == 'g' && m[2] == 'g' && m[3] == 'S';
    }

    private static boolean isMp3Magic(byte[] m) {
        return m.length >= 3
                && ((m[0] == 'I' && m[1] == 'D' && m[2] == '3')
                || (((m[0] & 0xFF) == 0xFF) && ((m[1] & 0xE0) == 0xE0)));
    }

    private void updateBgmSelection() {
        boolean defaultSelected = pendingBgmEnabled
                && (pendingBgmPath == null || pendingBgmPath.isEmpty()
                || !(new java.io.File(pendingBgmPath).exists()));
        boolean customSelected = pendingBgmEnabled
                && pendingBgmPath != null && !pendingBgmPath.isEmpty()
                && new java.io.File(pendingBgmPath).exists();
        boolean noSelected = !pendingBgmEnabled;
        if (defaultBgmRow != null) {
            defaultBgmRow.setBackgroundResource(defaultSelected
                    ? R.drawable.option_row_selected_background
                    : R.drawable.option_row_background);
        }
        if (selectBgmButton != null) {
            selectBgmButton.setBackgroundResource(customSelected
                    ? R.drawable.option_row_selected_background
                    : R.drawable.option_row_background);
        }
        if (noBgmRow != null) {
            noBgmRow.setBackgroundResource(noSelected
                    ? R.drawable.option_row_selected_background
                    : R.drawable.option_row_background);
        }
        if (bgmLabel != null) {
            if (!pendingBgmEnabled) {
                bgmLabel.setText(R.string.no_track_selected);
            } else if (pendingBgmPath == null || pendingBgmPath.isEmpty()
                    || !(new java.io.File(pendingBgmPath).exists())) {
                bgmLabel.setText(R.string.default_bgm);
            } else {
                bgmLabel.setText((pendingBgmName == null || pendingBgmName.isEmpty())
                        ? "Custom track" : pendingBgmName);
            }
        }
    }

    private void addFocusHighlight(TextView row) {
        row.setFocusable(true);
        // Deliberately not focusable in touch mode: otherwise the first tap
        // only focuses the row and a second tap is needed to click it, unlike
        // the Settings screen where one tap selects the option.
        row.setFocusableInTouchMode(false);
        // Same treatment as the Settings screen: the selected row keeps its
        // fill, a focused unselected row shows only the outline. The platform
        // highlight is disabled so the two never stack.
        FocusHighlight.disable(row);
        row.setBackgroundResource(R.drawable.option_row_background);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }
}
