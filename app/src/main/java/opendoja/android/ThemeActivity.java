package opendoja.android;

import android.app.Activity;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import android.net.Uri;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
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
            themeRows.add(row);
            root.addView(row, matchWrap());
        }
        updateHighlights(themeRows, pendingTheme);

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
        root.addView(defaultBgmRow, matchWrap());

        selectBgmButton = new TextView(this);
        selectBgmButton.setText(R.string.select_audio);
        selectBgmButton.setTextSize(16f);
        selectBgmButton.setPadding(dp(12), dp(10), dp(12), dp(10));
        selectBgmButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES,
                    new String[]{"audio/*", "application/octet-stream"});
            if (intent.resolveActivity(getPackageManager()) == null) {
                pickBgmCustom();
                return;
            }
            try {
                startActivityForResult(intent, 1);
            } catch (RuntimeException exception) {
                pickBgmCustom();
            }
        });
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
        buttons.addView(ok);
        TextView cancel = new TextView(this);
        cancel.setText(R.string.cancel);
        cancel.setTextSize(16f);
        cancel.setPadding(dp(16), dp(10), dp(16), dp(10));
        cancel.setOnClickListener(v -> finish());
        buttons.addView(cancel);
        root.addView(buttons, matchWrap());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);
    }

    private void updateHighlights(List<TextView> rows, String selected) {
        for (TextView row : rows) {
            row.setBackgroundColor(selected != null && selected.equals(row.getTag())
                    ? 0x663080FF : 0x00000000);
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
        if (requestCode == 1 && resultCode == RESULT_OK && data != null && data.getData() != null) {
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

    /** Fallback used when the device has no system document picker. */
    private void pickBgmCustom() {
        CustomFilePicker.pickFile(this, "Select audio file",
                new String[]{".mp3", ".ogg", ".mld"}, file -> {
                    try (InputStream in = new FileInputStream(file)) {
                        installBgmFromStream(in, file.getName());
                    } catch (Exception e) {
                        Toast.makeText(this, R.string.bgm_import_failed, Toast.LENGTH_LONG).show();
                    }
                });
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
            defaultBgmRow.setBackgroundColor(defaultSelected ? 0x663080FF : 0x00000000);
        }
        if (selectBgmButton != null) {
            selectBgmButton.setBackgroundColor(customSelected ? 0x663080FF : 0x00000000);
        }
        if (noBgmRow != null) {
            noBgmRow.setBackgroundColor(noSelected ? 0x663080FF : 0x00000000);
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

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }
}
