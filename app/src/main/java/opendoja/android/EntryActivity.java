package opendoja.android;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import com.nttdocomo.ui.IApplication;

import java.io.File;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import opendoja.host.DesktopLauncher;
import opendoja.host.OpenDoJaLaunchArgs;
import opendoja.host.DoJaRuntime;
import opendoja.host.JamGameClassLoaderFactory;
import opendoja.host.JamLauncher;
import opendoja.host.LaunchConfig;
import opendoja.host.OpenDoJaLog;

public class EntryActivity extends Activity {
    // Threads owned by the running game/runtime. When the user force-exits with BACK their
    // uncaught exceptions must not tear down the whole process - the user expects to land on
    // the title screen, not on an "app crashed" dialog.
    private static final Set<Thread> doomedThreads = ConcurrentHashMap.newKeySet();
    private static volatile boolean exiting;
    private static boolean crashGuardInstalled;
    private static Thread.UncaughtExceptionHandler previousCrashHandler;

    private JamSurfaceView surfaceView;
    private JamInputController input;
    private volatile boolean loadingHidden = false;
    private volatile boolean firstFramePresented = false;
    private android.os.Handler loadingHandler;
    // Per-game process isolation: set when this :game process is being torn down so the
    // delayed killer knows to still exit (and a reused process can cancel it).
    private static volatile boolean processTerminating = false;
    private int loadingDot = 0;
    private int exitingDot = 0;
    private final Runnable exitingTick = new Runnable() {
        @Override
        public void run() {
            StringBuilder sb = new StringBuilder("Exiting");
            for (int i = 0; i < exitingDot; i++) sb.append('.');
            surfaceView.showExitingOverlay(sb.toString());
            exitingDot = (exitingDot + 1) % 4;
            if (loadingHandler != null) loadingHandler.postDelayed(this, 400);
        }
    };
    private final Runnable loadingTick = new Runnable() {
        @Override
        public void run() {
            StringBuilder sb = new StringBuilder("Booting");
            for (int i = 0; i < loadingDot; i++) sb.append('.');
            surfaceView.showLoadingOverlay(sb.toString());
            loadingDot = (loadingDot + 1) % 4;
            if (loadingHandler != null) loadingHandler.postDelayed(this, 400);
        }
    };
    // Static: a system-driven activity recreation must not spawn a second
    // jam-launch game loop over the one that is already running (two loops on
    // one process corrupt the game's shared static state). reset in stopGame()
    // so the next manual launch starts a fresh thread.
    private static volatile boolean launched;
private static volatile Thread gameThread;
    private static volatile int activeLaunchSession = 0;
    private static final ThreadLocal<Integer> threadSession = new ThreadLocal<>();
    private static volatile DoJaRuntime expectedRuntime;
    private static volatile int sessionId = 0;
    private static volatile int activeSessionId = 0;
    public static String gameId;
    private static ClassLoader gameClassLoader;
    private static volatile Thread threadToSkip;
    private static volatile DoJaRuntime gameRuntime;
    private static volatile int presentedFrameCount = 0;
    public static volatile boolean hasUserInteracted = false;
    public static volatile boolean gameReady = false;
    private static volatile Thread exitThread;
    private volatile boolean gameStopped;
    private android.app.AlertDialog gameMenu;
    private static final java.util.concurrent.CountDownLatch exitLatch = new java.util.concurrent.CountDownLatch(1);
    private static volatile boolean exitInProgress = false;
    // Audio-handle generation of the game launched by this activity; exit
    // cleanup may only release handles up to this generation.
    private volatile long audioGeneration;
    // True while the game is hibernating in the background (HOME / task switch /
    // screen lock). The emulator state stays in memory; rendering, timers, and
    // audio park in place until the user returns and presses any key.
    private volatile boolean suspended;

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
        // Apply the requested orientation as early as possible (before the window
        // is created) so the activity does not flash in portrait first.
        try {
            String earlyJamPath = getIntent() != null ? getIntent().getStringExtra("jamPath") : null;
            String earlyGameId = earlyJamPath;
            if (earlyGameId != null) {
                java.io.File f = new java.io.File(earlyGameId);
                earlyGameId = f.getName();
            }
            HomeActivity.applyDisplayOrientation(this, earlyGameId);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // If Android reused this :game process for a new game, cancel any pending exit.
        processTerminating = false;
        String gameId = getIntent() != null ? getIntent().getStringExtra("jamPath") : null;
        if (gameId != null) {
            java.io.File f = new java.io.File(gameId);
            gameId = f.getName();
        }
        EntryActivity.gameId = gameId;
        opendoja.host.OpenDoJaLog.info(EntryActivity.class, "EntryActivity.onCreate: gameId=" + gameId);
        String platformOverride = getIntent() != null ? getIntent().getStringExtra("microeditionPlatform") : null;
        if (platformOverride != null && !platformOverride.isBlank()) {
            System.setProperty(OpenDoJaLaunchArgs.MICROEDITION_PLATFORM_OVERRIDE, platformOverride);
        }
        HomeActivity.applyDisplayOrientation(this, gameId);
        OpenDoJaLog.configure(OpenDoJaLog.Level.OFF);
        DoJaRuntime.setGlobalShutdownCallback(runtime -> {
            if (runtime.sessionId() != activeSessionId) {
                opendoja.host.OpenDoJaLog.info(EntryActivity.class, "shutdown callback ignored: session=" + runtime.sessionId() + " active=" + activeSessionId);
                return;
            }
        try {
            opendoja.host.DoJaRuntime.markShutdown();
        } catch (Throwable ignored) {
        }
        try {
            com.nttdocomo.ui.AudioPresenter.setAudioEnabled(false);
            com.nttdocomo.ui.AudioPresenter.bumpAudioGeneration();
        } catch (Throwable ignored) {
        }
            try {
                if (input != null) {
                    input.releaseAll();
                    input.clearRuntime();
                }
            } catch (Throwable ignored) {
            }
            try {
                releaseGameAudio(Long.MAX_VALUE);
                com.nttdocomo.ui.AudioPresenter.releaseAllSessions();
                com.nttdocomo.ui.AudioPresenter.releaseAll();
                opendoja.audio.mld.MLDPCMPlayer.releaseAll();
                opendoja.audio.mld.MLDPCMPlayer.releaseGenerationsUpTo(Long.MAX_VALUE);
            } catch (Throwable ignored) {
            }
            runOnUiThread(this::exitGame);
        });
        DoJaRuntime.setPlatformPresenter((frame, sessionId) -> {
            if (frame == null) {
                runOnUiThread(this::exitGame);
                return;
            }
            surfaceView.present(frame, sessionId);
        });
        // The default artifact-derived host root points inside the read-only APK
        // directory; SD-card storage init would fail there and titles like the
        // Touhou "reading SD" screen would wait forever. Keep it in app-private
        // storage instead.
        opendoja.host.OpenDoJaPaths.setHostDataRoot(java.nio.file.Paths.get(getFilesDir().getAbsolutePath()).resolve(".opendoja"));
        installCrashGuard();
        DoJaRuntime.setPlatformPresenter((frame, sessionId) -> {
            if (frame == null) {
                runOnUiThread(this::exitGame);
                return;
            }
            surfaceView.present(frame, sessionId);
        });
        exiting = false;
        input = new JamInputController();
        surfaceView = new JamSurfaceView(this, input);
        surfaceView.setFocusable(false);
        surfaceView.setFocusableInTouchMode(false);
        // Restore per-game hide-keypad preference.
        android.content.SharedPreferences hkPrefs = getSharedPreferences(DisplayActivity.PREFS_NAME, MODE_PRIVATE);
        if (gameId != null && !gameId.isEmpty()) {
            boolean hidden = hkPrefs.getBoolean(gameId + "_" + DisplayActivity.KEY_HIDE_KEYPAD, false);
            surfaceView.setKeypadVisible(!hidden);
        }
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        root.addView(surfaceView, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        loadingHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        loadingHandler.post(loadingTick);
        firstFramePresented = false;
        startBootStallWatchdog();
        // Direct-draw games paint dozens of primitives per frame without lock()/unlock();
        // coalescing their outside-lock presents keeps half-drawn frames off the screen.
        DoJaRuntime.setPresentDebounceEnabled(false);
        if (launched && (gameThread == null || !gameThread.isAlive())) {
            launched = false;
        }
        if (!launched) {
            launched = true;
            boolean smoke = getIntent() != null && getIntent().getBooleanExtra("smoke", false);
            Thread launchThread = new Thread(smoke ? this::launchSmoke : this::launchGame, "jam-launch");
            gameThread = launchThread;
            launchThread.setDaemon(true);
            launchThread.start();
        }
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (suspended && !isFinishing()) {
            // "-Paused- Press any key to continue": the first key press resumes
            // the game; it is consumed so the game never sees a stale press.
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                resumeGame();
            }
            return true;
        }
        if (event.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK) {
            // The system back key opens the in-game menu (hide keypad / exit game);
            // it is never routed into the game (the on-screen keypad keeps SOFT2
            // available for the game itself).
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN
                    && event.getRepeatCount() == 0) {
                showGameMenu();
            }
            return true;
        }
        opendoja.host.HostControlAction mapped = mappedAction(event.getKeyCode());
        if (mapped != null) {
            if (mapped == opendoja.host.HostControlAction.EXIT_GAME) {
                if (event.getAction() == android.view.KeyEvent.ACTION_DOWN
                        && event.getRepeatCount() == 0) {
                    exitGame();
                }
                return true;
            }
            if (mapped == opendoja.host.HostControlAction.SOFT_KEY_LEFT) {
                routeAction(mapped, event.getAction() == android.view.KeyEvent.ACTION_DOWN);
                if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        if (!isFinishing()) {
                            surfaceView.clearScreen();
                        }
                    }, 500);
                }
                return true;
            }
            routeAction(mapped, event.getAction() == android.view.KeyEvent.ACTION_DOWN);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /** BACK menu: toggle the on-screen keypad, or quit the game back to HomeActivity. */
    private void showGameMenu() {
        if (gameMenu != null && gameMenu.isShowing()) {
            return;
        }
        if (surfaceView == null) {
            return;
        }
        boolean keypadShown = surfaceView.isKeypadVisible();

        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);
        int btnPadH = (int) (12 * density);
        int btnPadV = (int) (10 * density);
        int corner = (int) (8 * density);
        int minHeight = (int) (48 * density);

        android.graphics.drawable.GradientDrawable normalBg = new android.graphics.drawable.GradientDrawable();
        normalBg.setColor(0xFF3A3A3A);
        normalBg.setCornerRadius(corner);
        android.graphics.drawable.GradientDrawable pressedBg = new android.graphics.drawable.GradientDrawable();
        pressedBg.setColor(0xFF5A5A5A);
        pressedBg.setCornerRadius(corner);

        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        android.widget.CheckBox hideCheck = new android.widget.CheckBox(this);
        hideCheck.setText(R.string.hide_keypad);
        hideCheck.setChecked(!keypadShown);
        hideCheck.setTextSize(16f);
        hideCheck.setTypeface(null, android.graphics.Typeface.BOLD);
        hideCheck.setGravity(android.view.Gravity.CENTER_VERTICAL);
        hideCheck.setMinHeight(minHeight);
        hideCheck.setPadding(btnPadH, btnPadV, btnPadH, btnPadV);
        hideCheck.setBackground(normalBg);
        hideCheck.setOnTouchListener((v, e) -> {
            if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) v.setBackground(pressedBg);
            else if (e.getAction() == android.view.MotionEvent.ACTION_UP || e.getAction() == android.view.MotionEvent.ACTION_CANCEL) v.setBackground(normalBg);
            return false;
        });
        hideCheck.setOnCheckedChangeListener((cb, isChecked) -> {
            surfaceView.setKeypadVisible(!isChecked);
            persistHideKeypad(isChecked);
        });
        root.addView(hideCheck);

        android.graphics.drawable.GradientDrawable exitNormalBg = new android.graphics.drawable.GradientDrawable();
        exitNormalBg.setColor(0xFF3A3A3A);
        exitNormalBg.setCornerRadius(corner);
        android.graphics.drawable.GradientDrawable exitPressedBg = new android.graphics.drawable.GradientDrawable();
        exitPressedBg.setColor(0xFF5A5A5A);
        exitPressedBg.setCornerRadius(corner);

        android.widget.TextView exit = new android.widget.TextView(this);
        exit.setText(R.string.exit_game);
        exit.setTextSize(16f);
        exit.setTypeface(null, android.graphics.Typeface.BOLD);
        exit.setGravity(android.view.Gravity.CENTER);
        exit.setMinHeight(minHeight);
        exit.setPadding(btnPadH, btnPadV, btnPadH, btnPadV);
        exit.setBackground(exitNormalBg);
        exit.setOnTouchListener((v, e) -> {
            if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) v.setBackground(exitPressedBg);
            else if (e.getAction() == android.view.MotionEvent.ACTION_UP || e.getAction() == android.view.MotionEvent.ACTION_CANCEL) v.setBackground(exitNormalBg);
            return false;
        });
        exit.setOnClickListener(v -> {
            if (gameMenu != null) gameMenu.dismiss();
            exitGame();
        });
android.widget.LinearLayout.LayoutParams exitLp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        exitLp.topMargin = pad / 2;
        root.addView(exit, exitLp);

        gameMenu = new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.game_menu)
                .setView(root)
                .setOnDismissListener(dialog -> gameMenu = null)
                .create();
        gameMenu.show();
    }

    private void persistHideKeypad(boolean hidden) {
        android.content.SharedPreferences prefs = getSharedPreferences(DisplayActivity.PREFS_NAME, MODE_PRIVATE);
        String gid = EntryActivity.gameId;
        if (gid == null || gid.isEmpty()) return;
        prefs.edit().putBoolean(gid + "_" + DisplayActivity.KEY_HIDE_KEYPAD, hidden).commit();
    }

    private void exitGame() {
        opendoja.host.OpenDoJaLog.info(EntryActivity.class, "exitGame called");
        HomeActivity.gameRunning = false;
        try {
            opendoja.host.DoJaRuntime.markShutdown();
        } catch (Throwable ignored) {
        }
        try {
            if (input != null) {
                input.releaseAll();
                input.clearRuntime();
            }
        } catch (Throwable ignored) {
        }
        HomeActivity.bgmSuppressed = false;
        if (surfaceView != null) {
            surfaceView.clearScreen();
            try {
                surfaceView.releaseAllControls();
            } catch (Throwable ignored) {
            }
        }
        showExitingOverlay();
        // Run stopGame synchronously to ensure full cleanup before next launch
        Thread t = new Thread(() -> {
            try {
                stopGame();
            } finally {
                exitThread = null;
            }
            runOnUiThread(() -> {
                if (!isFinishing()) {
                    finish();
                }
            });
        }, "jam-exit");
        exitThread = t;
        t.setDaemon(true);
        t.start();
// Wait for exit, prevent race condition!
        try {
            t.join(3000L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    public void hideLoadingOverlay() {
        firstFramePresented = true;
        if (loadingHidden) return;
        loadingHidden = true;
        runOnUiThread(() -> {
            if (surfaceView != null) surfaceView.hideLoadingOverlay();
            if (loadingHandler != null) loadingHandler.removeCallbacksAndMessages(null);
        });
    }

    /**
     * If the title never presents its first frame (the intermittent "Booting..." stall), dump
     * every thread's stack to a file so the hang can be diagnosed without adb.
     */
    private void startBootStallWatchdog() {
        new Thread(() -> {
            try {
                // Long enough that a legitimately slow first-time dex conversion (large titles
                // can exceed 10s) is not misreported as a stall.
                Thread.sleep(25000L);
            } catch (InterruptedException ignored) {
                return;
            }
            if (firstFramePresented || isFinishing() || gameStopped) {
                return;
            }
            try {
                StringBuilder sb = new StringBuilder();
                sb.append("PocketDoJa boot stall\n");
                sb.append("time: ").append(new java.util.Date()).append('\n');
                sb.append("game: ").append(gameId).append('\n');
                for (java.util.Map.Entry<Thread, StackTraceElement[]> entry
                        : Thread.getAllStackTraces().entrySet()) {
                    Thread thread = entry.getKey();
                    sb.append("\n\"").append(thread.getName()).append("\" id=").append(thread.getId())
                            .append(" state=").append(thread.getState()).append('\n');
                    for (StackTraceElement frame : entry.getValue()) {
                        sb.append("  at ").append(frame).append('\n');
                    }
                }
                java.io.File out = writeDiagnosticsFile("pocketdoja-boot-stall.txt", sb.toString());
                opendoja.host.OpenDoJaLog.warn(EntryActivity.class, "boot stall dump -> " + out);
            } catch (Throwable ignored) {
            }
        }, "opendoja-boot-watchdog").start();
    }

    private java.io.File writeDiagnosticsFile(String name, String content) {
        java.io.File[] candidates = {
                new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS), name),
                new java.io.File(getExternalFilesDir(null), name),
                new java.io.File(getFilesDir(), name)
        };
        for (java.io.File candidate : candidates) {
            try {
                java.io.File parent = candidate.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                try (java.io.FileWriter writer = new java.io.FileWriter(candidate, false)) {
                    writer.write(content);
                }
                return candidate;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private void showExitingOverlay() {
        runOnUiThread(() -> {
            if (surfaceView != null) surfaceView.showExitingOverlay("Exiting");
            exitingDot = 0;
            if (loadingHandler != null) {
                loadingHandler.removeCallbacks(exitingTick);
                loadingHandler.post(exitingTick);
            }
        });
    }

    private void forceExitToTitle() {
        if (surfaceView != null) {
            surfaceView.clearScreen();
        }
        stopGame();
        android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (am != null) {
            for (android.app.ActivityManager.AppTask task : am.getAppTasks()) {
                try {
                    task.finishAndRemoveTask();
                } catch (Exception ignored) {
                }
            }
        }
        finishAffinity();
        if (!isFinishing()) {
            finish();
        }
        System.exit(0);
    }

    /** Kills the running game: wakes input waiters, interrupts game threads, shuts the
     *  runtime down without exiting the process, so the title screen stays alive. */
    private void stopGame() {
        opendoja.host.OpenDoJaLog.info(EntryActivity.class, "stopGame called, thread=" + Thread.currentThread().getName() + " session=" + sessionId);
        if (gameStopped) {
            opendoja.host.OpenDoJaLog.info(EntryActivity.class, "stopGame: already stopped");
            return;
        }
        // Un-park hibernated threads first so the sweep below can stop them.
        suspended = false;
        try {
            opendoja.host.DoJaRuntime.resumeGlobal();
        } catch (Throwable ignored) {
        }
        try {
            com.nttdocomo.ui.AudioPresenter.resumeAllActive();
        } catch (Throwable ignored) {
        }
        gameStopped = true;
        exiting = true;
        launched = false;
        // The game is gone; let the title screen bring its own BGM back even on
        // exit paths that skip exitGame() (e.g. HOME while the game is booting).
        HomeActivity.gameRunning = false;
        HomeActivity.bgmSuppressed = false;
        final Thread currentGameThread = gameThread;  // Save before nulling so doomGameThreads() won't interrupt it
        gameThread = null;
        final DoJaRuntime runtimeToShutdown = gameRuntime;  // Save before nulling for shutdown path
        gameRuntime = null;
        gameReady = false;
        presentedFrameCount = 0;
        hasUserInteracted = false;
        opendoja.host.OpenDoJaLog.info(EntryActivity.class, "stopGame: runtime=" + DoJaRuntime.current()
                + " gameRuntime=" + gameRuntime
                + " audioGeneration=" + audioGeneration);
        if (surfaceView != null) {
            surfaceView.releaseAllControls();
        }
        if (input != null) {
            try {
                input.releaseAll();
            } catch (Throwable ignored) {
            }
            input.clearRuntime();
        }
// Immediate sweep: stale BGM stops right away, even before the async
        // Aggressively kill input dispatches
        try {
            if (input != null) {
                input.releaseAll();
                input.clearRuntime();
            }
        } catch (Throwable ignored) {
        }
        try {
            opendoja.host.DoJaRuntime.markShutdown();
        } catch (Throwable ignored) {
        }
        try {
            opendoja.host.DoJaRuntime.setPlatformPresenter(null);
            opendoja.host.DoJaRuntime.setPresentDebounceEnabled(false);
        } catch (Throwable ignored) {
        }
        if (surfaceView != null) {
            surfaceView.clearScreen();
        }
        releaseGameAudio(Long.MAX_VALUE);
        try {
            com.nttdocomo.ui.AudioPresenter.releaseAllSessions();
            com.nttdocomo.ui.AudioPresenter.releaseAll();
        } catch (Throwable ignored) {
        }
        try {
            opendoja.audio.mld.MLDPCMPlayer.releaseAll();
            opendoja.audio.mld.MLDPCMPlayer.releaseGenerationsUpTo(Long.MAX_VALUE);
        } catch (Throwable ignored) {
        }
        threadToSkip = currentGameThread;
        doomGameThreads();
        threadToSkip = null;
        DoJaRuntime runtime = DoJaRuntime.current();
        if (runtime == null) {
            runtime = DoJaRuntime.lastRuntime();
        }
        if (runtime == null) {
            runtime = runtimeToShutdown;
        }
        final DoJaRuntime shutdownRuntime = runtime;
        if (shutdownRuntime != null) {
            // Shutdown on a helper thread so a hung game can never block the exit itself.
            Thread shutdownThread = new Thread(() -> {
                try {
                    // exitOnShutdown is false for jam launches, so this never System.exit()s.
                    shutdownRuntime.shutdown();
                } catch (opendoja.host.GameTerminatedException normalExit) {
                    // shutdown() uses this Throwable to unwind the application thread. In a
                    // host-initiated stop there is no app frame to unwind, so it is expected.
                } catch (Throwable throwable) {
                    OpenDoJaLog.error(EntryActivity.class, "runtime shutdown failed", throwable);
                } finally {
                    // Catch handles opened by zombie game threads while the
                    // shutdown was still in flight.
                    try {
                        if (input != null) {
                            input.releaseAll();
                            input.clearRuntime();
                        }
                    } catch (Throwable ignored) {
                    }
                    try {
                        opendoja.host.DoJaRuntime.markShutdown();
                    } catch (Throwable ignored) {
                    }
                    releaseGameAudio(Long.MAX_VALUE);
        try {
            com.nttdocomo.ui.AudioPresenter.releaseStaleSessions();
            com.nttdocomo.ui.AudioPresenter.releaseAll();
        } catch (Throwable ignored) {
        }
                    try {
                        opendoja.audio.mld.MLDPCMPlayer.releaseAll();
                        opendoja.audio.mld.MLDPCMPlayer.releaseGenerationsUpTo(Long.MAX_VALUE);
                    } catch (Throwable ignored) {
                    }
                    opendoja.host.OpenDoJaLog.info(EntryActivity.class, "runtime shutdown done, audio released");
                }
            }, "jam-shutdown");
            shutdownThread.setDaemon(true);
            shutdownThread.start();
            try {
                shutdownThread.join(2000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        // Last resort: doomGameThreads() deliberately exempts "jam-launch" (the game
        // loop) and the runtime shutdown above is a no-op once a title has already
        // shut the runtime down itself (e.g. it called IApplication.terminate() but
        // swallowed the unwind). A surviving game loop would then contend with the
        // next title over shared emulator state, so make sure this session's loop is
        // actually gone before stopGame returns.
        if (currentGameThread != null && currentGameThread != Thread.currentThread() && currentGameThread.isAlive()) {
            OpenDoJaLog.warn(EntryActivity.class,
                    "stopGame: game thread alive after shutdown; forcing stop thread=" + currentGameThread.getName());
            currentGameThread.interrupt();
            try {
                currentGameThread.join(300L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            if (currentGameThread.isAlive()) {
                try {
                    currentGameThread.stop();
                } catch (Throwable ignored) {
                }
            }
        }
        // Drop every static reference that pinned this session so the dead threads, the
        // game's classloader, and the shut-down runtime (with its game class) can all be
        // garbage-collected. Otherwise a long session accumulates one of each per game.
        doomedThreads.clear();
        try {
            JamDexHost.releaseLoader(gameClassLoader);
        } catch (Throwable ignored) {
        }
        gameClassLoader = null;
        expectedRuntime = null;
    }

    private static void releaseGameAudio(long generation) {
        try {
            opendoja.audio.mld.MLDPCMPlayer.releaseGenerationsUpTo(generation);
        } catch (Throwable throwable) {
            opendoja.host.OpenDoJaLog.error(EntryActivity.class, "releaseGameAudio failed", throwable);
        }
        try {
            opendoja.audio.mld.MLDPCMPlayer.releaseAll();
        } catch (Throwable throwable) {
            opendoja.host.OpenDoJaLog.error(EntryActivity.class, "releaseAll MLD failed", throwable);
        }
    }

    private static void doomGameThreads() {
        Thread self = Thread.currentThread();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread == self || isSystemThread(thread) || thread == threadToSkip) {
                continue;
            }
            doomedThreads.add(thread);
            thread.interrupt();
            // Forcefully stop zombie game threads (including sound threads such as
            // "Thread-4") so they cannot keep playing stale audio after exit.
            try {
                thread.stop();
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean isSystemThread(Thread thread) {
        String name = thread.getName();
        if (name == null) {
            return false;
        }
        return name.equals("main")
                || name.startsWith("Finalizer")
                || name.startsWith("HeapTask")
                || name.startsWith("ReferenceQueue")
                || name.startsWith("Signal Catcher")
                || name.startsWith("ADB-JDWP")
                || name.startsWith("Profile Saver")
                || name.startsWith("Jit thread")
                || name.startsWith("Binder:")
                || name.startsWith("RenderThread")
                || name.startsWith("hwuiTask")
                // Shared infrastructure that outlives a single game
                || name.startsWith("opendoja-mld")
                || name.startsWith("opendoja-midi")
                || name.startsWith("openDoJa-media-events")
                || name.startsWith("opendoja-sampled-pcm")
                || name.startsWith("openDoJa-")
                || name.equals("MainEventDispatchThread")
                || name.startsWith("pool-") // keep some thread pools alive if shared
                || name.startsWith("Timer-");
    }

    private static synchronized void installCrashGuard() {
        if (crashGuardInstalled) {
            return;
        }
        crashGuardInstalled = true;
        previousCrashHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            if (doomedThreads.contains(thread) || (exiting && looksLikeGameThread(thread))) {
                opendoja.host.OpenDoJaLog.warn(EntryActivity.class, "ignored game-thread crash after exit: " + thread,
                        throwable);
                return;
            }
            if (previousCrashHandler != null) {
                previousCrashHandler.uncaughtException(thread, throwable);
            }
        });
    }

    private static boolean looksLikeGameThread(Thread thread) {
        String name = thread.getName();
        return name.startsWith("Thread-")
                || name.startsWith("Timer-")
                || name.startsWith("jam-launch")
                || name.startsWith("openDoJa-")
                || name.equals("MainEventDispatchThread")
                || name.startsWith("pool-");
    }

    /**
     * Stops any leftover game-loop thread ("jam-launch") from an earlier session.
     * {@link #doomGameThreads()} treats "jam-launch" as a system thread, and the
     * runtime shutdown is a no-op once a title has already shut the runtime down
     * itself, so a game loop that survived a mid-game exit would otherwise keep
     * running into the next session and contend over shared emulator state.
     */
    private static void reapLingeringLaunchThreads() {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread == Thread.currentThread() || !"jam-launch".equals(thread.getName())) {
                continue;
            }
            OpenDoJaLog.warn(EntryActivity.class,
                    "reapLingeringLaunchThreads: stopping leftover game thread=" + thread.getName());
            thread.interrupt();
            try {
                thread.join(300L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            if (thread.isAlive()) {
                try {
                    thread.stop();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private opendoja.host.HostControlAction mappedAction(int keyCode) {
        String gameId = EntryActivity.gameId;
        // Per-game bindings take precedence over the global keymap.
        opendoja.host.HostControlAction gameAction = mappedActionFromPrefs(
                getSharedPreferences(KeyMappingActivity.prefsNameForGame(gameId), MODE_PRIVATE), keyCode);
        if (gameAction != null) {
            return gameAction;
        }
        return mappedActionFromPrefs(getSharedPreferences(KeyMappingActivity.PREFS_NAME, MODE_PRIVATE), keyCode);
    }

    private opendoja.host.HostControlAction mappedActionFromPrefs(android.content.SharedPreferences prefs, int keyCode) {
        java.util.Map<String, ?> bindings = prefs.getAll();
        for (java.util.Map.Entry<String, ?> entry : bindings.entrySet()) {
            if (entry.getValue() instanceof Integer && (Integer) entry.getValue() == keyCode) {
                opendoja.host.HostControlAction action = opendoja.host.HostControlAction.fromId(entry.getKey());
                if (action != null) {
                    return action;
                }
            }
        }
        return null;
    }

    private void routeAction(opendoja.host.HostControlAction action, boolean pressed) {
        if (action.dispatchKind() == opendoja.host.HostControlAction.DispatchKind.HOST_SOFT_KEY) {
            if (pressed) {
                input.pressSoft(action.dispatchCode());
            } else {
                input.releaseSoft(action.dispatchCode());
            }
        } else {
            if (pressed) {
                input.pressDojaKey(action.dispatchCode());
            } else {
                input.releaseDojaKey(action.dispatchCode());
            }
        }
    }

    @Override
    protected void onPause() {
        if (surfaceView != null) {
            surfaceView.releaseAllControls();
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (!isFinishing() && !gameStopped) {
            // Backgrounded (HOME / task switch / screen lock): hibernate the
            // game in memory instead of terminating it. It resumes from the
            // exact frame when the user returns and presses a key.
            suspendGame();
        } else {
            stopGame();
        }
        super.onStop();
    }

    /** Hibernates the running game when the app leaves the foreground. The
     *  emulator state stays in memory; frame delivery, timers, and audio park
     *  in place so nothing burns CPU while hidden and nothing is lost. */
    private void suspendGame() {
        if (suspended || gameStopped) {
            return;
        }
        suspended = true;
        opendoja.host.OpenDoJaLog.info(EntryActivity.class, "suspendGame: hibernating session=" + sessionId);
        if (gameMenu != null && gameMenu.isShowing()) {
            try {
                gameMenu.dismiss();
            } catch (Throwable ignored) {
            }
        }
        try {
            com.nttdocomo.ui.AudioPresenter.suspendAllActive();
        } catch (Throwable ignored) {
        }
        try {
            opendoja.host.DoJaRuntime.suspendGlobal();
        } catch (Throwable ignored) {
        }
    }

    /** Wakes a hibernating game after the user returns and presses any key. */
    private void resumeGame() {
        if (!suspended) {
            return;
        }
        suspended = false;
        opendoja.host.OpenDoJaLog.info(EntryActivity.class, "resumeGame: resuming session=" + sessionId);
        // Restart frozen audio and re-enable frame delivery first, then wake
        // the parked threads: a frame racing resumeGlobal() would otherwise be
        // dropped by the released flag and leave the screen stale.
        try {
            com.nttdocomo.ui.AudioPresenter.resumeAllActive();
        } catch (Throwable ignored) {
        }
        if (surfaceView != null) {
            // onPause() released the surface's frame delivery; re-enable it so
            // the freshly woken game threads can present again.
            surfaceView.resumeRendering();
            surfaceView.hidePausedOverlay();
        }
        try {
            opendoja.host.DoJaRuntime.resumeGlobal();
        } catch (Throwable ignored) {
        }
    }

    private void showPausedOverlay() {
        if (surfaceView != null) {
            surfaceView.showPausedOverlay(getString(R.string.paused_press_any_key));
        }
    }

    private void launchSmoke() {
        try {
            LaunchConfig config = LaunchConfig.builder(SmokeApp.class)
                    .scratchpadRoot(java.nio.file.Paths.get(getFilesDir().getAbsolutePath()).resolve("scratchpad"))
                    .build();
            DesktopLauncher.launch(config);
            opendoja.host.OpenDoJaLog.info(EntryActivity.class, "launch returned normally");
        } catch (Throwable throwable) {
            OpenDoJaLog.error(EntryActivity.class, "launch failed", throwable);
            opendoja.host.OpenDoJaLog.error(EntryActivity.class, "launch failed", throwable);
        }
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        if (suspended && !isFinishing()) {
            // Tapping the screen also resumes a hibernating game (touch is a
            // key press on the on-screen keypad); consume the tap.
            if (ev.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                resumeGame();
            }
            return true;
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onResume() {
        super.onResume();
        HomeActivity.applyDisplayOrientation(this, gameId);
        if (suspended) {
            showPausedOverlay();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            HomeActivity.applyDisplayOrientation(this, gameId);
        }
    }

    /** Tears down a game that finished launching after the user already left the
     *  game screen (exit while "Booting..." was still showing). Its runtime did not
     *  exist when stopGame() swept for audio, so the sweep runs here instead. */
    private void teardownLateLaunch() {
        try {
            DoJaRuntime lateRuntime = DoJaRuntime.current();
            if (lateRuntime == null) {
                lateRuntime = DoJaRuntime.lastRuntime();
            }
            if (lateRuntime != null) {
                lateRuntime.shutdown();
            }
        } catch (Throwable ignored) {
        }
        releaseGameAudio(Long.MAX_VALUE);
        try {
            com.nttdocomo.ui.AudioPresenter.setAudioEnabled(false);
            com.nttdocomo.ui.AudioPresenter.releaseStaleSessions();
            com.nttdocomo.ui.AudioPresenter.releaseAllSessions();
            com.nttdocomo.ui.AudioPresenter.releaseAll();
        } catch (Throwable ignored) {
        }
        try {
            opendoja.audio.mld.MLDPCMPlayer.releaseAll();
            opendoja.audio.mld.MLDPCMPlayer.releaseGenerationsUpTo(Long.MAX_VALUE);
        } catch (Throwable ignored) {
        }
        // Stop threads the late game spawned. If a newer launch is already
        // underway, leave the sweep to that instance's stopGame() so this one
        // cannot kill the new launch thread.
        if (!launched || gameThread == null || gameThread == Thread.currentThread()) {
            doomGameThreads();
        }
    }

    private void launchGame() {
        // The user may back out while the "Booting..." overlay is still showing.
        // stopGame() runs on every exit path and marks this instance stopped, so
        // aborting here keeps a late launch from booting the game (and its BGM)
        // after the user is already back on the title screen.
        if (gameStopped) {
            opendoja.host.OpenDoJaLog.info(EntryActivity.class, "launchGame: aborted, game already stopped");
            return;
        }
        // Wait for any previous game exit to fully complete before starting new game.
        // This prevents audio leaks and frozen frames from race conditions during
        // quick exit/launch sequences.
        Thread prevExit = exitThread;
        if (prevExit != null && prevExit.isAlive()) {
            opendoja.host.OpenDoJaLog.info(EntryActivity.class, "launchGame: waiting for previous exit to complete");
            try {
                prevExit.join(3000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            if (gameStopped) {
                opendoja.host.OpenDoJaLog.info(EntryActivity.class, "launchGame: aborted while waiting for previous exit");
                return;
            }
        }

        // Belt-and-braces clean slate: a previous session that exited mid-game could
        // still have a zombie game-loop thread named "jam-launch" (doomGameThreads()
        // exempts it, and a self-initiated shutdown never stops its own thread). Such
        // a thread keeps touching shared emulator state, so reap any survivor now.
        reapLingeringLaunchThreads();

        HomeActivity.bgmSuppressed = true;
        HomeActivity.stopBgmStatic();
        HomeActivity.gameRunning = true;
        sessionId++;
        activeLaunchSession = sessionId;
        threadSession.set(sessionId);
        activeSessionId = sessionId;
        opendoja.host.OpenDoJaLog.info(EntryActivity.class, "launchGame: session=" + sessionId);
        gameReady = false;
        presentedFrameCount = 0;
        hasUserInteracted = false;
            audioGeneration = opendoja.audio.mld.MLDPCMPlayer.bumpGeneration();
            com.nttdocomo.ui.AudioPresenter.setSession(sessionId);
            com.nttdocomo.ui.AudioPresenter.bumpAudioGeneration();
        // Pre-game sweep: any handle left over from an earlier game (e.g. a
        // zombie presenter opened between shutdown and this launch) dies now.
        releaseGameAudio(audioGeneration - 1);
        try {
            com.nttdocomo.ui.AudioPresenter.setAudioEnabled(true);
        } catch (Throwable ignored) {
        }
        opendoja.host.OpenDoJaLog.info(EntryActivity.class, "launchGame: audioGeneration=" + audioGeneration);
        try {
            File dataRoot = new File(getFilesDir(), ".opendoja");
            if (dataRoot.exists()) {
                File[] children = dataRoot.listFiles();
                if (children != null) {
                    for (File child : children) {
                        if ("games".equals(child.getName())) {
                            File[] gameFiles = child.listFiles();
                            if (gameFiles != null) {
                                for (File gf : gameFiles) {
                                    if (gf.getName().endsWith(".rewritten.jar")) {
                                        // Keep offline-patched jars; do not delete.
                                    } else if (!gf.getName().endsWith(".jar") && !gf.getName().endsWith(".dex")) {
                                        deleteRecursively(gf);
                                    }
                                }
                            }
                        } else {
                            deleteRecursively(child);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            File jam = resolveJamFile();
            // Ensure .sp file is available next to the jam
            String base = stripExtension(jam.getName());
            File spFile = new File(jam.getParentFile(), base + ".sp");
            File spCandidate = new File(new File(jam.getParentFile(), "sp"), base + ".sp");
            File spOrig = new File(jam.getParentFile(), base + ".sp.orig");
            if (!spFile.exists()) {
                if (spCandidate.exists()) {
                    copyFileQuietly(spCandidate, spFile);
                    deleteFileQuietly(new File(spFile.getAbsolutePath() + ".undo"));
                }
            }
            // Keep a pristine baseline. Prefer the bundled sp/ copy; otherwise capture the
            // current file once so a later corruption can still be rolled back.
            if (!spOrig.exists()) {
                File seed = spCandidate.exists() ? spCandidate : (spFile.exists() ? spFile : null);
                if (seed != null) {
                    copyFileQuietly(seed, spOrig);
                }
            }
            // A DoJa scratchpad never changes size (writes are bounded in place), so a size
            // change means the file was corrupted (torn write / bad layout / truncation).
            // Titles gate their offline boot on flags inside it (e.g. Touhou's "deluxe" and
            // update bytes), so a corrupt file makes them demand a network check and show a
            // generic "communication error" until the .sp is replaced. Do not wipe the save
            // silently: ask first.
            if (spFile.exists() && spOrig.exists() && spFile.length() != spOrig.length()) {
                // A size change is NOT a reliable corruption signal: titles that do not declare
                // a scratchpad size may grow the file legitimately, and blocking the launch on a
                // dialog here looked like a black/stuck load. Only log; the user can use
                // "Reset Game Data" from the game list if a title actually misbehaves.
                opendoja.host.OpenDoJaLog.warn(EntryActivity.class,
                        "scratchpad " + spFile.getName() + " size " + spFile.length()
                                + " differs from pristine " + spOrig.length() + " (not resetting automatically)");
            }
            File jar = opendoja.host.JamGameJarLocator.locate(java.nio.file.Paths.get(jam.getAbsolutePath())).toFile();
            try {
                // Invalidate cached dexes when the emulator APK is updated.
                JamDexHost.setAppUpdateTime(getPackageManager()
                        .getPackageInfo(getPackageName(), 0).lastUpdateTime);
            } catch (Throwable ignored) {
            }
            File dex = JamDexHost.ensureDex(jar);
            if (gameStopped) {
                opendoja.host.OpenDoJaLog.info(EntryActivity.class, "launch cancelled after conversion");
                return;
            }
            opendoja.host.OpenDoJaLog.info(EntryActivity.class, "dex ready: " + dex + " (" + dex.length() + " bytes)");

            opendoja.host.DoJaEncoding.installDefaultCharsetOverride();
            try {
                opendoja.host.OpenDoJaLog.info(EntryActivity.class, "forName SJIS_i="
                        + java.nio.charset.Charset.forName("SJIS_i").getClass().getName());
            } catch (Throwable throwable) {
                opendoja.host.OpenDoJaLog.warn(EntryActivity.class, "forName SJIS_i failed: " + throwable);
            }

            android.content.Context appContext = getApplicationContext();
            JamGameClassLoaderFactory.setProvider((jamPath, parent) -> {
                File jamJar = jamPath.toFile();
                File dexJar = jamJar;
                String jarName = jamJar.getName();
                if (jarName.endsWith(".rewritten.jar")) {
                    String baseName = jarName.substring(0, jarName.length() - ".rewritten.jar".length());
                    dexJar = new File(jamJar.getParentFile(), baseName + ".jar");
                } else if (jarName.endsWith(".dex.rewritten.jar")) {
                    String baseName = jarName.substring(0, jarName.length() - ".dex.rewritten.jar".length());
                    dexJar = new File(jamJar.getParentFile(), baseName + ".jar");
                }
                File siblingDex = JamDexHost.dexFor(jamJar);
                if (!siblingDex.exists()) {
                    return null;
                }
                return JamDexHost.createLoader(siblingDex, parent, appContext);
            });
        // Prepare the surface for the new session before launching so frames rendered
        // during launch (including the first title frame) are not dropped by the
        // released flag left over from the previous game, which would leave the
        // screen black for several seconds.
        if (surfaceView != null) {
            surfaceView.prepareForNewGame();
        }
        // Apply debug logging preference (default: disabled for performance).
        try {
            android.content.SharedPreferences logPrefs = getSharedPreferences(DisplayActivity.PREFS_NAME, MODE_PRIVATE);
            boolean logEnabled = "enable".equals(logPrefs.getString(DisplayActivity.KEY_DEBUG, "disable"));
            if (logEnabled) {
                java.io.File logFile = new java.io.File(getExternalFilesDir(null), "pocketdoja-log.txt");
                if (logFile.getParentFile() == null) {
                    logFile = new java.io.File(getFilesDir(), "pocketdoja-log.txt");
                }
                OpenDoJaLog.setFileLoggingEnabled(true, logFile);
                // Actually emit messages when the user enables Debugging (previously the level
                // stayed OFF, so the log file was always empty).
                OpenDoJaLog.configure(OpenDoJaLog.Level.DEBUG);
            } else {
                OpenDoJaLog.setFileLoggingEnabled(false, null);
                OpenDoJaLog.configure(OpenDoJaLog.Level.OFF);
            }
        } catch (Throwable throwable) {
            runOnUiThread(() -> android.widget.Toast.makeText(this, "Logging setup failed: " + throwable, android.widget.Toast.LENGTH_LONG).show());
        }
        DoJaRuntime.setPlatformPresenter((frame, sessionId) -> {
            if (frame == null) {
                runOnUiThread(this::exitGame);
                return;
            }
            surfaceView.present(frame, sessionId);
        });

            LaunchConfig config = JamLauncher.buildLaunchConfig(java.nio.file.Paths.get(jam.getAbsolutePath()), false,
                    java.nio.file.Paths.get(getFilesDir().getAbsolutePath()).resolve("scratchpad"));
            // Platform presenter is set in onCreate and reads expectedRuntime (volatile)
            IApplication application = DesktopLauncher.launch(config);
            try {
                com.nttdocomo.ui.AudioPresenter.setSession(sessionId);
            } catch (Throwable ignored) {
            }
            // Prepare surface view for a new game session so stale frames from
            // the previous game are not displayed. Must run AFTER launch so the
            // new session ID from DoJaRuntime.current() is known.
            if (surfaceView != null) {
                surfaceView.prepareForNewGame();
            }
            if (gameStopped) {
                opendoja.host.OpenDoJaLog.info(EntryActivity.class, "launch cancelled after DesktopLauncher.launch");
                teardownLateLaunch();
                return;
            }
            opendoja.host.OpenDoJaLog.info(EntryActivity.class, "jam launch returned normally: " + application);
            gameClassLoader = application.getClass().getClassLoader();
            if (gameRuntime == null) {
                gameRuntime = DoJaRuntime.lastRuntime();
            }
            expectedRuntime = gameRuntime;
            if (input != null && gameRuntime != null) {
                input.setRuntime(gameRuntime);
            }
            if (gameRuntime != null) {
                final int sessionForCallback = sessionId;
                gameRuntime.setShutdownCallback(runtime -> {
                    if (runtime.sessionId() != sessionForCallback || runtime.sessionId() != activeSessionId) {
                        opendoja.host.OpenDoJaLog.info(EntryActivity.class, "per-session shutdown callback ignored: session=" + runtime.sessionId() + " this=" + sessionForCallback + " active=" + activeSessionId);
                        return;
                    }
                    runOnUiThread(this::exitGame);
                });
        DoJaRuntime.setPlatformPresenter((frame, sessionId) -> {
            if (frame == null) {
                runOnUiThread(this::exitGame);
                return;
            }
            surfaceView.present(frame, sessionId);
        });
            }
            DoJaRuntime runtime = DoJaRuntime.current();
            if (runtime != null && gameThread != null && gameThread.isAlive()) {
                try {
                    opendoja.host.OpenDoJaLog.info(EntryActivity.class, "awaitShutdown: entering");
                    runtime.awaitShutdown();
                    opendoja.host.OpenDoJaLog.info(EntryActivity.class, "awaitShutdown: returned");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } else {
                opendoja.host.OpenDoJaLog.info(EntryActivity.class, "game thread not alive, skipping awaitShutdown");
            }
            runOnUiThread(this::exitGame);
        } catch (Throwable throwable) {
            OpenDoJaLog.error(EntryActivity.class, "game launch failed", throwable);
            opendoja.host.OpenDoJaLog.error(EntryActivity.class, "game launch failed", throwable);
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }

    private static String stripExtension(String name) {
        int idx = name.lastIndexOf('.');
        return idx > 0 ? name.substring(0, idx) : name;
    }

    private static void copyFileQuietly(File source, File target) {
        try {
            java.nio.file.Files.copy(source.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }

    private static void deleteFileQuietly(File file) {
        try {
            java.nio.file.Files.deleteIfExists(file.toPath());
        } catch (IOException | RuntimeException ignored) {
        }
    }

    /**
     * Modal overlay shown when the game's saved data is detected as damaged. It offers to
     * reset the data to the pristine snapshot; it blocks the launch thread until the user
     * chooses so a corrupt save is never wiped silently.
     */
    private File resolveJamFile() {
        String extra = getIntent() == null ? null : getIntent().getStringExtra("jamPath");
        if (extra != null) {
            File jam = new File(extra);
            if (jam.isFile()) {
                return jam;
            }
            throw new IllegalStateException("Game file not found: " + extra);
        }
        throw new IllegalStateException("No game path (jamPath)");
    }

    protected void onDestroy() {
        // Covers destruction paths other than BACK.
        // If the game was hibernating, wake its threads before teardown so the
        // sweep below can stop them cleanly (a parked thread never dies).
        if (suspended) {
            suspended = false;
            try {
                opendoja.host.DoJaRuntime.resumeGlobal();
            } catch (Throwable ignored) {
            }
            try {
                com.nttdocomo.ui.AudioPresenter.resumeAllActive();
            } catch (Throwable ignored) {
            }
        }
        try {
            if (input != null) {
                input.releaseAll();
                input.clearRuntime();
            }
        } catch (Throwable ignored) {
        }
        stopGame();
        try {
            releaseGameAudio(Long.MAX_VALUE);
            com.nttdocomo.ui.AudioPresenter.releaseAllSessions();
            com.nttdocomo.ui.AudioPresenter.releaseAll();
        } catch (Throwable ignored) {
        }
        try {
            opendoja.audio.mld.MLDPCMPlayer.releaseAll();
            opendoja.audio.mld.MLDPCMPlayer.releaseGenerationsUpTo(Long.MAX_VALUE);
        } catch (Throwable ignored) {
        }
        DoJaRuntime.setPlatformPresenter(null);
        DoJaRuntime.setPresentDebounceEnabled(false);
        super.onDestroy();
        // Per-game process isolation: after a real exit, kill this :game process so every
        // game-created thread (Android cannot Thread.stop()) and all its audio/native
        // resources are released. Not done on HOME/background (onDestroy isn't called then),
        // so hibernation/resume still works.
        if (isFinishing()) {
            scheduleGameProcessExit();
        }
    }

    private static void scheduleGameProcessExit() {
        processTerminating = true;
        Thread killer = new Thread(() -> {
            try {
                Thread.sleep(250L);
            } catch (InterruptedException ignored) {
            }
            if (processTerminating) {
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        }, "opendoja-game-process-exit");
        killer.setDaemon(true);
        killer.start();
    }
}
