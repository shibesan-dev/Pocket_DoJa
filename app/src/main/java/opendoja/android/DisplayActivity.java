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
    static final String PREFS_NAME = "display";
    static final String KEY_ORIENTATION = "orientation";
    static final String KEY_DISPLAY_MODE = "displayMode";
    static final String KEY_HIDE_KEYPAD = "hideKeypad";
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

    private final List<OptionRow> orientationRows = new ArrayList<>();
    private final List<OptionRow> modeRows = new ArrayList<>();

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
            pendingOrientation = prefs.getString(KEY_ORIENTATION, ORIENTATION_PORTRAIT);
            pendingMode = prefs.getString(KEY_DISPLAY_MODE, MODE_ORIGINAL);
        }

        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(perGame ? "Display: " + gameId : "Display");
        title.setTextSize(24f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title, matchWrap());

        TextView section1 = new TextView(this);
        section1.setText("Orientation");
        section1.setTextSize(18f);
        section1.setTypeface(Typeface.DEFAULT_BOLD);
        section1.setPadding(0, pad, 0, pad / 2);
        root.addView(section1, matchWrap());

        if (perGame) {
            addOptionGroup(root, orientationRows,
                    new String[]{"Inherit global", "Portrait", "Landscape", "Portrait (reverse)", "Landscape (reverse)"},
                    new String[]{INHERIT, ORIENTATION_PORTRAIT, ORIENTATION_LANDSCAPE, ORIENTATION_PORTRAIT_REV, ORIENTATION_LANDSCAPE_REV},
                    pendingOrientation,
                    value -> pendingOrientation = value);
        } else {
            addOptionGroup(root, orientationRows,
                    new String[]{"Portrait", "Landscape", "Portrait (reverse)", "Landscape (reverse)"},
                    new String[]{ORIENTATION_PORTRAIT, ORIENTATION_LANDSCAPE, ORIENTATION_PORTRAIT_REV, ORIENTATION_LANDSCAPE_REV},
                    pendingOrientation,
                    value -> pendingOrientation = value);
        }

        TextView section2 = new TextView(this);
        section2.setText("Game screen");
        section2.setTextSize(18f);
        section2.setTypeface(Typeface.DEFAULT_BOLD);
        section2.setPadding(0, pad, 0, pad / 2);
        root.addView(section2, matchWrap());

        if (perGame) {
            addOptionGroup(root, modeRows,
                    new String[]{"Inherit global", "Original", "Full Screen"},
                    new String[]{INHERIT, MODE_ORIGINAL, MODE_FULLSCREEN},
                    pendingMode,
                    value -> pendingMode = value);
        } else {
            addOptionGroup(root, modeRows,
                    new String[]{"Original", "Full Screen"},
                    new String[]{MODE_ORIGINAL, MODE_FULLSCREEN},
                    pendingMode,
                    value -> pendingMode = value);
        }

        // Reset to global defaults (per-game only)
        if (perGame) {
            TextView reset = new TextView(this);
            reset.setText("Reset to Global Defaults");
            reset.setTextSize(16f);
            reset.setPadding(dp(12), dp(10), dp(12), dp(10));
            reset.setOnClickListener(v -> {
                pendingOrientation = INHERIT;
                pendingMode = INHERIT;
                refreshHighlights();
            });
            root.addView(reset, matchWrap());
        }

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        TextView ok = new TextView(this);
        ok.setText("OK");
        ok.setTextSize(16f);
        ok.setPadding(dp(16), dp(10), dp(16), dp(10));
        ok.setOnClickListener(v -> commitAndFinish());
        buttons.addView(ok);
        TextView cancel = new TextView(this);
        cancel.setText("Cancel");
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
    }

    private void commitAndFinish() {
        android.util.Log.i("OpenDoJa", "commitAndFinish: gameId=" + gameId + " orientation=" + pendingOrientation + " mode=" + pendingMode);
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
        editor.commit();
        HomeActivity.applyDisplayOrientation(this, gameId);
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
