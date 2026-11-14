package opendoja.host.device;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/**
 * Host support for the DoJa vibrator native resource
 * ({@link com.nttdocomo.ui.PhoneSystem#DEV_VIBRATOR}).
 *
 * <p>DoJa switches the vibrator on and off rather than requesting a fixed
 * duration, so a repeating waveform stands in for the "on" state and the
 * "off" request cancels it. Games often turn it on and off again within a few
 * milliseconds; the vibration is kept alive for a minimum pulse so the motor
 * has time to spin up. A safety cutoff stops a title that forgets to switch it
 * off, and the host cancels it when the game is hibernated or closed.</p>
 */
public final class DoJaVibrationSupport {
    /** Shortest visible vibration even if the game requests OFF immediately. */
    private static final long MIN_PULSE_MILLIS = 250L;
    /** Safety cutoff for a game that leaves the vibrator on. */
    private static final long MAX_PULSE_MILLIS = 3_000L;
    /** Buzz for 300 ms, rest for 100 ms, then repeat. */
    private static final long[] PULSE_PATTERN = {0L, 300L, 100L};

    private static final Object LOCK = new Object();
    private static Context applicationContext;
    private static Handler handler;
    private static Vibrator vibrator;
    private static boolean enabled;
    private static boolean paused;
    private static boolean pulseActive;
    private static long pulseStartedAt;
    private static int pulseGeneration;

    private DoJaVibrationSupport() {
    }

    /**
     * Supplies the Android context used to look up the vibrator service.
     * Safe to call repeatedly; only the application context is retained.
     */
    public static void initialize(Context context) {
        if (context == null) {
            return;
        }
        synchronized (LOCK) {
            Context app = context.getApplicationContext();
            Context resolved = app != null ? app : context;
            if (resolved != applicationContext) {
                applicationContext = resolved;
                vibrator = null;
            }
        }
    }

    /** Returns whether this device exposes a usable vibrator. */
    public static boolean isAvailable() {
        synchronized (LOCK) {
            return resolveVibratorLocked() != null;
        }
    }

    /**
     * Handles a DoJa vibrator attribute change.
     * {@code true} switches the motor on until {@code false} (or the safety
     * cutoff) stops it.
     */
    public static void setEnabled(boolean on) {
        synchronized (LOCK) {
            enabled = on;
            if (!on) {
                stopLocked(true);
                return;
            }
            if (!paused) {
                startLocked();
            }
        }
    }

    /**
     * Returns whether the game currently has the vibrator switched on.
     * The host uses this to avoid replacing a running game vibration with a
     * short UI haptic tick.
     */
    public static boolean isActive() {
        synchronized (LOCK) {
            return enabled && !paused;
        }
    }

    /** Stops any vibration and clears the logical state (game teardown). */
    public static void cancel() {
        synchronized (LOCK) {
            enabled = false;
            paused = false;
            cancelHardwareLocked();
        }
    }

    /** Stops the motor while the game is hibernated. */
    public static void pause() {
        synchronized (LOCK) {
            paused = true;
            cancelHardwareLocked();
        }
    }

    /** Restores a vibration that was still requested when the game hibernated. */
    public static void resume() {
        synchronized (LOCK) {
            paused = false;
            if (enabled) {
                startLocked();
            }
        }
    }

    private static void startLocked() {
        Vibrator device = resolveVibratorLocked();
        if (device == null) {
            return;
        }
        if (pulseActive) {
            // Already buzzing. Invalidate a pending minimum-pulse stop and
            // refresh the safety cutoff.
            int generation = ++pulseGeneration;
            scheduleStopLocked(generation,
                    Math.max(0L, MAX_PULSE_MILLIS - (SystemClock.elapsedRealtime() - pulseStartedAt)));
            return;
        }

        pulseActive = true;
        pulseStartedAt = SystemClock.elapsedRealtime();
        int generation = ++pulseGeneration;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                device.vibrate(VibrationEffect.createWaveform(PULSE_PATTERN, 0));
            } else {
                device.vibrate(PULSE_PATTERN, 0);
            }
        } catch (Throwable ignored) {
            pulseActive = false;
            return;
        }
        scheduleStopLocked(generation, MAX_PULSE_MILLIS);
    }

    private static void stopLocked(boolean honorMinimumPulse) {
        if (!pulseActive) {
            cancelHardwareLocked();
            return;
        }
        long elapsed = SystemClock.elapsedRealtime() - pulseStartedAt;
        if (honorMinimumPulse && elapsed < MIN_PULSE_MILLIS) {
            int generation = ++pulseGeneration;
            scheduleStopLocked(generation, MIN_PULSE_MILLIS - elapsed);
            return;
        }
        cancelHardwareLocked();
    }

    private static void scheduleStopLocked(int generation, long delayMillis) {
        Handler target = handlerLocked();
        if (target == null) {
            cancelHardwareLocked();
            return;
        }
        target.postDelayed(() -> {
            synchronized (LOCK) {
                if (generation != pulseGeneration) {
                    return;
                }
                cancelHardwareLocked();
            }
        }, Math.max(0L, delayMillis));
    }

    private static void cancelHardwareLocked() {
        pulseGeneration++;
        pulseActive = false;
        Vibrator device = vibrator;
        if (device != null) {
            try {
                device.cancel();
            } catch (Throwable ignored) {
            }
        }
    }

    private static Handler handlerLocked() {
        if (handler == null) {
            try {
                Looper looper = Looper.getMainLooper();
                if (looper != null) {
                    handler = new Handler(looper);
                }
            } catch (Throwable ignored) {
            }
        }
        return handler;
    }

    private static Vibrator resolveVibratorLocked() {
        if (vibrator != null) {
            return vibrator;
        }
        Context context = applicationContext;
        if (context == null) {
            return null;
        }
        try {
            Vibrator resolved;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager manager =
                        (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                resolved = manager != null ? manager.getDefaultVibrator() : null;
            } else {
                resolved = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (resolved == null || !resolved.hasVibrator()) {
                return null;
            }
            vibrator = resolved;
            return resolved;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
