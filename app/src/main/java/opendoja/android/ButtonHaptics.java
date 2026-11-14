package opendoja.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import opendoja.host.device.DoJaVibrationSupport;

/**
 * Haptic feedback.
 */
final class ButtonHaptics {
    /** SharedPreferences key for the 0-100 touch-haptic intensity. */
    static final String PREF_KEY = "hapticIntensity";
    static final int DEFAULT_INTENSITY = 15;

    // Use a wide duration range. Some vibration motors ignore amplitude
    // changes (or only support coarse steps), so duration is the one part of
    // the effect that reliably makes the slider feel different.
    private static final int MIN_TICK_MS = 20;
    private static final int MAX_TICK_MS = 180;
    // Keep a small amplitude floor so 1% is still a tick rather than a no-op,
    // but leave enough range for low settings to feel genuinely lighter.
    private static final int MIN_AMPLITUDE = 24;
    /** Avoids buzzing continuously while a finger slides across the keypad. */
    private static final long MIN_INTERVAL_MS = 30L;

    private static Vibrator vibrator;
    private static long lastTickMs;

    private ButtonHaptics() {
    }

    /** Reads the configured intensity (0 = off, 100 = strongest). */
    static int intensity(Context context) {
        if (context == null) {
            return DEFAULT_INTENSITY;
        }
        SharedPreferences prefs = context.getSharedPreferences(
                DisplayActivity.PREFS_NAME, Context.MODE_PRIVATE);
        return clamp(prefs.getInt(PREF_KEY, DEFAULT_INTENSITY), 0, 100);
    }

    /** Plays one tick at the configured intensity. */
    static void tick(Context context) {
        tick(context, intensity(context));
    }

    /** Plays one tick at an explicit intensity (used by the settings preview). */
    static void tick(Context context, int intensityPercent) {
        if (context == null) {
            return;
        }
        int percent = clamp(intensityPercent, 0, 100);
        if (percent == 0) {
            return;
        }
        // A game-requested vibration owns the motor. Replacing it with a tick
        // would stop the title's vibration until it sends its next ON request.
        try {
            if (DoJaVibrationSupport.isActive()) {
                return;
            }
        } catch (Throwable ignored) {
            // Host helper unavailable (desktop build): still allow UI ticks.
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastTickMs < MIN_INTERVAL_MS) {
            return;
        }
        lastTickMs = now;

        Vibrator device = vibrator(context);
        if (device == null) {
            return;
        }
        int durationMs = MIN_TICK_MS
                + Math.round((MAX_TICK_MS - MIN_TICK_MS) * percent / 100f);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (device.hasAmplitudeControl()) {
                    // Slightly convex curve: the midpoint feels closer to half
                    // strength on linear-resonant actuators instead of nearly off.
                    float t = percent / 100f;
                    int amplitude = Math.round(MIN_AMPLITUDE
                            + (255f - MIN_AMPLITUDE) * (float) Math.pow(t, 0.6));
                    device.vibrate(VibrationEffect.createOneShot(durationMs, amplitude));
                } else {
                    device.vibrate(VibrationEffect.createOneShot(durationMs,
                            VibrationEffect.DEFAULT_AMPLITUDE));
                }
            } else {
                device.vibrate(durationMs);
            }
        } catch (Throwable ignored) {
            // Haptics are best-effort; never break input.
        }
    }

    private static Vibrator vibrator(Context context) {
        if (vibrator != null) {
            return vibrator;
        }
        try {
            Context app = context.getApplicationContext();
            Context resolved = app != null ? app : context;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager manager = (VibratorManager) resolved.getSystemService(
                        Context.VIBRATOR_MANAGER_SERVICE);
                vibrator = manager != null ? manager.getDefaultVibrator() : null;
            } else {
                vibrator = (Vibrator) resolved.getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (vibrator != null && !vibrator.hasVibrator()) {
                vibrator = null;
            }
        } catch (Throwable ignored) {
            vibrator = null;
        }
        return vibrator;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
