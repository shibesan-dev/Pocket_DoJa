package opendoja.android;

import android.os.Handler;
import android.os.Looper;

import com.nttdocomo.ui.Display;
import com.nttdocomo.ui.Frame;

import opendoja.host.DesktopKeyInputAdapter;
import opendoja.host.DoJaRuntime;
import opendoja.host.OpenDoJaLog;

/**
 * Bridges Android touch/key input into the DoJa runtime using the same
 * DesktopKeyInputAdapter press/release machinery the desktop host uses
 * (debounce, dedup, select latch all preserved).
 */
public final class JamInputController {
    private static final String TAG = "OpenDoJa";
    private static final int RELEASE_DEBOUNCE_MS = 50;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final DesktopKeyInputAdapter keyAdapter;
    private final DesktopKeyInputAdapter softAdapter;
    private int logCount;

    private volatile DoJaRuntime runtime;

    public void setRuntime(DoJaRuntime runtime) {
        this.runtime = runtime;
    }

    public JamInputController() {
        keyAdapter = new DesktopKeyInputAdapter(this::schedule,
                (dojaKey, eventType) -> {
                    DoJaRuntime r = runtime;
                    if (r == null) r = DoJaRuntime.current();
                    if (r != null) {
                        r.dispatchSyntheticKey(dojaKey, eventType);
                    }
                },
                RELEASE_DEBOUNCE_MS);
        softAdapter = new DesktopKeyInputAdapter(this::schedule,
                (softKey, eventType) -> {
                    DoJaRuntime r = runtime;
                    if (r == null) r = DoJaRuntime.current();
                    if (r != null) {
                        opendoja.host.OpenDoJaLog.info(JamInputController.class, "soft sink: dispatching softKey=" + softKey + " to runtime=" + r);
                        r.dispatchHostSoftKey(softKey, eventType);
                    } else {
                        opendoja.host.OpenDoJaLog.warn(JamInputController.class, "soft sink: NO runtime, softKey=" + softKey + " dropped");
                    }
                },
                RELEASE_DEBOUNCE_MS);
    }

    public void pressDojaKey(int dojaKey) {
        log(() -> "press key " + dojaKey);
        DoJaRuntime r = runtime;
        if (r == null) r = DoJaRuntime.current();
        if (r == null || r.isShutdown() || opendoja.host.DoJaRuntime.isShuttingDown()) {
            return;
        }
        keyAdapter.keyPressed(dojaKey);
    }

    public void releaseDojaKey(int dojaKey) {
        DoJaRuntime r = runtime;
        if (r == null) r = DoJaRuntime.current();
        if (r == null || r.isShutdown() || opendoja.host.DoJaRuntime.isShuttingDown()) {
            return;
        }
        keyAdapter.keyReleased(dojaKey);
    }

    public void pressSoft(int softKey) {
        log(() -> "press soft " + softKey + " current=" + DoJaRuntime.current() + " stored=" + runtime);
        DoJaRuntime r = runtime;
        if (r == null) r = DoJaRuntime.current();
        if (r == null || r.isShutdown() || opendoja.host.DoJaRuntime.isShuttingDown()) {
            return;
        }
        softAdapter.keyPressed(softKey);
        // Taisen Puyo Puyo 2 DX uses a game-specific key (0x00200000) for its
        // "Stop" softkey during matchmaking. Map the left soft key to it.
        if (softKey == Frame.SOFT_KEY_1) {
            keyAdapter.keyPressed(0x00200000);
        }
    }

    public void releaseSoft(int softKey) {
        DoJaRuntime r = runtime;
        if (r == null) r = DoJaRuntime.current();
        if (r == null || r.isShutdown() || opendoja.host.DoJaRuntime.isShuttingDown()) {
            return;
        }
        softAdapter.keyReleased(softKey);
        if (softKey == Frame.SOFT_KEY_1) {
            keyAdapter.keyReleased(0x00200000);
        }
    }

    public void releaseAll() {
        keyAdapter.releaseAll();
        softAdapter.releaseAll();
    }

    public void clearRuntime() {
        this.runtime = null;
    }

    public void pressPointer(int canvasX, int canvasY) {
        log(() -> "pointer press " + canvasX + "," + canvasY);
        DoJaRuntime runtime = DoJaRuntime.current();
        if (runtime != null) {
            runtime.dispatchPointerEvent(DoJaRuntime.TOUCH_PRESSED_EVENT, canvasX, canvasY);
        }
    }

    public void releasePointer(int canvasX, int canvasY) {
        log(() -> "pointer release " + canvasX + "," + canvasY);
        DoJaRuntime runtime = DoJaRuntime.current();
        if (runtime != null) {
            runtime.dispatchPointerEvent(DoJaRuntime.TOUCH_RELEASED_EVENT, canvasX, canvasY);
        }
    }

    public void movePointer(int canvasX, int canvasY) {
        DoJaRuntime runtime = DoJaRuntime.current();
        if (runtime != null) {
            runtime.dispatchPointerEvent(Display.POINTER_MOVED_EVENT, canvasX, canvasY);
        }
    }

    private void log(java.util.function.Supplier<String> messageSupplier) {
        if (!OpenDoJaLog.isInfoEnabled()) {
            logCount++;
            return;
        }
        if (logCount < 200 || logCount % 100 == 0) {
            OpenDoJaLog.info(JamInputController.class, () -> "input#" + logCount + " " + messageSupplier.get());
        }
        logCount++;
    }

    private DesktopKeyInputAdapter.PendingRelease schedule(int delayMillis, Runnable task) {
        handler.postDelayed(task, delayMillis);
        return () -> handler.removeCallbacks(task);
    }
}
