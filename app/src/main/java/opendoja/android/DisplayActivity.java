package opendoja.android;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class DisplayActivity extends Activity {
    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    static final String PREFS_NAME = "display";
    static final String KEY_ORIENTATION = "orientation";
    static final String KEY_DISPLAY_MODE = "displayMode";
    static final String KEY_HIDE_KEYPAD = "hideKeypad";
    static final String KEY_DEBUG = "debugLogging";
    static final String INHERIT = "inherit";

    static final String ORIENTATION_PORTRAIT = "portrait";
    static final String ORIENTATION_LANDSCAPE = "landscape";
    static final String ORIENTATION_PORTRAIT_REV = "portrait_reverse";
    static final String ORIENTATION_LANDSCAPE_REV = "landscape_reverse";

    static final String MODE_ORIGINAL = "original";
    static final String MODE_FULLSCREEN = "fullscreen";

    private SharedPreferences prefs;
    private String gameId;
    private String pendingOrientation;
    private String pendingMode;
    private String pendingDebug;
    private boolean pendingUseSystem;
    private String pendingLanguage;

    private final List<OptionRow> orientationRows = new ArrayList<>();
    private final List<OptionRow> modeRows = new ArrayList<>();
    private final List<OptionRow> debugRows = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        gameId = getIntent() != null ? getIntent().getStringExtra("gameId") : null;
        boolean perGame = gameId != null && !gameId.isEmpty();

        if (perGame) {
            pendingOrientation = prefs.getString(gameId + "_" + KEY_ORIENTATION, INHERIT);
            pendingMode = prefs.getString(gameId + "_" + KEY_DISPLAY_MODE, INHERIT);
        } else {
            if (prefs.contains(KEY_ORIENTATION)) {
                pendingOrientation = prefs.getString(KEY_ORIENTATION, ORIENTATION_PORTRAIT);
            } else {
                int devOri = getResources().getConfiguration().orientation;
                pendingOrientation = (devOri == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
                        ? ORIENTATION_LANDSCAPE : ORIENTATION_PORTRAIT;
            }
            pendingMode = prefs.getString(KEY_DISPLAY_MODE, MODE_ORIGINAL);
        }
        pendingDebug = prefs.getString(KEY_DEBUG, "disable");
        pendingUseSystem = prefs.getBoolean(LocaleHelper.KEY_USE_SYSTEM_LANGUAGE, true);
        pendingLanguage = prefs.getString(LocaleHelper.KEY_LANGUAGE, "en");

        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(perGame ? "Settings: " + gameId : getString(R.string.menu_settings));
        title.setTextSize(24f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title, matchWrap());

        TextView section1 = new TextView(this);
        section1.setText(R.string.orientation);
        section1.setTextSize(18f);
        section1.setTypeface(Typeface.DEFAULT_BOLD);
        section1.setPadding(0, pad, 0, pad / 2);
        root.addView(section1, matchWrap());

        if (perGame) {
            addOptionGroup(root, orientationRows,
                    new String[]{getString(R.string.inherit_global), getString(R.string.portrait), getString(R.string.landscape), getString(R.string.portrait_reverse), getString(R.string.landscape_reverse)},
                    new String[]{INHERIT, ORIENTATION_PORTRAIT, ORIENTATION_LANDSCAPE, ORIENTATION_PORTRAIT_REV, ORIENTATION_LANDSCAPE_REV},
                    pendingOrientation,
                    value -> pendingOrientation = value);
        } else {
            addOptionGroup(root, orientationRows,
                    new String[]{getString(R.string.portrait), getString(R.string.landscape), getString(R.string.portrait_reverse), getString(R.string.landscape_reverse)},
                    new String[]{ORIENTATION_PORTRAIT, ORIENTATION_LANDSCAPE, ORIENTATION_PORTRAIT_REV, ORIENTATION_LANDSCAPE_REV},
                    pendingOrientation,
                    value -> pendingOrientation = value);
        }

        TextView section2 = new TextView(this);
        section2.setText(R.string.game_screen);
        section2.setTextSize(18f);
        section2.setTypeface(Typeface.DEFAULT_BOLD);
        section2.setPadding(0, pad, 0, pad / 2);
        root.addView(section2, matchWrap());

        if (perGame) {
            addOptionGroup(root, modeRows,
                    new String[]{getString(R.string.inherit_global), getString(R.string.original), getString(R.string.full_screen)},
                    new String[]{INHERIT, MODE_ORIGINAL, MODE_FULLSCREEN},
                    pendingMode,
                    value -> pendingMode = value);
        } else {
            addOptionGroup(root, modeRows,
                    new String[]{getString(R.string.original), getString(R.string.full_screen)},
                    new String[]{MODE_ORIGINAL, MODE_FULLSCREEN},
                    pendingMode,
                    value -> pendingMode = value);
        }

        if (!perGame) {
            // Debugging (global)
            TextView sectionDebug = new TextView(this);
            sectionDebug.setText(R.string.debugging);
            sectionDebug.setTextSize(18f);
            sectionDebug.setTypeface(Typeface.DEFAULT_BOLD);
            sectionDebug.setPadding(0, pad, 0, pad / 2);
            root.addView(sectionDebug, matchWrap());
            addOptionGroup(root, debugRows,
                    new String[]{getString(R.string.enable), getString(R.string.disable)},
                    new String[]{"enable", "disable"},
                    pendingDebug,
                    value -> pendingDebug = value);
        }
        if (perGame) {
            TextView reset = new TextView(this);
            reset.setText(R.string.reset_to_global);
            reset.setTextSize(16f);
            reset.setPadding(dp(12), dp(10), dp(12), dp(10));
            reset.setOnClickListener(v -> {
                pendingOrientation = INHERIT;
                pendingMode = INHERIT;
                refreshHighlights();
            });
            root.addView(reset, matchWrap());
        }

        if (!perGame) {
            // Language section
            TextView sectionLang = new TextView(this);
            sectionLang.setText("Language");
            sectionLang.setTextSize(18f);
            sectionLang.setTypeface(Typeface.DEFAULT_BOLD);
            sectionLang.setPadding(0, pad, 0, pad / 2);
            root.addView(sectionLang, matchWrap());

            android.widget.CheckBox useSystemCb = new android.widget.CheckBox(this);
            useSystemCb.setText("Use System Language");
            useSystemCb.setChecked(pendingUseSystem);
            useSystemCb.setOnCheckedChangeListener((b, checked) -> pendingUseSystem = checked);
            root.addView(useSystemCb, matchWrap());

            TextView langRow = new TextView(this);
            langRow.setTextSize(16f);
            langRow.setPadding(dp(12), dp(10), dp(12), dp(10));
            Runnable updateLangLabel = () -> {
                String name = pendingLanguage;
                for (int i = 0; i < LocaleHelper.LANGUAGE_TAGS.length; i++) {
                    if (LocaleHelper.LANGUAGE_TAGS[i].equals(pendingLanguage)) {
                        name = LocaleHelper.LANGUAGE_NAMES[i];
                        break;
                    }
                }
                langRow.setText(name);
            };
            updateLangLabel.run();
            langRow.setOnClickListener(v -> {
                new android.app.AlertDialog.Builder(this)
                        .setTitle("Language")
                        .setItems(LocaleHelper.LANGUAGE_NAMES, (d, which) -> {
                            pendingLanguage = LocaleHelper.LANGUAGE_TAGS[which];
                            updateLangLabel.run();
                        })
                        .show();
            });
            root.addView(langRow, matchWrap());
        }

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

        refreshHighlights();
    }

    private void addOptionGroup(LinearLayout root, List<OptionRow> rows,
                                String[] labels, String[] values,
                                String current, java.util.function.Consumer<String> setter) {
        for (int i = 0; i < labels.length; i++) {
            final String value = values[i];
            TextView row = new TextView(this);
            row.setText(labels[i]);
            row.setTextSize(16f);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setOnClickListener(v -> {
                setter.accept(value);
                refreshHighlights();
            });
            rows.add(new OptionRow(row, value));
            root.addView(row, matchWrap());
        }
    }

    private void refreshHighlights() {
        for (OptionRow row : orientationRows) {
            row.view.setBackgroundColor(row.value.equals(pendingOrientation) ? 0x663080FF : 0x00000000);
        }
        for (OptionRow row : modeRows) {
            row.view.setBackgroundColor(row.value.equals(pendingMode) ? 0x663080FF : 0x00000000);
        }
        for (OptionRow row : debugRows) {
            row.view.setBackgroundColor(row.value.equals(pendingDebug) ? 0x663080FF : 0x00000000);
        }
    }

    private void commitAndFinish() {
        opendoja.host.OpenDoJaLog.info(DisplayActivity.class, "commitAndFinish: gameId=" + gameId + " orientation=" + pendingOrientation + " mode=" + pendingMode);
        SharedPreferences.Editor editor = prefs.edit();
        String gameId = this.gameId;
        if (gameId != null && !gameId.isEmpty()) {
            if (pendingOrientation.equals(INHERIT)) {
                editor.remove(gameId + "_" + KEY_ORIENTATION);
            } else {
                editor.putString(gameId + "_" + KEY_ORIENTATION, pendingOrientation);
            }
            if (pendingMode.equals(INHERIT)) {
                editor.remove(gameId + "_" + KEY_DISPLAY_MODE);
            } else {
                editor.putString(gameId + "_" + KEY_DISPLAY_MODE, pendingMode);
            }
        } else {
            editor.putString(KEY_ORIENTATION, pendingOrientation);
            editor.putString(KEY_DISPLAY_MODE, pendingMode);
        }
         if (gameId == null || gameId.isEmpty()) {
             editor.putString(KEY_DEBUG, pendingDebug);
             editor.putBoolean(LocaleHelper.KEY_USE_SYSTEM_LANGUAGE, pendingUseSystem);
             editor.putString(LocaleHelper.KEY_LANGUAGE, pendingLanguage);
         }
         editor.commit();
        HomeActivity.applyDisplayOrientation(this, gameId);
        if (HomeActivity.instance != null) {
            HomeActivity.applyDisplayOrientation(HomeActivity.instance, gameId);
            HomeActivity.instance.recreate();
        }
        finish();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private static class OptionRow {
        final TextView view;
        final String value;

        OptionRow(TextView view, String value) {
            this.view = view;
            this.value = value;
        }
    }
}
