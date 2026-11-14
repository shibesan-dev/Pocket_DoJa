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
    static final String KEY_GAME_DISPLAY = "gameDisplay";
    static final String KEY_FILTER = "filter";
    static final String KEY_HIDE_KEYPAD = "hideKeypad";
    static final String KEY_SHOW_FPS = "showFps";
    static final String KEY_USE_HARDWARE_RENDERER = "useHardwareRenderer";
    static final String KEY_USE_SYSTEM_KEYBOARD = "useSystemKeyboard";
    static final String KEY_DEBUG = "debugLogging";
    static final String KEY_DEFAULTS_VERSION = "defaultsVersion";
    static final int DEFAULTS_VERSION = 1;
    static final String INHERIT = "inherit";

    static final String ORIENTATION_PORTRAIT = "portrait";
    static final String ORIENTATION_LANDSCAPE = "landscape";
    static final String ORIENTATION_PORTRAIT_REV = "portrait_reverse";
    static final String ORIENTATION_LANDSCAPE_REV = "landscape_reverse";

    static final String MODE_ORIGINAL = "original";
    static final String MODE_FULLSCREEN = "fullscreen";

    static final String GAME_DISPLAY_FILL = "fill";
    static final String GAME_DISPLAY_FIT = "fit";

    private SharedPreferences prefs;
    private String gameId;
    private String pendingOrientation;
    private String pendingMode;
    private String pendingGameDisplay;
    private String pendingFilter;
    private String pendingDebug;
    private boolean pendingShowFps;
    private boolean pendingUseHardwareRenderer;
    private boolean pendingUseSystemKeyboard;
    private int pendingHapticIntensity;
    private boolean hapticDragging;
    private boolean pendingUseSystem;
    private String pendingLanguage;

    private final List<OptionRow> orientationRows = new ArrayList<>();
    private final List<OptionRow> modeRows = new ArrayList<>();
    private final List<OptionRow> gameDisplayRows = new ArrayList<>();
    private final List<OptionRow> filterRows = new ArrayList<>();
    private final List<OptionRow> debugRows = new ArrayList<>();

    /**
     * Applies the shipping defaults once. Preference files survive app updates,
     * so changing a default in code has no effect on an existing install
     * without this bump. Users can change the values again afterwards.
     */
    static void applyShippingDefaults(android.content.Context context) {
        if (context == null) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(
                PREFS_NAME, android.content.Context.MODE_PRIVATE);
        if (prefs.getInt(KEY_DEFAULTS_VERSION, 0) >= DEFAULTS_VERSION) {
            return;
        }
        prefs.edit()
                .putBoolean(KEY_USE_HARDWARE_RENDERER, true)   // on by default
                .putString(KEY_DEBUG, "disable")               // debugging off by default
                .putBoolean(KEY_SHOW_FPS, false)               // FPS counter off by default
                .putInt(ButtonHaptics.PREF_KEY, ButtonHaptics.DEFAULT_INTENSITY)
                .putInt(KEY_DEFAULTS_VERSION, DEFAULTS_VERSION)
                .apply();
    }

    /**
     * True when Search/Rename should use the device's installed IME instead of
     * the built-in D-pad keyboard. Defaults to false because system IMEs are not
     * reliably navigable with a remote.
     */
    static boolean isSystemKeyboardEnabled(android.content.Context context) {
        if (context == null) {
            return false;
        }
        return context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .getBoolean(KEY_USE_SYSTEM_KEYBOARD, false);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyShippingDefaults(this);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        gameId = getIntent() != null ? getIntent().getStringExtra("gameId") : null;
        boolean perGame = gameId != null && !gameId.isEmpty();

        if (perGame) {
            pendingOrientation = prefs.getString(gameId + "_" + KEY_ORIENTATION, INHERIT);
            pendingMode = prefs.getString(gameId + "_" + KEY_DISPLAY_MODE, INHERIT);
            pendingGameDisplay = prefs.getString(gameId + "_" + KEY_GAME_DISPLAY, INHERIT);
            pendingFilter = prefs.getString(gameId + "_" + KEY_FILTER, INHERIT);
        } else {
            if (prefs.contains(KEY_ORIENTATION)) {
                pendingOrientation = prefs.getString(KEY_ORIENTATION, ORIENTATION_PORTRAIT);
            } else {
                int devOri = getResources().getConfiguration().orientation;
                pendingOrientation = (devOri == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
                        ? ORIENTATION_LANDSCAPE : ORIENTATION_PORTRAIT;
            }
            pendingMode = prefs.getString(KEY_DISPLAY_MODE, MODE_ORIGINAL);
            pendingGameDisplay = prefs.getString(KEY_GAME_DISPLAY, GAME_DISPLAY_FILL);
            pendingFilter = prefs.getString(KEY_FILTER, GameFilters.ID_NONE);
        }
        pendingDebug = prefs.getString(KEY_DEBUG, "disable");
        pendingShowFps = prefs.getBoolean(KEY_SHOW_FPS, false);
        pendingUseHardwareRenderer = prefs.getBoolean(KEY_USE_HARDWARE_RENDERER, true);
        pendingUseSystemKeyboard = prefs.getBoolean(KEY_USE_SYSTEM_KEYBOARD, false);
        pendingHapticIntensity = prefs.getInt(ButtonHaptics.PREF_KEY,
                ButtonHaptics.DEFAULT_INTENSITY);
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

        TextView sectionGameDisplay = new TextView(this);
        sectionGameDisplay.setText(R.string.game_display);
        sectionGameDisplay.setTextSize(18f);
        sectionGameDisplay.setTypeface(Typeface.DEFAULT_BOLD);
        sectionGameDisplay.setPadding(0, pad, 0, pad / 2);
        root.addView(sectionGameDisplay, matchWrap());

        if (perGame) {
            addOptionGroup(root, gameDisplayRows,
                    new String[]{getString(R.string.inherit_global), getString(R.string.fill_screen), getString(R.string.scale_to_fit)},
                    new String[]{INHERIT, GAME_DISPLAY_FILL, GAME_DISPLAY_FIT},
                    pendingGameDisplay,
                    value -> pendingGameDisplay = value);
        } else {
            addOptionGroup(root, gameDisplayRows,
                    new String[]{getString(R.string.fill_screen), getString(R.string.scale_to_fit)},
                    new String[]{GAME_DISPLAY_FILL, GAME_DISPLAY_FIT},
                    pendingGameDisplay,
                    value -> pendingGameDisplay = value);
        }

        // Display filter: global setting and per-game override.
        TextView sectionFilter = new TextView(this);
        sectionFilter.setText(R.string.filter);
        sectionFilter.setTextSize(18f);
        sectionFilter.setTypeface(Typeface.DEFAULT_BOLD);
        sectionFilter.setPadding(0, pad, 0, pad / 2);
        root.addView(sectionFilter, matchWrap());

        String[] filterLabels = {
                getString(R.string.filter_none),
                getString(R.string.filter_scanlines),
                getString(R.string.filter_lcd_grid),
                getString(R.string.filter_crt),
                getString(R.string.filter_dot_matrix),
                getString(R.string.filter_gameboy),
                getString(R.string.filter_sepia),
                getString(R.string.filter_grayscale),
        };
        if (perGame) {
            String[] labels = new String[filterLabels.length + 1];
            String[] values = new String[GameFilters.IDS.length + 1];
            labels[0] = getString(R.string.inherit_global);
            values[0] = INHERIT;
            System.arraycopy(filterLabels, 0, labels, 1, filterLabels.length);
            System.arraycopy(GameFilters.IDS, 0, values, 1, GameFilters.IDS.length);
            addOptionGroup(root, filterRows, labels, values,
                    pendingFilter,
                    value -> pendingFilter = value);
        } else {
            addOptionGroup(root, filterRows, filterLabels, GameFilters.IDS,
                    pendingFilter,
                    value -> pendingFilter = value);
        }

        if (!perGame) {
            // FPS counter overlay (global): drawn on the game screen itself.
            TextView sectionFps = new TextView(this);
            sectionFps.setText(R.string.display_fps);
            sectionFps.setTextSize(18f);
            sectionFps.setTypeface(Typeface.DEFAULT_BOLD);
            sectionFps.setPadding(0, pad, 0, pad / 2);
            root.addView(sectionFps, matchWrap());

            android.widget.CheckBox showFpsCb = new android.widget.CheckBox(this);
            showFpsCb.setText(R.string.show_fps);
            showFpsCb.setChecked(pendingShowFps);
            showFpsCb.setOnCheckedChangeListener((b, checked) -> pendingShowFps = checked);
            root.addView(showFpsCb, matchWrap());

            android.widget.CheckBox hardwareCb = new android.widget.CheckBox(this);
            hardwareCb.setText(R.string.use_hardware_rendering);
            hardwareCb.setChecked(pendingUseHardwareRenderer);
            hardwareCb.setOnCheckedChangeListener((b, checked) ->
                    pendingUseHardwareRenderer = checked);
            root.addView(hardwareCb, matchWrap());

            android.widget.CheckBox systemKeyboardCb = new android.widget.CheckBox(this);
            systemKeyboardCb.setText(R.string.use_system_keyboard);
            systemKeyboardCb.setChecked(pendingUseSystemKeyboard);
            systemKeyboardCb.setOnCheckedChangeListener((b, checked) ->
                    pendingUseSystemKeyboard = checked);
            root.addView(systemKeyboardCb, matchWrap());

            // Touch haptics for the on-screen controls: 0 = off.
            TextView hapticLabel = new TextView(this);
            hapticLabel.setTextSize(16f);
            hapticLabel.setPadding(dp(12), dp(12), dp(12), 0);
            hapticLabel.setText(getString(R.string.haptic_feedback_value,
                    pendingHapticIntensity));
            root.addView(hapticLabel, matchWrap());

            android.widget.SeekBar hapticSeek = new android.widget.SeekBar(this);
            hapticSeek.setMax(100);
            hapticSeek.setProgress(pendingHapticIntensity);
            hapticSeek.setPadding(dp(12), 0, dp(12), dp(4));
            hapticSeek.setOnSeekBarChangeListener(
                    new android.widget.SeekBar.OnSeekBarChangeListener() {
                        @Override
                        public void onProgressChanged(android.widget.SeekBar seekBar,
                                                      int progress, boolean fromUser) {
                            pendingHapticIntensity = progress;
                            hapticLabel.setText(getString(
                                    R.string.haptic_feedback_value, progress));
                            // Do not vibrate while the finger is dragging: each
                            // tick would restart the motor before it can spin
                            // down, so the whole slider feels like one strong,
                            // unchanging buzz. Preview after release instead.
                            if (fromUser && !hapticDragging) {
                                ButtonHaptics.tick(DisplayActivity.this, progress);
                            }
                        }

                        @Override
                        public void onStartTrackingTouch(android.widget.SeekBar seekBar) {
                            hapticDragging = true;
                        }

                        @Override
                        public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
                            hapticDragging = false;
                            // Preview the selected strength once the motor has
                            // had a chance to come to rest.
                            ButtonHaptics.tick(DisplayActivity.this,
                                    pendingHapticIntensity);
                        }
                    });
            root.addView(hapticSeek, matchWrap());
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
                pendingGameDisplay = INHERIT;
                pendingFilter = INHERIT;
                refreshHighlights();
            });
            root.addView(reset, matchWrap());
        }

        if (!perGame) {
            // Language section
            TextView sectionLang = new TextView(this);
            sectionLang.setText(R.string.language);
            sectionLang.setTextSize(18f);
            sectionLang.setTypeface(Typeface.DEFAULT_BOLD);
            sectionLang.setPadding(0, pad, 0, pad / 2);
            root.addView(sectionLang, matchWrap());

            android.widget.CheckBox useSystemCb = new android.widget.CheckBox(this);
            useSystemCb.setText(R.string.use_system_language);
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
                        .setTitle(R.string.language)
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
        // The list itself must not draw a screen-sized focus ring; the option
        // rows and checkboxes show the blue item highlight instead.
        FocusHighlight.disable(scroll);
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
            // Selection fill and focus outline live in one state list, so a
            // focused selected row does not stack the platform focus ring on
            // top of the blue fill.
            row.setBackgroundResource(R.drawable.option_row_background);
            row.setOnClickListener(v -> {
                setter.accept(value);
                refreshHighlights();
            });
            rows.add(new OptionRow(row, value));
            root.addView(row, matchWrap());
        }
    }

    private void refreshHighlights() {
        refreshRowGroup(orientationRows, pendingOrientation);
        refreshRowGroup(modeRows, pendingMode);
        refreshRowGroup(gameDisplayRows, pendingGameDisplay);
        refreshRowGroup(filterRows, pendingFilter);
        refreshRowGroup(debugRows, pendingDebug);
    }

    private void refreshRowGroup(List<OptionRow> rows, String selected) {
        for (OptionRow row : rows) {
            boolean isSelected = row.value.equals(selected);
            // Swap backgrounds instead of using View.setSelected(), because
            // the platform text color would dim the text of a selected view.
            row.view.setBackgroundResource(isSelected
                    ? R.drawable.option_row_selected_background
                    : R.drawable.option_row_background);
        }
    }

    private void commitAndFinish() {
        opendoja.host.OpenDoJaLog.info(DisplayActivity.class, "commitAndFinish: gameId=" + gameId + " orientation=" + pendingOrientation + " mode=" + pendingMode + " gameDisplay=" + pendingGameDisplay + " filter=" + pendingFilter);
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
            if (pendingGameDisplay.equals(INHERIT)) {
                editor.remove(gameId + "_" + KEY_GAME_DISPLAY);
            } else {
                editor.putString(gameId + "_" + KEY_GAME_DISPLAY, pendingGameDisplay);
            }
            if (pendingFilter.equals(INHERIT)) {
                editor.remove(gameId + "_" + KEY_FILTER);
            } else {
                editor.putString(gameId + "_" + KEY_FILTER, pendingFilter);
            }
        } else {
            editor.putString(KEY_ORIENTATION, pendingOrientation);
            editor.putString(KEY_DISPLAY_MODE, pendingMode);
            editor.putString(KEY_GAME_DISPLAY, pendingGameDisplay);
            editor.putString(KEY_FILTER, pendingFilter);
        }
         if (gameId == null || gameId.isEmpty()) {
             editor.putString(KEY_DEBUG, pendingDebug);
             editor.putBoolean(KEY_SHOW_FPS, pendingShowFps);
             editor.putBoolean(KEY_USE_HARDWARE_RENDERER, pendingUseHardwareRenderer);
             editor.putBoolean(KEY_USE_SYSTEM_KEYBOARD, pendingUseSystemKeyboard);
             editor.putInt(ButtonHaptics.PREF_KEY, pendingHapticIntensity);
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
