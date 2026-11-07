package opendoja.android;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import opendoja.host.HostControlAction;

/**
 * Binds physical hardware keys (volume keys etc.) to the same DoJa actions the
 * Windows host exposes in its keybind dialog. Tap a row, then press a hardware
 * key to bind it; long-press clears the binding. The system back key is always
 * reserved for force-quitting the game back to the title screen.
 */
public class KeyMappingActivity extends Activity {
    static final String PREFS_NAME = "keymap";

    public static String prefsNameForGame(String gameId) {
        if (gameId == null || gameId.isEmpty()) {
            return PREFS_NAME;
        }
        return PREFS_NAME + "_" + gameId;
    }

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    private SharedPreferences prefs;
    private String prefsName;
    private String gameId;
    private LinearLayout rowsContainer;
    private TextView headerView;
    private HostControlAction capturing;
    private View capturingRow;
    private boolean swallowBackUp;
    private final Map<String, TextView> bindingViews = new LinkedHashMap<>();

    private final Map<String, Integer> pending = new LinkedHashMap<>();
    private final Map<String, Long> lastTapTime = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        gameId = getIntent() != null ? getIntent().getStringExtra("gameId") : null;
        prefsName = (gameId == null || gameId.isEmpty()) ? PREFS_NAME : PREFS_NAME + "_" + gameId;

        SharedPreferences initialPrefs = getSharedPreferences(prefsName, MODE_PRIVATE);
        SharedPreferences fallbackPrefs = null;
        if (gameId != null && !gameId.isEmpty() && initialPrefs.getAll().isEmpty()) {
            fallbackPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        }
        prefs = initialPrefs;
        SharedPreferences sourcePrefs = (fallbackPrefs != null) ? fallbackPrefs : prefs;
        for (HostControlAction action : HostControlAction.values()) {
            int keyCode = sourcePrefs.getInt(action.id(), -1);
            if (keyCode >= 0) {
                pending.put(action.id(), keyCode);
            }
        }

        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(R.string.key_mapping_title);
        title.setTextSize(24f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title, matchWrap());

        headerView = new TextView(this);
        headerView.setTextSize(13f);
        headerView.setPadding(0, pad / 2, 0, pad);
        root.addView(headerView, matchWrap());
        updateHeader(null);

        rowsContainer = new LinearLayout(this);
        rowsContainer.setOrientation(LinearLayout.VERTICAL);
        for (HostControlAction action : HostControlAction.values()) {
            rowsContainer.addView(buildRow(action));
        }
        root.addView(rowsContainer, matchWrap());

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        TextView ok = new TextView(this);
        ok.setText(R.string.ok);
        ok.setTextSize(16f);
        ok.setPadding(dp(16), dp(10), dp(16), dp(10));
        ok.setOnClickListener(v -> commitAndFinish());
        buttons.addView(ok);
        TextView reset = new TextView(this);
        reset.setText(R.string.reset);
        reset.setTextSize(16f);
        reset.setPadding(dp(16), dp(10), dp(16), dp(10));
        reset.setOnClickListener(v -> {
            pending.clear();
            refreshBindings();
            updateHeader(null);
        });
        buttons.addView(reset);
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

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private View buildRow(HostControlAction action) {
        float density = getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int) (8 * density), (int) (10 * density), (int) (8 * density), (int) (10 * density));

        TextView name = new TextView(this);
        name.setText(action.displayName());
        name.setTextSize(16f);
        row.addView(name, weighted());

        TextView summary = new TextView(this);
        summary.setText(describeDispatch(this, action));
        summary.setTextSize(12f);
        summary.setAlpha(0.7f);
        row.addView(summary, weighted());

        TextView binding = new TextView(this);
        binding.setText(bindingLabel(action));
        binding.setTextSize(14f);
        binding.setGravity(Gravity.END);
        row.addView(binding, weighted());
        bindingViews.put(action.id(), binding);

        row.setFocusable(false);
        row.setFocusableInTouchMode(false);

        row.setOnClickListener(v -> {
            long now = System.currentTimeMillis();
            Long last = lastTapTime.get(action.id());
            if (last != null && now - last < 300) {
                pending.remove(action.id());
                refreshBindings();
                updateHeader(null);
                lastTapTime.remove(action.id());
            } else {
                lastTapTime.put(action.id(), now);
                beginCapture(action, row);
            }
        });
        row.setOnLongClickListener(v -> {
            pending.remove(action.id());
            refreshBindings();
            updateHeader(null);
            return true;
        });
        row.setTag(action);
        return row;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.rightMargin = (int) (8 * getResources().getDisplayMetrics().density);
        return params;
    }

    private static String describeDispatch(android.content.Context context, HostControlAction action) {
        if (action.dispatchKind() == HostControlAction.DispatchKind.HOST_SOFT_KEY) {
                return "Key";
        }
        if (action.dispatchKind() == HostControlAction.DispatchKind.HOST_ACTION) {
                return context.getString(R.string.exit_game_action);
        }
        return context.getString(R.string.doja_key, action.dispatchCode());
    }

    private String bindingLabel(HostControlAction action) {
        Integer keyCode = pending.get(action.id());
        if (keyCode == null || keyCode < 0) {
            return getString(R.string.unbound);
        }
        return keyLabel(keyCode);
    }

    static String keyLabel(int keyCode) {
        switch (keyCode) {
            case android.view.KeyEvent.KEYCODE_VOLUME_UP:
                return "Key";
            case android.view.KeyEvent.KEYCODE_VOLUME_DOWN:
                return "Key";
            case android.view.KeyEvent.KEYCODE_VOLUME_MUTE:
                return "Key";
            case android.view.KeyEvent.KEYCODE_BACK:
                return "Key";
            case android.view.KeyEvent.KEYCODE_HOME:
                return "Home";
            case android.view.KeyEvent.KEYCODE_ENTER:
                return "Key";
            case android.view.KeyEvent.KEYCODE_SPACE:
                return "Key";
            default:
                break;
        }
        String name = android.view.KeyEvent.keyCodeToString(keyCode);
        return name.startsWith("KEYCODE_") ? name.substring("KEYCODE_".length()) : name;
    }

    private void beginCapture(HostControlAction action, View row) {
        if (capturingRow != null) {
            capturingRow.setBackgroundColor(Color.TRANSPARENT);
        }
        capturing = action;
        capturingRow = row;
        row.setBackgroundColor(0x403080FF);
        updateHeader(action);
    }

    private void endCapture() {
        if (capturingRow != null) {
            capturingRow.setBackgroundColor(Color.TRANSPARENT);
        }
        capturing = null;
        capturingRow = null;
        updateHeader(null);
    }

    private void updateHeader(HostControlAction target) {
        if (target != null) {
            headerView.setText(getString(R.string.binding_capturing, target.displayName()));
            headerView.setTextColor(0x66CCFFFF);
        } else {
            headerView.setText(R.string.binding_hint);
            headerView.setTextColor(0xAAFFFFFF);
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private void commitAndFinish() {
        SharedPreferences.Editor editor = prefs.edit();
        editor.clear();
        for (Map.Entry<String, Integer> entry : pending.entrySet()) {
            if (entry.getValue() != null) {
                editor.putInt(entry.getKey(), entry.getValue());
            }
        }
        editor.apply();
        finish();
    }

    private void refreshBindings() {
        for (HostControlAction action : HostControlAction.values()) {
            TextView view = bindingViews.get(action.id());
            if (view != null) {
                view.setText(bindingLabel(action));
            }
        }
    }

    private void bindKey(HostControlAction action, int keyCode) {
        // One physical key drives exactly one action: drop stale duplicates first.
        for (String key : new ArrayList<>(pending.keySet())) {
            if (pending.get(key) != null && pending.get(key) == keyCode) {
                pending.remove(key);
            }
        }
        pending.put(action.id(), keyCode);
        refreshBindings();
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        int keyCode = event.getKeyCode();
        boolean down = event.getAction() == android.view.KeyEvent.ACTION_DOWN;
        if (down && event.getRepeatCount() == 0) {
            if (capturing != null) {
                if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                    endCapture();
                    swallowBackUp = true;
                } else {
                    bindKey(capturing, keyCode);
                    endCapture();
                }
                return true;
            }
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                finish();
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
        if (capturing != null) {
            return true;
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            if (!down && swallowBackUp) {
                swallowBackUp = false;
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
