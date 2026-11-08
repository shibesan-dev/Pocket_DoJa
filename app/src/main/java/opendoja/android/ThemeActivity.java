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

import java.util.ArrayList;
import java.util.List;

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
            intent.setType("audio/*");
            startActivityForResult(intent, 1);
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
            String name = uri.getLastPathSegment();
            if (name == null) name = "track";
            if (!name.toLowerCase().endsWith(".mp3") && !name.toLowerCase().endsWith(".ogg")) {
                return;
            }
            // Copy to app-private storage
            File dir = new File(getFilesDir(), "bgm");
            dir.mkdirs();
            File out = new File(dir, "bgm_track" + (name.endsWith(".ogg") ? ".ogg" : ".mp3"));
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            } catch (Exception e) {
                return;
            }
             pendingBgmPath = out.getAbsolutePath();
            pendingBgmEnabled = true;
            pendingBgmName = name;
            HomeActivity.stopBgmStatic();
            updateBgmSelection();
        }
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
