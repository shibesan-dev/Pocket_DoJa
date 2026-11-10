package opendoja.host;

import com.nttdocomo.opt.ui.PhoneSystem2;
import com.nttdocomo.opt.ui.PointingDevice;
import com.nttdocomo.opt.ui.StereoScreen;
import com.nttdocomo.ui.*;
import com.nttdocomo.ui.Canvas;
import com.nttdocomo.ui.Frame;
import com.nttdocomo.ui.Graphics;
import opendoja.host.input.ControllerInputEvent;
import opendoja.host.input.ControllerInputListener;
import opendoja.host.input.ControllerInputManager;

import opendoja.compat.swing.*;
import opendoja.compat.awt.*;
import opendoja.compat.awt.event.*;
import opendoja.compat.awt.geom.AffineTransform;
import opendoja.compat.awt.geom.NoninvertibleTransformException;
import opendoja.compat.awt.geom.Point2D;
import opendoja.compat.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

public final class DoJaRuntime {
    public static final int MIN_HOST_SCALE = HostScale.MIN_FIXED_SCALE;
    public static final int MAX_HOST_SCALE = HostScale.MAX_FIXED_SCALE;
    private static final double MIN_DYNAMIC_HOST_SCALE = 0.01d;

    private static final ThreadLocal<LaunchConfig> PREPARED_LAUNCH = new ThreadLocal<>();
    private static final boolean TRACE_EVENTS = opendoja.host.OpenDoJaLaunchArgs.getBoolean(opendoja.host.OpenDoJaLaunchArgs.TRACE_EVENTS);
    private static final long MINIMUM_SELECT_PRESS_NANOS =
            java.lang.Math.max(0L, opendoja.host.OpenDoJaLaunchArgs.getLong(opendoja.host.OpenDoJaLaunchArgs.INPUT_MINIMUM_SELECT_PRESS_MS)) * 1_000_000L;
    // A short release debounce collapses desktop auto-repeat release/press pairs into a stable hold.
    private static final int KEY_REPEAT_RELEASE_DEBOUNCE_MS =
            java.lang.Math.max(0, opendoja.host.OpenDoJaLaunchArgs.getInt(opendoja.host.OpenDoJaLaunchArgs.INPUT_KEY_REPEAT_RELEASE_DEBOUNCE_MS));
    public static final int TOUCH_PRESSED_EVENT = 67;
    public static final int TOUCH_RELEASED_EVENT = 68;
    private static final Map<Integer, HostControlAction> DEFAULT_KEYBOARD_ACTIONS =
            HostKeybindProfile.defaults().keyboardActionsByKeyCode();
    private static final java.util.concurrent.atomic.AtomicInteger SESSION_COUNTER = new java.util.concurrent.atomic.AtomicInteger(0);
    private final int sessionId = SESSION_COUNTER.incrementAndGet();

    public int sessionId() {
        return sessionId;
    }
    private volatile java.util.function.Consumer<DoJaRuntime> shutdownCallback;
    public void setShutdownCallback(java.util.function.Consumer<DoJaRuntime> callback) {
        this.shutdownCallback = callback;
    }
    private static volatile java.util.function.Consumer<DoJaRuntime> globalShutdownCallback;
    public static void setGlobalShutdownCallback(java.util.function.Consumer<DoJaRuntime> callback) {
        globalShutdownCallback = callback;
    }
    private volatile Thread gameThread;
    public Thread getGameThread() { return gameThread; }
    private static final java.util.Set<String> LOGGED_RESOURCE_LOOKUPS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void logResourceLookup(String session, String normalized, String mechanism, String result) {
        String key = session + "|" + normalized + "|" + mechanism + "|" + result;
        if (LOGGED_RESOURCE_LOOKUPS.add(key)) {
            opendoja.host.OpenDoJaLog.info(DoJaRuntime.class, "resource lookup: session=" + session
                    + " name=" + normalized + " via " + mechanism + " -> " + result);
        }
    }
    private static volatile DoJaRuntime current;
    private static volatile DoJaRuntime lastRuntime;
    private static volatile boolean everShutdown;
    public static boolean isShuttingDown() {
        DoJaRuntime runtime = current;
        if (runtime != null) {
            return runtime.isShutdown();
        }
        return everShutdown;
    }
    public static void markShutdown() {
        everShutdown = true;
    }

    private static final java.util.Set<Thread> TERMINATION_SIGNALLED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Cooperative shutdown for leaked game threads (Android's Thread.stop() is unsupported and
     * titles swallow interrupt()/catch(Exception)). On the first call after its runtime shut
     * down, the calling thread throws {@link GameTerminatedException} (an Error, so
     * catch(Exception) cannot swallow it). If the SAME thread calls again it means the title
     * caught even that (e.g. catch(Throwable) around its loop): park it forever so it can never
     * burn CPU again.
     */
    public static void terminateOrParkIfShuttingDown(boolean shuttingDown) {
        if (!shuttingDown) {
            return;
        }
        Thread currentThread = Thread.currentThread();
        if (TERMINATION_SIGNALLED.add(currentThread)) {
            throw new GameTerminatedException();
        }
        while (true) {
            try {
                Thread.sleep(60_000L);
            } catch (InterruptedException ignored) {
                // stay parked
            }
        }
    }
    /**
     * When enabled (Android host), outside-lock per-primitive presents are coalesced:
     * a burst of draw calls publishes one snapshot after drawing goes quiet, so the
     * screen never shows a half-drawn frame. Frame-boundary presents stay immediate.
     */
    private static volatile boolean presentDebounceEnabled;
    private static final long PRESENT_DEBOUNCE_QUIET_MS = 8L;
    private static final long PRESENT_DEBOUNCE_MAX_WAIT_MS = 300L;

    private final LaunchConfig config;
    private final AtomicBoolean shutdown = new AtomicBoolean();
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);
    private final ReentrantLock surfaceLock = new ReentrantLock(true);
    private final ScheduledExecutorService scheduler;
    private final java.util.concurrent.ExecutorService eventExecutor;
    private final Object deferredPresentLock = new Object();
    private BufferedImage deferredPresentFrame;
    private Canvas deferredPresentCanvas;
    private long deferredPresentFirstNanos;
    private ScheduledFuture<?> deferredPresentFuture;
    private final java.util.concurrent.ExecutorService renderExecutor;
    // Runtime-posted callbacks let host-side services feed work back onto the
    // same execution model that DoJa titles already use for paint/event code.
    private final ConcurrentLinkedQueue<Runnable> applicationCallbacks = new ConcurrentLinkedQueue<>();
    private final ThreadLocal<Boolean> drainingApplicationCallbacks =
            ThreadLocal.withInitial(() -> false);
    private final AtomicBoolean renderQueued = new AtomicBoolean();
    // iDKDoja3.5 uses a bounded ScreenUpdater queue. openDoJa has one render slot,
    // so repaint calls that arrive while it is occupied are coalesced and replayed.
    private final AtomicReference<RepaintRequest> pendingRepaint = new AtomicReference<>();
    private final Object renderMonitor = new Object();
    private final Set<AutoCloseable> shutdownResources = ConcurrentHashMap.newKeySet();
    private final HostPanel hostPanel;
    private final ExternalFrameRenderer externalFrameRenderer;
    private final OpenGlesFpsOverlay openGlesFpsOverlay;
    private final ScratchpadStorage scratchpadStorage;
    private final opendoja.compat.awt.event.WindowAdapter hostWindowLifecycleListener;
    private final opendoja.compat.awt.event.WindowAdapter hostWindowFocusListener;
    private volatile boolean openGlesFpsEnabled;
    private volatile String hostScaleSetting;
    private volatile IApplication application;
    private JFrame frameWindow;
    private GraphicsDevice fullScreenDevice;
    private volatile Frame currentFrame;
    // `presentedCanvas` / `presentedFrame` only tell us what is currently visible. We still need
    // one explicit bit to remember whether the active canvas has already presented from the
    // application-owned direct graphics path, because only that fact suppresses extra host paints.
    private volatile boolean currentCanvasPresentedByApplication;
    private volatile Canvas presentedCanvas;
    private volatile BufferedImage presentedFrame;
    private volatile long keypadStateBits;
    private volatile long selectLatchedUntilNanos;
    private volatile Thread renderThread;
    private final Set<Integer> enabledKeypadGroups = ConcurrentHashMap.newKeySet();
    private final AtomicInteger modalDialogDepth = new AtomicInteger();
    private volatile boolean keypadConfigurationLocked;
    private String previousMicroeditionPlatform;
    private String previousMicroeditionProfiles;
    private boolean restoreMicroeditionPlatform;
    private boolean restoreMicroeditionProfiles;

    private DoJaRuntime(LaunchConfig config) {
        this.config = config;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> newRuntimeThread(r, "openDoJa-runtime"));
        this.eventExecutor = Executors.newSingleThreadExecutor(r -> newRuntimeThread(r, "MainEventDispatchThread"));
        this.renderExecutor = Executors.newSingleThreadExecutor(r -> newRuntimeThread(r, "openDoJa-render"));
        this.enabledKeypadGroups.add(0);
        this.hostScaleSetting = resolveHostScale(config);
        this.externalFrameRenderer = new ExternalFrameRenderer(
                resolveExternalFrameEnabled(config),
                config.statusBarIconDevice(),
                config.iAppliType(),
                config.launchType());
        this.openGlesFpsOverlay = new OpenGlesFpsOverlay();
        this.openGlesFpsEnabled = OpenDoJaLaunchArgs.getBoolean(OpenDoJaLaunchArgs.SHOW_OPEN_GLES_FPS);
        if (openGlesFpsEnabled) {
            this.externalFrameRenderer.addOverlay(openGlesFpsOverlay);
        }
        String legacyNamespaceRaw = config.sourceUrl();
        if (legacyNamespaceRaw == null || legacyNamespaceRaw.isBlank()) {
            legacyNamespaceRaw = config.applicationClass() == null ? null : config.applicationClass().getName();
        }
        if (legacyNamespaceRaw == null || legacyNamespaceRaw.isBlank()) {
            legacyNamespaceRaw = config.parameters().get("AppName");
        }
        String legacyNamespace = null;
        if (legacyNamespaceRaw != null && !legacyNamespaceRaw.isBlank()) {
            legacyNamespace = java.util.UUID.nameUUIDFromBytes(
                    legacyNamespaceRaw.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .toString().replace("-", "").toLowerCase(java.util.Locale.ROOT);
        }
        this.scratchpadStorage = new ScratchpadStorage(
                config.scratchpadRoot(),
                config.scratchpadPackedFile(),
                config.scratchpadSizes(),
                legacyNamespace);
        this.hostPanel = new HostPanel(this);
        this.hostWindowLifecycleListener = new opendoja.compat.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(opendoja.compat.awt.event.WindowEvent e) {
                if (!shutdown.get()) {
                    shutdown();
                }
            }
        };
        this.hostWindowFocusListener = new opendoja.compat.awt.event.WindowAdapter() {
            @Override
            public void windowGainedFocus(opendoja.compat.awt.event.WindowEvent e) {
                updateFullScreenTopHint((JFrame) e.getWindow(), true);
            }

            @Override
            public void windowLostFocus(opendoja.compat.awt.event.WindowEvent e) {
                updateFullScreenTopHint((JFrame) e.getWindow(), false);
            }
        };
    }

    private Thread newRuntimeThread(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        // Runtime-owned workers need the same resource lookup view as the launched application.
        thread.setContextClassLoader(config.applicationClass().getClassLoader());
        return thread;
    }

    public static void prepareLaunch(LaunchConfig config) {
        PREPARED_LAUNCH.set(config);
    }

    public static LaunchConfig consumePreparedLaunch() {
        return PREPARED_LAUNCH.get();
    }

    public static LaunchConfig peekPreparedLaunch() {
        return PREPARED_LAUNCH.get();
    }

    public static void clearPreparedLaunch() {
        PREPARED_LAUNCH.remove();
    }

    public static DoJaRuntime bootstrap(LaunchConfig config) {
        // Fully reset JVM-wide state so the new game session starts identically
        // to a fresh process launch (matching force-stop / swipe-off-from-recents behavior).
        // Without this, state left behind by a previous session (charset override,
        // stale application reference, active audio presenters, sticky shutdown flag)
        // can cause the next game to behave differently on first launch.
        resetForNewGameSession();
        DoJaRuntime runtime = new DoJaRuntime(config);
        runtime.prepareScratchpadStorage();
        runtime.createWindowIfPossible();
        current = runtime;
        lastRuntime = runtime;
        runtime.applyLaunchSystemProperties();
        PhoneSystem2.resetRuntimeDefaults();
        StereoScreen.resetRuntimeDefaults();
        OpenDoJaLog.info(DoJaRuntime.class,
                "runtime bootstrap session=" + runtime.sessionId() + " thread=" + Thread.currentThread().getName()
                        + " runtime=" + System.identityHashCode(runtime) + " fullReset=true");
        return runtime;
    }

    /**
     * Resets all JVM-wide/static state that persists across game sessions within the same
     * emulator process. Called at the start of every {@link #bootstrap(LaunchConfig)} so
     * that "Exit Game" followed by "New Game" behaves identically to force-stopping and
     * reopening the emulator.
     */
    private static void invokeStaticMethod(String className, String methodName) {
        try {
            java.lang.reflect.Method method = Class.forName(className).getDeclaredMethod(methodName);
            method.setAccessible(true);
            method.invoke(null);
        } catch (Throwable ignored) {
        }
    }

    private static void resetForNewGameSession() {
        OpenDoJaLog.info(DoJaRuntime.class,
                "resetForNewGameSession: starting full cross-session state reset");

        // Restore UTF-8 default charset
        try {
            opendoja.host.DoJaEncoding.resetDefaultCharset();
        } catch (Throwable ignored) {
        }

        try {
            com.nttdocomo.ui.IApplication.clearCurrentApp();
        } catch (Throwable ignored) {
        }

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

        try {
            com.nttdocomo.ui.AudioPresenter.setAudioEnabled(true);
        } catch (Throwable ignored) {
        }
        try {
            com.nttdocomo.ui.MediaManager.reset();
        } catch (Throwable ignored) {
        }
        try {
            opendoja.audio.MidiEventPlayer.reset();
        } catch (Throwable ignored) {
        }
        try {
            opendoja.host.OpenDoJaLog.configureIfUnset(opendoja.host.OpenDoJaLog.Level.OFF);
        } catch (Throwable ignored) {
        }

        // Aggressively clear common static caches
        try {
            Class.forName("com.nttdocomo.ui.PhoneSystem").getMethod("clearCache").invoke(null);
        } catch (Throwable ignored) {
        }
        try {
            Class.forName("com.nttdocomo.ui.MediaManager").getMethod("clearCache").invoke(null);
        } catch (Throwable ignored) {
        }
        try {
            Class.forName("com.nttdocomo.ui.Display").getMethod("clearCache").invoke(null);
        } catch (Throwable ignored) {
        }
        try {
            Class.forName("com.nttdocomo.ui.IApplication").getMethod("clearCurrentApp").invoke(null);
        } catch (Throwable ignored) {
        }
        try {
            // Font statics (customized default font, cached default/family state,
            // bitmap string raster cache) otherwise leak across game sessions.
            com.nttdocomo.ui.Font.resetSessionState();
        } catch (Throwable ignored) {
        }
        // Static System-API / optional-device stores are keyed per session and some hold
        // game-created objects; clear them so titles (and their classloaders) are freed.
        try {
            opendoja.host.system.DoJaSystemRegistry.clear();
        } catch (Throwable ignored) {
        }
        invokeStaticMethod("com.nttdocomo.system._SystemSupport", "resetSessionState");
        try {
            com.nttdocomo.opt.ui.IlluminationDisplay.resetSessionState();
        } catch (Throwable ignored) {
        }
        invokeStaticMethod("com.nttdocomo.device.felica.FelicaSupport", "resetSessionState");
        try {
            com.nttdocomo.device.Camera.resetSessionState();
        } catch (Throwable ignored) {
        }

        // Session-scoped "log once per resource" bookkeeping is keyed by session id, so it
        // would otherwise grow without bound across the process lifetime.
        LOGGED_RESOURCE_LOOKUPS.clear();

        // A previous session that was backgrounded/hibernated can leave the global suspend
        // latch set. The next launch would then block in Display.setCurrent -> requestRender
        // -> awaitSuspendRelease and hang forever at "Booting...". Always start un-suspended.
        resumeGlobal();

        everShutdown = false;

        OpenDoJaLog.info(DoJaRuntime.class,
                "resetForNewGameSession: cross-session state reset complete");
    }

    public static void bindPreparedApplication(IApplication application) {
        DoJaRuntime runtime = current;
        LaunchConfig prepared = PREPARED_LAUNCH.get();
        if (runtime == null || prepared == null || !prepared.applicationClass().isInstance(application)) {
            return;
        }
        runtime.attachApplication(application);
    }

    public static void resetCurrent() {
        try {
            if (current != null) {
                current = lastRuntime;
            }
        } catch (Throwable ignored) {
        }
    }
    public static DoJaRuntime current() {
        return current;
    }

    public static DoJaRuntime lastRuntime() {
        return lastRuntime;
    }

    public boolean isShutdown() {
        return shutdown.get();
    }

    /** True once any runtime in this process has been shut down; used to keep
     *  zombie game threads from starting new audio after an exit. */
    public static boolean hasShutDown() {
        return everShutdown;
    }

    public LaunchConfig config() {
        return config;
    }

    public ScheduledExecutorService scheduler() {
        return scheduler;
    }

    public ReentrantLock surfaceLock() {
        return surfaceLock;
    }

    public void postApplicationCallback(Runnable callback) {
        if (callback == null || shutdown.get()) {
            return;
        }
        applicationCallbacks.add(callback);
        Frame frame = currentFrame;
        if (frame instanceof Canvas canvas) {
            // Once a canvas has actually presented from outside the runtime render
            // worker, queued callbacks can ride that app-owned lock/unlock path.
            // Forcing requestRender(canvas) after that would inject an extra host
            // paint into the application's own presentation loop.
            if (currentCanvasPresentedByApplication) {
                return;
            }
            // Non-direct canvas titles still need a render wakeup so the
            // callback is drained on the canvas/application path instead of
            // on an unrelated host thread.
            requestRender(canvas);
            return;
        }
        // Non-canvas frames still need a host-side wakeup to service the queue.
        SwingUtilities.invokeLater(this::drainApplicationCallbacks);
    }

    public void drainApplicationCallbacks() {
        if (shutdown.get() || drainingApplicationCallbacks.get()) {
            return;
        }
        drainingApplicationCallbacks.set(true);
        try {
            Runnable callback;
            while ((callback = applicationCallbacks.poll()) != null) {
                try {
                    callback.run();
                } catch (Throwable throwable) {
                    // Match the rest of the host runtime: callbacks are best
                    // effort and should not tear down the scheduler thread.
                    OpenDoJaLog.error(DoJaRuntime.class,
                            "Unhandled application callback failure", throwable);
                }
            }
        } finally {
            drainingApplicationCallbacks.set(false);
        }
    }

    public IApplication application() {
        return application;
    }

    public void attachApplication(IApplication application) {
        Objects.requireNonNull(application, "application");
        OpenDoJaLog.info(DoJaRuntime.class, "attachApplication: " + application.getClass().getName());
        // Diagnostic: inspect FCanvas subclass
        try {
            Class<?> appClass = application.getClass();
            Class<?> fcanvas = appClass.getClassLoader().loadClass("netpuyo2dx.FCanvas");
            OpenDoJaLog.info(DoJaRuntime.class, "FCanvas: " + fcanvas.getName());
            OpenDoJaLog.info(DoJaRuntime.class, "FCanvas superclass: " + fcanvas.getSuperclass().getName());
            for (String methodName : new String[]{"setConData", "setConStatus", "getConStatus"}) {
                boolean found = false;
                for (java.lang.reflect.Method m : fcanvas.getDeclaredMethods()) {
                    if (m.getName().equals(methodName)) {
                        OpenDoJaLog.info(DoJaRuntime.class, "FCanvas method " + methodName + ": declaringClass=" + m.getDeclaringClass().getName() + " modifiers=" + java.lang.reflect.Modifier.toString(m.getModifiers()) + " params=" + java.util.Arrays.toString(m.getParameterTypes()) + " returnType=" + m.getReturnType().getName());
                        found = true;
                    }
                }
                if (!found) {
                    OpenDoJaLog.info(DoJaRuntime.class, "FCanvas does NOT declare " + methodName);
                }
            }
            Class<?> screenClass = appClass.getClassLoader().loadClass("netpuyo2dx.screen");
            OpenDoJaLog.info(DoJaRuntime.class, "Screen class: " + screenClass.getName());
            OpenDoJaLog.info(DoJaRuntime.class, "Screen superclass: " + screenClass.getSuperclass().getName());
            for (String methodName : new String[]{"setConData", "setConStatus", "getConStatus"}) {
                boolean found = false;
                for (java.lang.reflect.Method m : screenClass.getDeclaredMethods()) {
                    if (m.getName().equals(methodName)) {
                        OpenDoJaLog.info(DoJaRuntime.class, "Screen method " + methodName + ": declaringClass=" + m.getDeclaringClass().getName() + " modifiers=" + java.lang.reflect.Modifier.toString(m.getModifiers()) + " params=" + java.util.Arrays.toString(m.getParameterTypes()) + " returnType=" + m.getReturnType().getName());
                        found = true;
                    }
                }
                if (!found) {
                    OpenDoJaLog.info(DoJaRuntime.class, "Screen does NOT declare " + methodName);
                }
            }
        } catch (Exception e) {
            OpenDoJaLog.warn(DoJaRuntime.class, "Diagnostic failed", e);
        }
        IApplication attached = this.application;
        if (attached == application) {
            return;
        }
        if (attached != null) {
            throw new IllegalStateException("Runtime already attached to " + attached.getClass().getName());
        }
        this.application = application;
    }

    public int displayWidth() {
        return config.width();
    }

    public int displayHeight() {
        return config.height();
    }

    public int hostScale() {
        return Math.max(MIN_HOST_SCALE, (int) Math.round(hostScaleFactor()));
    }

    public double hostScaleFactor() {
        return resolveHostScaleFactor(hostScaleSetting);
    }

    public String hostScaleSetting() {
        return hostScaleSetting;
    }

    public void setHostScale(int scale) {
        setHostScale(Integer.toString(scale));
    }

    public void setHostScale(String scale) {
        String normalized = normalizeHostScale(scale);
        if (hostScaleSetting.equals(normalized)) {
            return;
        }
        hostScaleSetting = normalized;
        if (SwingUtilities.isEventDispatchThread()) {
            applyHostWindowModeIfNeeded();
            hostPanel.refreshPreferredSize();
            repackWindow();
            repaintWindow();
        } else {
            SwingUtilities.invokeLater(() -> {
                applyHostWindowModeIfNeeded();
                hostPanel.refreshPreferredSize();
                repackWindow();
                repaintWindow();
            });
        }
    }

    public String sourceUrl() {
        return config.sourceUrl();
    }

    public Map<String, String> parameters() {
        return config.parameters();
    }

    public int launchType() {
        return config.launchType();
    }

    public String[] args() {
        return config.args();
    }

    public void startApplication() {
        IApplication app = application;
        if (app == null) {
            throw new IllegalStateException("No application attached to runtime");
        }
        gameThread = Thread.currentThread();
        OpenDoJaLog.info(DoJaRuntime.class, "startApplication: calling app.start() class=" + app.getClass().getName() + " thread=" + Thread.currentThread().getName());
        try {
            Class<?> logClass = Class.forName("android.util.Log");
            java.lang.reflect.Method m = logClass.getMethod("i", String.class, String.class);
            m.invoke(null, "OpenDoJa", "startApplication: calling app.start() class=" + app.getClass().getName() + " thread=" + Thread.currentThread().getName());
        } catch (Throwable ignored) {
        }
        try {
            app.start();
        } catch (GameTerminatedException terminated) {
            // IApplication.terminate() unwinds the application thread by throwing
            // GameTerminatedException. When a title runs a blocking main loop inside
            // start()/run(), the unwind surfaces here: that is a clean, expected exit,
            // not a launch failure.
            OpenDoJaLog.info(DoJaRuntime.class,
                    "startApplication: application terminated normally thread=" + Thread.currentThread().getName());
            return;
        }
        OpenDoJaLog.info(DoJaRuntime.class, "IS_MAPP=" + (app instanceof com.nttdocomo.ui.MApplication) + " isActive=" + (app instanceof com.nttdocomo.ui.MApplication m ? m.isActive() : "n/a"));
        try {
            Class<?> logClass = Class.forName("android.util.Log");
            java.lang.reflect.Method m = logClass.getMethod("i", String.class, String.class);
            m.invoke(null, "OpenDoJa", "startApplication: app.start() returned thread=" + Thread.currentThread().getName());
        } catch (Throwable ignored) {
        }
        OpenDoJaLog.info(DoJaRuntime.class, "startApplication: app.start() returned");
        if (app instanceof MApplication standbyApplication && !standbyApplication.isActive()) {
            // Standby titles commonly call deactivate() during start() and then wait for the
            // initial host mode-change callback before leaving their splash/inactive state.
            standbyApplication.setActiveForHost(true);
            standbyApplication.processSystemEvent(MApplication.MODE_CHANGED_EVENT, 0);
        }
    }

    public void shutdown() {
        shutdown(config.exitOnShutdown());
    }

    public boolean isShutdownRequested() {
        return shutdown.get();
    }

    void abortLaunch() {
        OpenDoJaLog.info(DoJaRuntime.class,
                "abortLaunch entry: session=" + sessionId + " game=" + (application == null ? "none" : application.getClass().getName())
                        + " thread=" + Thread.currentThread().getName() + " shutdownAlreadyRequested=" + shutdown.get());
        try {
            shutdown(false);
        } catch (GameTerminatedException normalExit) {
            // shutdown() always signals the app thread to unwind; during an abort there
            // is no app frame left to unwind, so the signal is expected and harmless.
        }
        OpenDoJaLog.info(DoJaRuntime.class,
                "abortLaunch exit: session=" + sessionId + " game=" + (application == null ? "none" : application.getClass().getName())
                        + " thread=" + Thread.currentThread().getName());
    }

    private void shutdown(boolean exitProcess) {
        if (!shutdown.compareAndSet(false, true)) {
            return;
        }
        everShutdown = true;
        markShutdown();
        try {
            com.nttdocomo.ui.IApplication.clearCurrentApp();
        } catch (Throwable ignored) {
        }
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
        try {
            if (current == this) {
                current = lastRuntime;
            }
        } catch (Throwable ignored) {
        }
        opendoja.host.DoJaEncoding.resetDefaultCharset();
        opendoja.host.DoJaSjisCompatibility.reset();
        opendoja.host.JamNamedModuleResourceBridge.reset();
        try {
            Class<?> logClass = Class.forName("android.util.Log");
            java.lang.reflect.Method m = logClass.getMethod("i", String.class, String.class);
            m.invoke(null, "OpenDoJa", "runtime shutdown begin session=" + sessionId + " exitProcess=" + exitProcess + " thread=" + Thread.currentThread().getName()
                    + " runtime=" + System.identityHashCode(this) + " keypadStateBits=0x" + Long.toHexString(keypadStateBits)
                    + " (" + Long.toBinaryString(keypadStateBits) + ")");
            closeShutdownResources();
            Thread gt = gameThread;
            if (gt != null && gt != Thread.currentThread()) {
                gt.interrupt();
                try { gt.join(200); } catch (InterruptedException ignored) { }
                if (gt.isAlive()) {
                    try { gt.stop(); } catch (Throwable ignored) { }
                }
                gameThread = null;
            }
            // The session is over: drop journal bookkeeping but keep any journal file, so
            // the next launch of this game (even in the same process) rolls back a save
            // that was interrupted by this exit.
            try {
                ScratchpadJournal.abandon(config.scratchpadPackedFile());
            } catch (Throwable ignored) {
            }
            cancelDeferredPresent();
            scheduler.shutdownNow();
            eventExecutor.shutdownNow();
            renderExecutor.shutdownNow();
            JFrame window = frameWindow;
            if (window != null) {
                SwingUtilities.invokeLater(() -> {
                    try { exitFullScreenWindowIfNeeded(); window.dispose(); } catch (Throwable ignored) { }
                });
            }
            restoreLaunchSystemProperties();
            // Drop the game application so the title (and its classloader) is collectible.
            application = null;
            if (current == this) { current = null; }
            if (lastRuntime == this) { lastRuntime = null; }
        } catch (Throwable throwable) {
            OpenDoJaLog.error(DoJaRuntime.class, "shutdown failed", throwable);
        } finally {
            shutdownLatch.countDown();
            java.util.function.Consumer<DoJaRuntime> cb = shutdownCallback;
            if (cb != null) {
                try {
                    cb.accept(this);
                } catch (Throwable ignored) {
                }
            }
            cb = globalShutdownCallback;
            if (cb != null) {
                try {
                    cb.accept(this);
                } catch (Throwable ignored) {
                }
            }
            try {
            Class<?> logClass = Class.forName("android.util.Log");
            java.lang.reflect.Method m = logClass.getMethod("i", String.class, String.class);
            m.invoke(null, "OpenDoJa", "runtime shutdown complete session=" + sessionId + " runtime=" + System.identityHashCode(this));
        } catch (Throwable ignored) {
        }
        if (exitProcess) {
            System.exit(0);
        } else {
            throw new opendoja.host.GameTerminatedException();
        }
    }
}
    public void awaitShutdown() throws InterruptedException {
        shutdownLatch.await();
    }

    public boolean awaitShutdown(long timeoutMillis) throws InterruptedException {
        return shutdownLatch.await(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public void registerShutdownResource(AutoCloseable resource) {
        if (resource == null || shutdown.get()) {
            if (resource != null) {
                OpenDoJaLog.warn(DoJaRuntime.class,
                        "shutdown resource registered after shutdown: "
                                + resource.getClass().getName());
                try {
                    resource.close();
                } catch (Exception exception) {
                    OpenDoJaLog.error(DoJaRuntime.class,
                            "late shutdown resource close failed", exception);
                }
            }
            return;
        }
        shutdownResources.add(resource);
    }

    public void unregisterShutdownResource(AutoCloseable resource) {
        if (resource != null) {
            shutdownResources.remove(resource);
        }
    }

    public void setCurrentFrame(Frame frame) {
        try {
            Class<?> logClass = Class.forName("android.util.Log");
            java.lang.reflect.Method m = logClass.getMethod("i", String.class, String.class);
            m.invoke(null, "OpenDoJa", "setCurrentFrame: " + frame + " (was " + currentFrame + ") thread=" + Thread.currentThread().getName());
        } catch (Throwable ignored) {
        }
        if (frame != null) {
            keypadConfigurationLocked = true;
        }
        Frame previousFrame = this.currentFrame;
        this.currentFrame = frame;
        if (frame instanceof Canvas canvas) {
            if (previousFrame != canvas) {
                currentCanvasPresentedByApplication = false;
            }
            ensureCanvasSurface(canvas);
            if (presentedCanvas != canvas || presentedFrame == null) {
                presentedFrame = snapshotCanvasImage(canvas);
            }
            presentedCanvas = canvas;
            requestRender(canvas);
        } else {
            currentCanvasPresentedByApplication = false;
            presentedCanvas = null;
            presentedFrame = null;
            repaintWindow();
        }
    }

    public Frame getCurrentFrame() {
        return currentFrame;
    }

    public void requestRepaint(Canvas canvas) {
        requestRender(new RepaintRequest(canvas, null), true);
    }

    public void requestRepaint(Canvas canvas, int x, int y, int width, int height) {
        requestRender(new RepaintRequest(canvas, new Rectangle(x, y, width, height)), true);
    }

    public void requestRender(Canvas canvas) {
        requestRender(new RepaintRequest(canvas, null), false);
    }

    private void requestRender(RepaintRequest repaint, boolean repaintRequest) {
        // Hibernating: park repaint callers so game logic loops freeze in place
        // instead of cycling on the render backpressure poll while the app is hidden.
        awaitSuspendRelease();
        Canvas canvas = repaint.canvas();
        if (!acceptsRenderRequest(canvas)) {
            return;
        }
        Runnable paintTask = () -> {
            renderThread = Thread.currentThread();
            try {
                // Re-validate at execution time. A task queued while no current
                // frame was set, or while this runtime was still alive, may
                // arrive after the session has moved on: the runtime shut down,
                // the canvas belongs to an exited session, or a different canvas
                // has since become current. Painting in any of those states
                // throws inside the application paint loop (e.g. NPE on a
                // released surface), which starves the current session's single
                // render slot and leaves the screen black. Bail out cleanly.
                if (!acceptsRenderRequest(canvas)) {
                    return;
                }
                synchronized (canvas) {
                    // Drain queued callbacks immediately before application
                    // paint code so titles observe them at a normal frame
                    // boundary, not from host input/audio threads.
                    drainApplicationCallbacks();
                    ensureCanvasSurface(canvas);
                    Graphics g = runtimeGraphics(canvas);
                    try {
                        // Runtime-driven paints must not happen on the EDT: some games keep a
                        // synchronized paint loop on their own thread and call Graphics.lock()
                        // inside that method. A separate render worker keeps window events flowing
                        // and preserves the same Canvas-monitor boundary as direct canvas loops.
                        g.lock();
                        Rectangle dirtyRegion = repaint.dirtyRegion();
                        if (dirtyRegion != null) {
                            g.setClip(dirtyRegion.x, dirtyRegion.y, dirtyRegion.width, dirtyRegion.height);
                        }
                        canvas.paint(g);
                    } finally {
                        g.unlock(true);
                        g.refreshDelegateAfterHostPaint();
                    }
                }
            } catch (Throwable throwable) {
                OpenDoJaLog.error(DoJaRuntime.class, "Unhandled canvas paint failure", throwable);
            } finally {
                releaseRenderSlot();
                replayPendingRepaint();
            }
        };
        if (shutdown.get()) {
            return;
        }
        boolean queued = renderQueued.compareAndSet(false, true);
        boolean waitForBackpressure = false;
        if (queued) {
            try {
                renderExecutor.execute(paintTask);
            } catch (RejectedExecutionException e) {
                releaseRenderSlot();
                return;
            }
        } else if (repaintRequest) {
            waitForBackpressure = rememberPendingRepaint(repaint) && shouldWaitForRepaintBackpressure(canvas);
        }
        if (waitForBackpressure) {
            awaitRenderSlotAvailable();
        }
    }

    /**
     * Accepts paint work only from a canvas created by this runtime and, once a
     * current frame is established, only from that frame. This prevents a canvas
     * leaked from an exited session from claiming the current session's single
     * render slot.
     */
    private boolean acceptsRenderRequest(Canvas canvas) {
        if (canvas == null || shutdown.get() || canvas.ownerRuntime() != this) {
            return false;
        }
        Frame current = currentFrame;
        return current == null || current == canvas;
    }

    // ---- Global game suspension (app backgrounded) ----
    // When the app leaves the foreground (HOME / task switch / screen lock) the
    // running game hibernates instead of being terminated: frame delivery, repaint
    // and event dispatch, timer ticks, and audio all park in place, so game logic
    // consumes no CPU while hidden and resumes from the exact state the user left.
    private static final Object SUSPEND_MONITOR = new Object();
    private static volatile boolean suspended;

    public static boolean isSuspended() {
        return suspended;
    }

    public static void suspendGlobal() {
        synchronized (SUSPEND_MONITOR) {
            suspended = true;
        }
    }

    public static void resumeGlobal() {
        synchronized (SUSPEND_MONITOR) {
            suspended = false;
            SUSPEND_MONITOR.notifyAll();
        }
    }

    /** Parks the calling thread while the runtime is suspended; returns
     *  immediately when the game is running normally. Used by frame delivery
     *  and audio start so hibernating games stop burning CPU in place. */
    public static void awaitSuspendRelease() {
        if (!suspended) {
            return;
        }
        synchronized (SUSPEND_MONITOR) {
            while (suspended) {
                try {
                    SUSPEND_MONITOR.wait(250L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    public static @FunctionalInterface interface PlatformPresenter {
        void accept(BufferedImage frame, int sessionId);
    }

    private static volatile PlatformPresenter platformPresenter;

    public static void setPlatformPresenter(PlatformPresenter presenter) {
        platformPresenter = presenter;
    }

    public static void setPresentDebounceEnabled(boolean enabled) {
        presentDebounceEnabled = enabled;
    }

    private static void presentPlatformFrame(BufferedImage frame, int sessionId) {
        // Hibernating: park frame delivery until the user resumes the game.
        awaitSuspendRelease();
        PlatformPresenter presenter = platformPresenter;
        try {
            Class<?> logClass = Class.forName("android.util.Log");
            java.lang.reflect.Method m = logClass.getMethod("d", String.class, String.class);
            m.invoke(null, "OpenDoJa", "presentPlatformFrame: frame=" + (frame == null ? "null" : frame.getWidth() + "x" + frame.getHeight()) + " presenter=" + (presenter == null ? "null" : "set"));
        } catch (Throwable ignored) {
        }
        if (presenter == null) {
            return;
        }
        try {
            presenter.accept(frame, sessionId);
        } catch (Throwable throwable) {
            OpenDoJaLog.error(DoJaRuntime.class, "Platform presentation failed", throwable);
        }
    }

    public void repaintWindow(int sessionId) {
        if (frameWindow != null) {
            hostPanel.repaint();
        }
        presentPlatformFrame(presentedFrame, sessionId);
    }

    public void repaintWindow() {
        repaintWindow(current != null ? current.sessionId() : -1);
    }

    public boolean isExternalFrameEnabled() {
        return externalFrameRenderer.enabled();
    }

    public void setExternalFrameEnabled(boolean enabled) {
        if (externalFrameRenderer.enabled() == enabled) {
            return;
        }
        externalFrameRenderer.setEnabled(enabled);
        if (SwingUtilities.isEventDispatchThread()) {
            hostPanel.refreshPreferredSize();
            repackWindow();
        } else {
            SwingUtilities.invokeLater(() -> {
                hostPanel.refreshPreferredSize();
                repackWindow();
            });
        }
        repaintWindow();
    }

    public void addExternalFrameOverlay(HostOverlayRenderer overlay) {
        externalFrameRenderer.addOverlay(overlay);
        repaintWindow();
    }

    public void removeExternalFrameOverlay(HostOverlayRenderer overlay) {
        externalFrameRenderer.removeOverlay(overlay);
        repaintWindow();
    }

    public boolean isOpenGlesFpsEnabled() {
        return openGlesFpsEnabled;
    }

    public void setOpenGlesFpsEnabled(boolean enabled) {
        if (openGlesFpsEnabled == enabled) {
            return;
        }
        openGlesFpsEnabled = enabled;
        if (enabled) {
            externalFrameRenderer.addOverlay(openGlesFpsOverlay);
        } else {
            externalFrameRenderer.removeOverlay(openGlesFpsOverlay);
        }
        OpenDoJaLaunchArgs.set(OpenDoJaLaunchArgs.SHOW_OPEN_GLES_FPS, Boolean.toString(enabled));
        repaintWindow();
    }

    public Path scratchpadRoot() {
        return config.scratchpadRoot();
    }

    public Path scratchpadPackedFile() {
        return config.scratchpadPackedFile();
    }

    public LaunchConfig.IAppliType iAppliType() {
        return config.iAppliType();
    }

    /**
     * Host-only dialog parent for native desktop UI shims such as IME.
     */
    public opendoja.compat.awt.Component dialogParent() {
        JFrame window = frameWindow;
        if (window != null && window.isDisplayable()) {
            return hostPanel;
        }
        return null;
    }

    public InputStream openScratchpadInput(int index, long position, long length) throws IOException {
        return scratchpadStorage.openInput(index, position, length);
    }

    public OutputStream openScratchpadOutput(int index, long position, long length) throws IOException {
        return scratchpadStorage.openOutput(index, position, length);
    }

    public int keypadState() {
        return keypadState(0);
    }

    public int keypadState(int group) {
        if (!isKeypadGroupEnabled(group)) {
            return 0;
        }
        long state = keypadStateBits;
        if (group == 0 && MINIMUM_SELECT_PRESS_NANOS > 0L && selectLatchedUntilNanos > System.nanoTime()) {
            state |= absoluteKeyBit(Display.KEY_SELECT);
        }
        int shift = group * 32;
        if (shift < 0 || shift >= Long.SIZE) {
            return 0;
        }
        return (int) ((state >>> shift) & 0xFFFF_FFFFL);
    }

    public void enableKeypadGroup(int group) {
        if (group < 0 || keypadConfigurationLocked) {
            return;
        }
        enabledKeypadGroups.add(group);
    }

    public boolean isKeypadGroupEnabled(int group) {
        return group >= 0 && enabledKeypadGroups.contains(group);
    }

    public boolean isKeypadConfigurationLocked() {
        return keypadConfigurationLocked;
    }

    public void beginModalDialog() {
        modalDialogDepth.incrementAndGet();
    }

    public void endModalDialog() {
        modalDialogDepth.updateAndGet(currentDepth -> java.lang.Math.max(0, currentDepth - 1));
    }

    public boolean isDialogShowing() {
        return modalDialogDepth.get() > 0;
    }

    public void dispatchTimerEvent(Canvas canvas, int param) {
        if (shutdown.get()) {
            return;
        }
        // Hibernating: timer events dispatched straight into application code
        // freeze together with the rest of the game.
        awaitSuspendRelease();
        if (TRACE_EVENTS) {
            OpenDoJaLog.debug(DoJaRuntime.class, () -> "timer event canvas=" + canvas.getClass().getName() + " param=" + param);
        }
        canvas.processEvent(Display.TIMER_EXPIRED_EVENT, param);
    }

    public void dispatchSyntheticKey(int dojaKey, int eventType) {
        if (isShutdown()) {
            return;
        }
        if (everShutdown) {
            return;
        }
        if (!isKeypadGroupEnabled(keyGroup(dojaKey))) {
            return;
        }
        long mask = absoluteKeyBit(dojaKey);
        if (mask == 0L) {
            return;
        }
        long now = System.nanoTime();
        if (eventType == Display.KEY_PRESSED_EVENT) {
            keypadStateBits |= mask;
            if (dojaKey == Display.KEY_SELECT && MINIMUM_SELECT_PRESS_NANOS > 0L) {
                selectLatchedUntilNanos = now + MINIMUM_SELECT_PRESS_NANOS;
            }
        } else if (eventType == Display.KEY_RELEASED_EVENT) {
            keypadStateBits &= ~mask;
        }
        if (!(currentFrame instanceof Canvas canvas)) {
            return;
        }
        dispatchCanvasUiEvent(canvas, () -> {
            if (TRACE_EVENTS) {
                OpenDoJaLog.debug(DoJaRuntime.class, () -> "key event type=" + eventType + " key=" + dojaKey + " canvas=" + canvas.getClass().getName());
            }
            canvas.processEvent(eventType, dojaKey);
            repaintWindow();
        }, eventType);
    }

    public void updatePointerPosition(int x, int y) {
        PointingDevice.setMode(PointingDevice.MODE_MOUSE);
        PointingDevice.setPosition(
                clamp(x, 0, Math.max(0, displayWidth() - 1)),
                clamp(y, 0, Math.max(0, displayHeight() - 1)));
    }

    public void dispatchPointerEvent(int eventType, int x, int y) {
        updatePointerPosition(x, y);
        if (!(currentFrame instanceof Canvas canvas)) {
            return;
        }
        dispatchCanvasUiEvent(canvas, () -> {
            if (TRACE_EVENTS) {
                OpenDoJaLog.debug(DoJaRuntime.class,
                        () -> "pointer event type=" + eventType + " x=" + x + " y=" + y
                                + " canvas=" + canvas.getClass().getName());
            }
            canvas.processEvent(eventType, 0);
            repaintWindow();
        }, eventType);
    }

    public void dispatchHostSoftKey(int softKey, int eventType) {
        if (isShutdown()) {
            return;
        }
        if (everShutdown) {
            return;
        }
        Frame frame = currentFrame;
        try {
            Class<?> logClass = Class.forName("android.util.Log");
            java.lang.reflect.Method m = logClass.getMethod("i", String.class, String.class);
            m.invoke(null, "OpenDoJa", "dispatchHostSoftKey: softKey=" + softKey + " eventType=" + eventType + " frame=" + (frame == null ? "null" : frame.getClass().getName()));
        } catch (Throwable ignored) {
        }
        if (frame == null) {
            return;
        }
        dispatchUiEvent(() -> {
            if (TRACE_EVENTS) {
        OpenDoJaLog.info(DoJaRuntime.class,
                "dispatchHostSoftKey: softKey=" + softKey + " eventType=" + eventType + " frame=" + frame.getClass().getName());
            }
            frame.processSoftKeyEvent(eventType, softKey);
            repaintWindow();
            if (frame == currentFrame && frame instanceof Canvas canvas && !currentCanvasPresentedByApplication) {
                requestRender(canvas);
            }
        });
    }

    public InputStream openResourceStream(String path) throws IOException {
        return openResourceStream(path, config, config.applicationClass().getClassLoader());
    }

    public static InputStream openLaunchResourceStream(String path) throws IOException {
        DoJaRuntime runtime = current;
        if (runtime != null) {
            return runtime.openResourceStream(path);
        }
        LaunchConfig prepared = peekPreparedLaunch();
        ClassLoader loader = prepared == null ? Thread.currentThread().getContextClassLoader()
                : prepared.applicationClass().getClassLoader();
        return openResourceStream(path, prepared, loader);
    }

    public void notifySurfaceFlush(Canvas canvas, BufferedImage frame, boolean frameBoundary) {
        int sessionId = this.sessionId;
        if (canvas == currentFrame && Thread.currentThread() != renderThread) {
            currentCanvasPresentedByApplication = true;
        }
        if (openGlesFpsEnabled) {
            openGlesFpsOverlay.recordFrame(canvas, canvasUsesOpenGles(canvas));
        }
        // Some games start a render thread from the Canvas constructor and flush direct-draw frames
        // before Display.setCurrent(canvas) runs. Preserve that latest frame so it is still visible
        // once the Canvas becomes current, instead of dropping it as "not current yet".
        if (canvas == currentFrame || currentFrame == null) {
            if (!frameBoundary && presentDebounceEnabled && !shutdown.get()) {
                // Non-boundary presents may carry frame=null ("snapshot at delivery");
                // copying the surface here on every draw call would reintroduce the
                // per-primitive full-frame copy cost the debounce exists to remove.
                deferPresent(canvas, frame);
                return;
            }
              cancelDeferredPresent();
              presentedCanvas = canvas;
              presentedFrame = frame == null ? snapshotCanvasImage(canvas) : frame;
              repaintWindow(sessionId);
          }
      }
  
      /** Queues a trailing-edge present: fire once drawing pauses, capped by a max wait. */
    private void deferPresent(Canvas canvas, BufferedImage snapshotOrNull) {
        long now = System.nanoTime();
        synchronized (deferredPresentLock) {
            if (deferredPresentFrame == null && deferredPresentCanvas == null) {
                deferredPresentFirstNanos = now;
            }
            deferredPresentFrame = snapshotOrNull;
            deferredPresentCanvas = canvas;
            long quietDeadline = now + PRESENT_DEBOUNCE_QUIET_MS * 1_000_000L;
            long maxDeadline = deferredPresentFirstNanos + PRESENT_DEBOUNCE_MAX_WAIT_MS * 1_000_000L;
            long deadline = Math.min(quietDeadline, maxDeadline);
            long delayMs = Math.max(1L, (deadline - now + 999_999L) / 1_000_000L);
            if (deferredPresentFuture != null) {
                deferredPresentFuture.cancel(false);
            }
            try {
                deferredPresentFuture = scheduler.schedule(this::drainDeferredPresent, delayMs, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException | IllegalStateException e) {
                deferredPresentFuture = null;
                deferredPresentFrame = null;
                deferredPresentCanvas = null;
            }
        }
    }

    private void cancelDeferredPresent() {
        synchronized (deferredPresentLock) {
            if (deferredPresentFuture != null) {
                deferredPresentFuture.cancel(false);
                deferredPresentFuture = null;
            }
            deferredPresentFrame = null;
            deferredPresentCanvas = null;
        }
    }

    private void drainDeferredPresent() {
        Canvas canvas;
        BufferedImage snapshot;
        synchronized (deferredPresentLock) {
            deferredPresentFuture = null;
            canvas = deferredPresentCanvas;
            snapshot = deferredPresentFrame;
            deferredPresentFrame = null;
            deferredPresentCanvas = null;
        }
        if (canvas == null || shutdown.get()) {
            return;
        }
        if (!(canvas == currentFrame || currentFrame == null)) {
            return;
        }
        presentedCanvas = canvas;
        presentedFrame = snapshot != null ? snapshot : snapshotCanvasImage(canvas);
        repaintWindow();
    }

    private void ensureCanvasSurface(Canvas canvas) {
        invokeCanvasMethod(canvas, "ensureSurface", new Class<?>[]{int.class, int.class}, displayWidth(), displayHeight());
    }

    private boolean rememberPendingRepaint(RepaintRequest repaint) {
        if (shutdown.get() || currentFrame != repaint.canvas()) {
            return false;
        }
        pendingRepaint.accumulateAndGet(repaint, RepaintRequest::merge);
        return true;
    }

    private void replayPendingRepaint() {
        RepaintRequest repaint = pendingRepaint.getAndSet(null);
        if (repaint == null) {
            return;
        }
        if (!shutdown.get() && currentFrame == repaint.canvas()) {
            requestRender(repaint, false);
        }
    }

    private boolean shouldWaitForRepaintBackpressure(Canvas canvas) {
        // Only public repaint calls are backpressured; host render wakeups stay async.
        return currentFrame == canvas
                && !shutdown.get()
                && Thread.currentThread() != renderThread
                && !Thread.holdsLock(canvas)
                && !SwingUtilities.isEventDispatchThread();
    }

    private void dispatchCanvasUiEvent(Canvas canvas, Runnable eventTask, int eventType) {
        dispatchUiEvent(() -> {
            eventTask.run();
            if (!shouldWakeCanvasAfterUiEvent(eventType)) {
                return;
            }
            if (canvas == currentFrame && !currentCanvasPresentedByApplication) {
                requestRender(canvas);
            }
        });
    }

    private static boolean shouldWakeCanvasAfterUiEvent(int eventType) {
        // Key repeat/release pairs can otherwise force duplicate paints, but touch release is often
        // the action edge for desktop pointer-driven titles and needs a host wakeup too.
        return eventType == Display.KEY_PRESSED_EVENT
                || eventType == Display.POINTER_MOVED_EVENT
                || eventType == TOUCH_PRESSED_EVENT
                || eventType == TOUCH_RELEASED_EVENT;
    }

    private void dispatchUiEvent(Runnable eventTask) {
        if (eventTask == null || shutdown.get()) {
            return;
        }
        try {
            eventExecutor.execute(() -> {
                try {
                    // Hibernating: park the event thread so queued input/UI events
                    // do not run application code while the app is hidden.
                    awaitSuspendRelease();
                    eventTask.run();
                } catch (Throwable throwable) {
                    OpenDoJaLog.error(DoJaRuntime.class, "Unhandled UI event failure", throwable);
                }
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    private void releaseRenderSlot() {
        synchronized (renderMonitor) {
            renderQueued.set(false);
            renderMonitor.notifyAll();
        }
    }

    private void awaitRenderSlotAvailable() {
        synchronized (renderMonitor) {
            while (!shutdown.get() && renderQueued.get()) {
                try {
                    renderMonitor.wait(20L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private BufferedImage getCanvasImage(Canvas canvas) {
        DesktopSurface surface = getCanvasSurface(canvas);
        if (surface == null) {
            return null;
        }
        surfaceLock.lock();
        try {
            return surface.image();
        } finally {
            surfaceLock.unlock();
        }
    }

    private boolean canvasUsesOpenGles(Canvas canvas) {
        DesktopSurface surface = getCanvasSurface(canvas);
        return surface != null && surface.hasOpenGlesActivity();
    }

    private DesktopSurface getCanvasSurface(Canvas canvas) {
        surfaceLock.lock();
        try {
            Object surface = invokeCanvasMethod(canvas, "surface", new Class<?>[0]);
            return surface instanceof DesktopSurface desktopSurface ? desktopSurface : null;
        } finally {
            surfaceLock.unlock();
        }
    }

    private BufferedImage snapshotCanvasImage(Canvas canvas) {
        BufferedImage image = getCanvasImage(canvas);
        return copyCanvasImage(image);
    }

    private Graphics runtimeGraphics(Canvas canvas) {
        Object graphics = invokeCanvasMethod(canvas, "runtimeGraphics", new Class<?>[0]);
        return (Graphics) Objects.requireNonNull(graphics, "Canvas.runtimeGraphics() returned null");
    }

    private BufferedImage copyCanvasImage(BufferedImage image) {
        if (image == null) {
            return null;
        }
        BufferedImage copy = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = copy.createGraphics();
        try {
            g2.drawImage(image, 0, 0, null);
        } finally {
            g2.dispose();
        }
        return copy;
    }

    private Object invokeCanvasMethod(Canvas canvas, String methodName, Class<?>[] parameterTypes, Object... args) {
        try {
            Method method = Canvas.class.getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            return method.invoke(canvas, args);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Failed to invoke Canvas#" + methodName, e);
        }
    }

    private static InputStream openResourceStream(String path, LaunchConfig launchConfig, ClassLoader preferredLoader) throws IOException {
        String normalized = normalizeResourcePath(path);
        String session = "none";
        DoJaRuntime currentRuntime = DoJaRuntime.current();
        if (currentRuntime != null) {
            session = String.valueOf(currentRuntime.sessionId());
        }
        // Try the Jam jar file first via JamNamedModuleResourceBridge. This opens the
        // game jar directly and is not affected by stale classloader state from
        // previous game sessions.
        Optional<InputStream> bridge = JamNamedModuleResourceBridge.openResource(normalized);
        if (bridge.isPresent()) {
            InputStream buffered = toBufferedResourceStream(bridge.get());
            logResourceLookup(session, normalized, "bridge", "found bytes=" + buffered.available());
            return buffered;
        }
        logResourceLookup(session, normalized, "bridge", "miss");
        if (preferredLoader != null) {
            InputStream preferred = preferredLoader.getResourceAsStream(normalized);
            if (preferred != null) {
                InputStream buffered = toBufferedResourceStream(preferred);
                logResourceLookup(session, normalized, "preferredLoader", "found bytes=" + buffered.available());
                return buffered;
            }
            logResourceLookup(session, normalized, "preferredLoader", "miss");
        }
        InputStream contextIn = Thread.currentThread().getContextClassLoader().getResourceAsStream(normalized);
        if (contextIn != null) {
            InputStream buffered = toBufferedResourceStream(contextIn);
            logResourceLookup(session, normalized, "contextLoader", "found bytes=" + buffered.available());
            return buffered;
        }
        logResourceLookup(session, normalized, "contextLoader", "miss");
        Path filesystemPath = Path.of(normalized);
        if (Files.exists(filesystemPath)) {
            logResourceLookup(session, normalized, "filesystem", "found");
            return new ByteArrayInputStream(Files.readAllBytes(filesystemPath));
        }
        logResourceLookup(session, normalized, "filesystem", "miss");
        Path relativeToSource = resolveRelativeToSourceUrl(launchConfig, normalized);
        if (relativeToSource != null && Files.exists(relativeToSource)) {
            logResourceLookup(session, normalized, "sourceUrl", "found path=" + relativeToSource);
            return new ByteArrayInputStream(Files.readAllBytes(relativeToSource));
        }
        logResourceLookup(session, normalized, "sourceUrl", "miss");
        OpenDoJaLog.warn(DoJaRuntime.class, "resource not found: " + path);
        throw new FileNotFoundException("Resource not found: " + path);
    }

    private static InputStream toBufferedResourceStream(InputStream raw) throws IOException {
        try (InputStream in = raw) {
            // DoJa games often treat resource streams like memory-backed blobs: available() is expected
            // to be the full remaining length, and a single read(byte[]) is expected to fill that buffer.
            return new ByteArrayInputStream(opendoja.host.ByteStreams.readAll(in));
        }
    }

    private static String normalizeResourcePath(String path) {
        String normalized = path;
        if (normalized.startsWith("resource:///")) {
            normalized = normalized.substring("resource:///".length());
        } else if (normalized.startsWith("resource://")) {
            normalized = normalized.substring("resource://".length());
        } else if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private static Path resolveRelativeToSourceUrl(LaunchConfig launchConfig, String normalized) {
        if (launchConfig == null || launchConfig.sourceUrl() == null || launchConfig.sourceUrl().isBlank()) {
            return null;
        }
        try {
            URI sourceUri = URI.create(launchConfig.sourceUrl());
            if (!"file".equalsIgnoreCase(sourceUri.getScheme())) {
                return null;
            }
            Path sourcePath = Path.of(sourceUri);
            Path base = Files.isDirectory(sourcePath) ? sourcePath : sourcePath.getParent();
            if (base == null) {
                return null;
            }
            return base.resolve(normalized).normalize();
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void prepareScratchpadStorage() {
        try {
            scratchpadStorage.initialize();
        } catch (IOException e) {
            Path packedFile = scratchpadPackedFile();
            if (packedFile != null) {
                throw new IllegalStateException("Failed to prepare packed scratchpad file " + packedFile, e);
            }
            throw new IllegalStateException("Failed to create scratchpad root " + scratchpadRoot(), e);
        }
    }

    private void createWindowIfPossible() {
        if (!shouldCreateHostWindow(GraphicsEnvironment.isHeadless(), shutdown.get())) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            // VerifyError fallback relaunch can abort startup before this queued EDT task runs.
            // If shutdown already started, do not create the stale first window at all.
            if (!shouldCreateHostWindow(false, shutdown.get())) {
                return;
            }
            JFrame window = buildHostWindow(isFullscreenHostScale());
            frameWindow = window;
            hostPanel.refreshPreferredSize();
            if (!shouldCreateHostWindow(false, shutdown.get())) {
                window.dispose();
                return;
            }
            showHostWindow(window, true);
            if (!shouldCreateHostWindow(false, shutdown.get())) {
                window.dispose();
                return;
            }
            hostPanel.requestFocusInWindow();
        });
    }

    static boolean shouldCreateHostWindow(boolean headless, boolean shutdownRequested) {
        return !headless && !shutdownRequested;
    }

    private void positionInitialWindow(Window window) {
        if (isFullscreenHostScale()) {
            return;
        }
        // GNOME/OpenJDK can ignore setLocationByPlatform(true) for non-resizable frames,
        // which leaves the host window near the primary-display origin instead of on the active screen.
        //window.setLocationByPlatform(true);
        centerOnCurrentScreen(window);
    }

    private static void centerOnCurrentScreen(Window window) {
        Rectangle usableBounds = usableScreenBounds(window);
        if (usableBounds == null) {
            return;
        }

        Dimension size = window.getSize();
        int x = usableBounds.x + (usableBounds.width - size.width) / 2;
        int y = usableBounds.y + (usableBounds.height - size.height) / 2;
        window.setLocation(x, y);
    }

    private static Rectangle usableScreenBounds(Window window) {
        if (GraphicsEnvironment.isHeadless()) {
            return null;
        }
        GraphicsConfiguration graphicsConfiguration = graphicsConfigurationFor(window);
        if (graphicsConfiguration == null) {
            return null;
        }
        Rectangle bounds = graphicsConfiguration.getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(graphicsConfiguration);
        Rectangle usable = new Rectangle(
                bounds.x + insets.left,
                bounds.y + insets.top,
                Math.max(1, bounds.width - insets.left - insets.right),
                Math.max(1, bounds.height - insets.top - insets.bottom));
        Rectangle maximumWindowBounds = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        if (maximumWindowBounds != null && usable.intersects(maximumWindowBounds)) {
            usable = usable.intersection(maximumWindowBounds);
        }
        return usable;
    }

    private static GraphicsConfiguration graphicsConfigurationFor(Window window) {
        PointerInfo pointerInfo = MouseInfo.getPointerInfo();
        if (pointerInfo != null) {
            return pointerInfo.getDevice().getDefaultConfiguration();
        }
        if (window != null && window.getGraphicsConfiguration() != null) {
            return window.getGraphicsConfiguration();
        }
        return GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice()
                .getDefaultConfiguration();
    }

    private static long absoluteKeyBit(int keyCode) {
        if (keyCode < 0 || keyCode >= Long.SIZE) {
            return 0L;
        }
        return 1L << keyCode;
    }

    private static int keyGroup(int keyCode) {
        return keyCode < 0 ? -1 : (keyCode >> 5);
    }

    private record RepaintRequest(Canvas canvas, Rectangle dirtyRegion) {
        private static RepaintRequest merge(RepaintRequest existing, RepaintRequest update) {
            if (existing == null) {
                return update;
            }
            if (update == null || existing.canvas() != update.canvas()) {
                return update;
            }
            Rectangle mergedRegion = mergeDirtyRegions(existing.dirtyRegion(), update.dirtyRegion());
            return new RepaintRequest(existing.canvas(), mergedRegion);
        }

        private static Rectangle mergeDirtyRegions(Rectangle first, Rectangle second) {
            if (first == null || second == null) {
                return null;
            }
            return first.union(second);
        }
    }

    private static String resolveHostScale(LaunchConfig config) {
        return normalizeHostScale(opendoja.host.OpenDoJaLaunchArgs.get(opendoja.host.OpenDoJaLaunchArgs.HOST_SCALE));
    }

    static int normalizeHostScale(int scale) {
        return HostScale.normalizeFixedScale(scale);
    }

    static String normalizeHostScale(String scale) {
        return HostScale.normalizeId(scale);
    }

    static boolean isWindowResizableForHostScale(String scale) {
        return HostScale.isFullscreen(normalizeHostScale(scale));
    }

    private static boolean resolveExternalFrameEnabled(LaunchConfig config) {
        return opendoja.host.OpenDoJaLaunchArgs.getBoolean(
                opendoja.host.OpenDoJaLaunchArgs.EXTERNAL_FRAME,
                config == null || config.externalFrameEnabled());
    }

    private void repackWindow() {
        if (frameWindow == null) {
            return;
        }
        applyHostWindowResizePolicy(frameWindow);
        Dimension preferred = hostPreferredSize();
        hostPanel.setSize(preferred);
        if (isFullscreenHostScale()) {
            enterFullScreenWindowIfNeeded();
            frameWindow.validate();
            return;
        }
        frameWindow.pack();
        frameWindow.validate();
    }

    private void applyHostWindowModeIfNeeded() {
        JFrame window = frameWindow;
        if (window == null) {
            return;
        }
        boolean fullscreen = isFullscreenHostScale();
        if (fullscreen && window.isUndecorated() && isFullScreenActive()) {
            return;
        }
        if (!fullscreen && !window.isUndecorated()) {
            return;
        }
        rebuildHostWindowForCurrentMode();
    }

    private void enterFullScreenWindowIfNeeded() {
        JFrame window = frameWindow;
        if (window == null) {
            return;
        }
        GraphicsDevice targetDevice = fullScreenDeviceFor(window);
        if (targetDevice == null) {
            return;
        }
        if (fullScreenDevice == targetDevice
                && targetDevice.getFullScreenWindow() == window
                && window.isUndecorated()) {
            return;
        }

        if (fullScreenDevice != null
                && fullScreenDevice != targetDevice
                && fullScreenDevice.getFullScreenWindow() == window) {
            fullScreenDevice.setFullScreenWindow(null);
        }
        window.setResizable(true);
        fullScreenDevice = targetDevice;
        Dimension preferred = fullScreenHostPanelSize(logicalHostSize());
        hostPanel.setPreferredSize(preferred);
        hostPanel.setMinimumSize(preferred);
        hostPanel.setMaximumSize(preferred);
        hostPanel.setSize(preferred);
        Rectangle screenBounds = targetDevice.getDefaultConfiguration().getBounds();
        if (screenBounds != null) {
            window.setBounds(screenBounds);
        }
        if (!shutdown.get()) {
            if (!window.isVisible()) {
                window.setVisible(true);
            }
            hostPanel.ensureControllerInput(window);
            updateFullScreenTopHint(window, true);
            targetDevice.setFullScreenWindow(window);
            window.validate();
            window.toFront();
            hostPanel.requestFocusInWindow();
            reassertFullScreenWindow(targetDevice, window);
        }
    }

    private void exitFullScreenWindowIfNeeded() {
        JFrame window = frameWindow;
        if (window == null) {
            return;
        }
        GraphicsDevice previousDevice = fullScreenDevice;
        boolean wasFullscreen = previousDevice != null && previousDevice.getFullScreenWindow() == window;
        boolean needsDecorationChange = window.isUndecorated();
        if (!wasFullscreen && !needsDecorationChange) {
            fullScreenDevice = null;
            return;
        }

        updateFullScreenTopHint(window, false);
        if (wasFullscreen) {
            previousDevice.setFullScreenWindow(null);
        }
        fullScreenDevice = null;
        window.setResizable(false);
    }

    private void rebuildHostWindowForCurrentMode() {
        JFrame previousWindow = frameWindow;
        if (previousWindow == null) {
            return;
        }
        // Linux/Swing fullscreen transitions can leave the native window carrying stale
        // fullscreen geometry/state. Rebuild the frame so the replacement starts clean.
        updateFullScreenTopHint(previousWindow, false);
        exitFullScreenWindowIfNeeded();
        previousWindow.removeWindowListener(hostWindowLifecycleListener);
        previousWindow.removeWindowFocusListener(hostWindowFocusListener);
        if (hostPanel.getParent() != null) {
            hostPanel.getParent().remove(hostPanel);
        }
        previousWindow.setVisible(false);
        previousWindow.dispose();
        if (shutdown.get()) {
            frameWindow = null;
            return;
        }
        JFrame replacement = buildHostWindow(isFullscreenHostScale());
        frameWindow = replacement;
        hostPanel.refreshPreferredSize();
        showHostWindow(replacement, true);
    }

    private Dimension hostPreferredSize() {
        if (isFullscreenHostScale()) {
            return fullScreenHostPanelSize(logicalHostSize());
        }
        return scaledHostFrameSize();
    }

    private Dimension scaledHostFrameSize() {
        return externalFrameRenderer.layoutFor(
                displayWidth(),
                displayHeight(),
                hostScaleFactor()).preferredSize();
    }

    private Dimension logicalHostSize() {
        return externalFrameRenderer.layoutFor(displayWidth(), displayHeight(), 1).preferredSize();
    }

    private double resolveHostScaleFactor(String scaleSetting) {
        String normalized = normalizeHostScale(scaleSetting);
        if (!HostScale.isFullscreen(normalized)) {
            return HostScale.fixedScaleOrDefault(normalized, MIN_HOST_SCALE);
        }
        Dimension logicalSize = logicalHostSize();
        Dimension availableSize = fullScreenHostPanelSize(logicalSize);
        return resolveFullscreenHostScale(logicalSize, availableSize);
    }

    private boolean isFullscreenHostScale() {
        return HostScale.isFullscreen(hostScaleSetting);
    }

    private Dimension fullScreenHostPanelSize(Dimension logicalSize) {
        if (isFullScreenActive() && hostPanel.getWidth() > 0 && hostPanel.getHeight() > 0) {
            return hostPanel.getSize();
        }
        Rectangle bounds = fullScreenScreenBounds();
        if (bounds == null) {
            return new Dimension(
                    Math.max(1, logicalSize.width * MAX_HOST_SCALE),
                    Math.max(1, logicalSize.height * MAX_HOST_SCALE));
        }
        return new Dimension(Math.max(1, bounds.width), Math.max(1, bounds.height));
    }

    static double resolveFullscreenHostScale(Dimension logicalSize, Dimension availableSize) {
        if (logicalSize == null || availableSize == null || logicalSize.width <= 0 || logicalSize.height <= 0) {
            return MIN_DYNAMIC_HOST_SCALE;
        }
        double widthScale = availableSize.width / (double) logicalSize.width;
        double heightScale = availableSize.height / (double) logicalSize.height;
        double scale = Math.min(widthScale, heightScale);
        if (!Double.isFinite(scale)) {
            return MIN_DYNAMIC_HOST_SCALE;
        }
        return Math.max(MIN_DYNAMIC_HOST_SCALE, scale);
    }

    static Point frameOriginForHostScale(String scaleSetting, Dimension contentSize, Dimension frameSize) {
        if (!HostScale.isFullscreen(scaleSetting) || contentSize == null || frameSize == null) {
            return new Point(0, 0);
        }
        return new Point(
                Math.max(0, (contentSize.width - frameSize.width) / 2),
                Math.max(0, (contentSize.height - frameSize.height) / 2));
    }

    private boolean isFullScreenActive() {
        JFrame window = frameWindow;
        GraphicsDevice device = fullScreenDevice;
        return window != null && device != null && device.getFullScreenWindow() == window;
    }

    private Rectangle fullScreenScreenBounds() {
        GraphicsDevice device = fullScreenDevice;
        if (device == null) {
            device = fullScreenDeviceFor(frameWindow);
        }
        if (device == null || device.getDefaultConfiguration() == null) {
            return null;
        }
        return device.getDefaultConfiguration().getBounds();
    }

    private static GraphicsDevice fullScreenDeviceFor(Window window) {
        if (GraphicsEnvironment.isHeadless()) {
            return null;
        }
        GraphicsConfiguration graphicsConfiguration = graphicsConfigurationFor(window);
        if (graphicsConfiguration == null) {
            return null;
        }
        return graphicsConfiguration.getDevice();
    }

    private JFrame buildHostWindow(boolean fullscreen) {
        JFrame window = new JFrame(config.title());
        window.setUndecorated(fullscreen);
        window.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        window.getContentPane().setBackground(Color.BLACK);
        window.setBackground(Color.BLACK);
        window.setAlwaysOnTop(false);
        window.addWindowListener(hostWindowLifecycleListener);
        window.addWindowFocusListener(hostWindowFocusListener);
        window.add(hostPanel);
        window.setResizable(fullscreen);
        return window;
    }

    private void applyHostWindowResizePolicy(JFrame window) {
        if (window == null) {
            return;
        }
        window.setResizable(isWindowResizableForHostScale(hostScaleSetting));
    }

    private void showHostWindow(JFrame window, boolean positionWindowed) {
        if (isFullscreenHostScale()) {
            enterFullScreenWindowIfNeeded();
            return;
        }
        window.pack();
        hostPanel.refreshPreferredSize();
        repackWindow();
        if (positionWindowed) {
            positionInitialWindow(window);
        }
        if (!shutdown.get()) {
            window.setVisible(true);
            hostPanel.ensureControllerInput(window);
            hostPanel.requestFocusInWindow();
        }
    }

    private void reassertFullScreenWindow(GraphicsDevice targetDevice, JFrame window) {
        SwingUtilities.invokeLater(() -> {
            if (shutdown.get() || frameWindow != window || !isFullscreenHostScale()) {
                return;
            }
            updateFullScreenTopHint(window, window.isFocused());
            targetDevice.setFullScreenWindow(window);
            window.toFront();
            hostPanel.requestFocusInWindow();
        });
    }

    private void updateFullScreenTopHint(JFrame window, boolean focused) {
        if (window == null || !window.isAlwaysOnTopSupported()) {
            return;
        }
        boolean shouldBeAlwaysOnTop = isFullscreenHostScale() && focused;
        if (window.isAlwaysOnTop() == shouldBeAlwaysOnTop) {
            return;
        }
        window.setAlwaysOnTop(shouldBeAlwaysOnTop);
    }


    private void closeShutdownResources() {
        // Also forcibly stop audio before closing other resources
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
        AutoCloseable[] resources = shutdownResources.toArray(AutoCloseable[]::new);
        OpenDoJaLog.info(DoJaRuntime.class,
                "closing " + resources.length + " shutdown resource(s)");
        for (AutoCloseable resource : resources) {
            try {
                resource.close();
            } catch (Exception exception) {
                OpenDoJaLog.error(DoJaRuntime.class,
                        "shutdown resource close failed: " + resource.getClass().getName(),
                        exception);
            } finally {
                shutdownResources.remove(resource);
            }
        }
    }

    private void applyLaunchSystemProperties() {
        previousMicroeditionPlatform = OpenDoJaLaunchArgs.get("microedition.platform", null);
        String launchPlatform = launchMicroeditionPlatform();
        if ((previousMicroeditionPlatform == null || previousMicroeditionPlatform.isBlank())
                && launchPlatform != null
                && !launchPlatform.isBlank()) {
            System.setProperty("microedition.platform", launchPlatform);
            restoreMicroeditionPlatform = true;
        }
        previousMicroeditionProfiles = OpenDoJaLaunchArgs.get("microedition.profiles", null);
        String launchProfiles = launchMicroeditionProfiles();
        if ((previousMicroeditionProfiles == null || previousMicroeditionProfiles.isBlank())
                && launchProfiles != null
                && !launchProfiles.isBlank()) {
            System.setProperty("microedition.profiles", launchProfiles);
            restoreMicroeditionProfiles = true;
        }
    }

    private void restoreLaunchSystemProperties() {
        if (restoreMicroeditionPlatform) {
            if (previousMicroeditionPlatform == null) {
                System.clearProperty("microedition.platform");
            } else {
                System.setProperty("microedition.platform", previousMicroeditionPlatform);
            }
            restoreMicroeditionPlatform = false;
            previousMicroeditionPlatform = null;
        }
        if (restoreMicroeditionProfiles) {
            if (previousMicroeditionProfiles == null) {
                System.clearProperty("microedition.profiles");
            } else {
                System.setProperty("microedition.profiles", previousMicroeditionProfiles);
            }
            restoreMicroeditionProfiles = false;
            previousMicroeditionProfiles = null;
        }
    }

    private String launchMicroeditionPlatform() {
        String override = OpenDoJaLaunchArgs.microeditionPlatformOverride();
        if (!override.isBlank()) {
            return override;
        }
        String targetDevice = config.parameters().get("TargetDevice");
        if (targetDevice != null && !targetDevice.isBlank()) {
            return targetDevice.trim();
        }
        return "openDoJa";
    }

    private String launchMicroeditionProfiles() {
        DoJaProfile resolved = DoJaProfile.fromParametersOrDocumentedDeviceIdentity(config.parameters());
        if (resolved.isKnown()) {
            return resolved.toString();
        }
        return "";
    }

    private String launchDeviceIdentity() {
        String targetDevice = config.parameters().get("TargetDevice");
        if (targetDevice != null && !targetDevice.isBlank()) {
            return targetDevice.trim();
        }
        String packageUrl = config.parameters().get("PackageURL");
        if (packageUrl != null && !packageUrl.isBlank()) {
            return packageUrl.trim();
        }
        return null;
    }

    private int mapKeyCode(int awtKeyCode) {
        HostControlAction action = DEFAULT_KEYBOARD_ACTIONS.get(awtKeyCode);
        if (action == null || action.dispatchKind() != HostControlAction.DispatchKind.DOJA_KEY) {
            return -1;
        }
        return action.dispatchCode();
    }

    private static int mapHostSoftKey(int awtKeyCode) {
        HostControlAction action = DEFAULT_KEYBOARD_ACTIONS.get(awtKeyCode);
        if (action == null || action.dispatchKind() != HostControlAction.DispatchKind.HOST_SOFT_KEY) {
            return -1;
        }
        return action.dispatchCode();
    }

    private Point hostPointToCanvas(Point hostPoint, boolean clampToDrawArea) {
        if (hostPoint == null) {
            return null;
        }
        Dimension frameSize = scaledHostFrameSize();
        Point origin = frameOriginForHostScale(hostScaleSetting(), hostPanel.getSize(), frameSize);
        ExternalFrameLayout layout = externalFrameRenderer.layoutFor(displayWidth(), displayHeight(), hostScaleFactor());
        double scale = layout.scale();
        if (!Double.isFinite(scale) || scale <= 0d) {
            return null;
        }
        Rectangle drawArea = layout.drawArea();
        if (drawArea.width <= 0 || drawArea.height <= 0) {
            return null;
        }
        double logicalX = (hostPoint.x - origin.x) / scale;
        double logicalY = (hostPoint.y - origin.y) / scale;
        if (!clampToDrawArea
                && (logicalX < drawArea.x
                || logicalY < drawArea.y
                || logicalX >= drawArea.x + drawArea.width
                || logicalY >= drawArea.y + drawArea.height)) {
            return null;
        }
        double clampedLogicalX = clamp(logicalX, drawArea.x, nextAfter(drawArea.x + drawArea.width, drawArea.x));
        double clampedLogicalY = clamp(logicalY, drawArea.y, nextAfter(drawArea.y + drawArea.height, drawArea.y));
        Point2D canvasPoint = hostPointToCanvasPoint(new Point2D.Double(clampedLogicalX, clampedLogicalY), drawArea);
        if (canvasPoint == null) {
            return null;
        }
        return new Point(
                clamp((int) Math.floor(canvasPoint.getX()), 0, Math.max(0, displayWidth() - 1)),
                clamp((int) Math.floor(canvasPoint.getY()), 0, Math.max(0, displayHeight() - 1)));
    }

    private Point2D hostPointToCanvasPoint(Point2D hostPoint, Rectangle drawArea) {
        String rotation = OpenDoJaLaunchArgs.displayRotation();
        if ("none".equals(rotation)) {
            double x = (hostPoint.getX() - drawArea.x) * displayWidth() / Math.max(1, drawArea.width);
            double y = (hostPoint.getY() - drawArea.y) * displayHeight() / Math.max(1, drawArea.height);
            return new Point2D.Double(x, y);
        }
        AffineTransform transform = new AffineTransform();
        if ("left".equals(rotation)) {
            transform.translate(drawArea.x, drawArea.y + drawArea.height);
            transform.rotate(-Math.PI / 2d);
        } else if ("right".equals(rotation)) {
            transform.translate(drawArea.x + drawArea.width, drawArea.y);
            transform.rotate(Math.PI / 2d);
        } else {
            return null;
        }
        transform.scale(
                drawArea.height / (double) Math.max(1, displayWidth()),
                drawArea.width / (double) Math.max(1, displayHeight()));
        try {
            return transform.inverseTransform(hostPoint, null);
        } catch (NoninvertibleTransformException e) {
            OpenDoJaLog.warn(DoJaRuntime.class, "Unable to invert host pointer transform", e);
            return null;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double nextAfter(double start, double direction) {
        return Math.nextAfter(start, direction);
    }

    private static final class HostPanel extends JPanel {
        private final DoJaRuntime runtime;
        private final DesktopKeyInputAdapter keyInputAdapter;
        private final DesktopKeyInputAdapter softKeyInputAdapter;
        private final HostInputRouter inputRouter;
        private volatile ControllerInputManager controllerInputManager;
        private boolean touchPointerActive;

        private HostPanel(DoJaRuntime runtime) {
            this.runtime = runtime;
            this.keyInputAdapter = new DesktopKeyInputAdapter(this::scheduleRelease, runtime::dispatchSyntheticKey,
                    KEY_REPEAT_RELEASE_DEBOUNCE_MS);
            this.softKeyInputAdapter = new DesktopKeyInputAdapter(this::scheduleRelease, runtime::dispatchHostSoftKey,
                    KEY_REPEAT_RELEASE_DEBOUNCE_MS);
            this.inputRouter = new HostInputRouter(HostKeybindProfile.fromLaunchArgs());
            refreshPreferredSize();
            setBackground(Color.BLACK);
            setOpaque(true);
            setFocusable(true);
            installHostScalePopup();
            addFocusListener(new FocusAdapter() {
                @Override
                public void focusLost(FocusEvent e) {
                    keyInputAdapter.releaseAll();
                    softKeyInputAdapter.releaseAll();
                    touchPointerActive = false;
                }
            });
            addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    dispatchKey(e);
                }

                @Override
                public void keyReleased(KeyEvent e) {
                    dispatchKey(e);
                }

                private void dispatchKey(KeyEvent event) {
                    HostControlAction action = inputRouter.keyboardAction(event.getKeyCode());
                    if (action == null) {
                        return;
                    }
                    dispatchMappedAction(action, event.getID() == KeyEvent.KEY_PRESSED);
                    event.consume();
                }
            });
        }

        private void ensureControllerInput(JFrame ownerWindow) {
            if (controllerInputManager != null || GraphicsEnvironment.isHeadless()) {
                return;
            }
            ControllerInputManager manager = new ControllerInputManager(ownerWindow);
            manager.addListener(new ControllerInputListener() {
                @Override
                public void onInput(ControllerInputEvent event) {
                    SwingUtilities.invokeLater(() -> dispatchControllerEvent(event));
                }
            });
            runtime.registerShutdownResource(manager);
            controllerInputManager = manager;
        }

        private DesktopKeyInputAdapter.PendingRelease scheduleRelease(int delayMillis, Runnable task) {
            Timer timer = new Timer(java.lang.Math.max(0, delayMillis), e -> task.run());
            timer.setRepeats(false);
            timer.start();
            return timer::stop;
        }

        private void dispatchControllerEvent(ControllerInputEvent event) {
            if (event == null || !isShowing()) {
                return;
            }
            Window window = SwingUtilities.getWindowAncestor(this);
            if (window != null && !window.isFocused()) {
                return;
            }
            HostControlAction action = inputRouter.controllerAction(event);
            if (action == null) {
                return;
            }
            dispatchMappedAction(action, event.active());
        }

        private void dispatchMappedAction(HostControlAction action, boolean pressed) {
            if (action == HostControlAction.EXIT_GAME) {
                if (pressed) {
                    new Thread(() -> runtime.shutdown(), "exit-game").start();
                }
                return;
            }
            if (action.dispatchKind() == HostControlAction.DispatchKind.HOST_SOFT_KEY) {
                int softKey = action.dispatchCode();
                if (pressed) {
                    softKeyInputAdapter.keyPressed(softKey);
                } else {
                    softKeyInputAdapter.keyReleased(softKey);
                }
                return;
            }
            int dojaKey = action.dispatchCode();
            if (pressed) {
                keyInputAdapter.keyPressed(dojaKey);
            } else {
                keyInputAdapter.keyReleased(dojaKey);
            }
        }

        private void installHostScalePopup() {
            MouseAdapter popupListener = new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent event) {
                    if (SwingUtilities.isLeftMouseButton(event) && event.getClickCount() == 2) {
                        runtime.setHostScale(MIN_HOST_SCALE);
                        HostPanel.this.requestFocusInWindow();
                    }
                }

                @Override
                public void mousePressed(MouseEvent event) {
                    if (maybeShowPopup(event)) {
                        return;
                    }
                    dispatchPointerPressed(event);
                }

                @Override
                public void mouseReleased(MouseEvent event) {
                    if (maybeShowPopup(event)) {
                        touchPointerActive = false;
                        return;
                    }
                    dispatchPointerReleased(event);
                }

                @Override
                public void mouseDragged(MouseEvent event) {
                    dispatchPointerMoved(event, touchPointerActive);
                }

                @Override
                public void mouseMoved(MouseEvent event) {
                    dispatchPointerMoved(event, false);
                }
            };
            addMouseListener(popupListener);
            addMouseMotionListener(popupListener);
        }

        private void dispatchPointerPressed(MouseEvent event) {
            if (!SwingUtilities.isLeftMouseButton(event)) {
                return;
            }
            Point canvasPoint = runtime.hostPointToCanvas(event.getPoint(), false);
            if (canvasPoint == null) {
                return;
            }
            touchPointerActive = true;
            runtime.dispatchPointerEvent(TOUCH_PRESSED_EVENT, canvasPoint.x, canvasPoint.y);
            HostPanel.this.requestFocusInWindow();
            event.consume();
        }

        private void dispatchPointerReleased(MouseEvent event) {
            if (!touchPointerActive || !SwingUtilities.isLeftMouseButton(event)) {
                return;
            }
            Point canvasPoint = runtime.hostPointToCanvas(event.getPoint(), true);
            touchPointerActive = false;
            if (canvasPoint == null) {
                return;
            }
            runtime.dispatchPointerEvent(TOUCH_RELEASED_EVENT, canvasPoint.x, canvasPoint.y);
            event.consume();
        }

        private void dispatchPointerMoved(MouseEvent event, boolean dispatchMoveEvent) {
            Point canvasPoint = runtime.hostPointToCanvas(event.getPoint(), dispatchMoveEvent);
            if (canvasPoint == null) {
                return;
            }
            if (dispatchMoveEvent) {
                runtime.dispatchPointerEvent(Display.POINTER_MOVED_EVENT, canvasPoint.x, canvasPoint.y);
            } else {
                runtime.updatePointerPosition(canvasPoint.x, canvasPoint.y);
            }
        }

        private boolean maybeShowPopup(MouseEvent event) {
            if (!event.isPopupTrigger()) {
                return false;
            }
            JPopupMenu menu = buildHostScalePopup();
            menu.show(event.getComponent(), event.getX(), event.getY());
            return true;
        }

        private JPopupMenu buildHostScalePopup() {
            JPopupMenu menu = new JPopupMenu();
            JCheckBoxMenuItem showOpenGlesFpsItem = new JCheckBoxMenuItem("Show OpenGLES FPS");
            showOpenGlesFpsItem.setSelected(runtime.isOpenGlesFpsEnabled());
            showOpenGlesFpsItem.addActionListener(event -> {
                runtime.setOpenGlesFpsEnabled(showOpenGlesFpsItem.isSelected());
                HostPanel.this.requestFocusInWindow();
            });
            menu.add(showOpenGlesFpsItem);
            menu.addSeparator();
            ButtonGroup group = new ButtonGroup();
            for (int scale = MIN_HOST_SCALE; scale <= MAX_HOST_SCALE; scale++) {
                int selectedScale = scale;
                JRadioButtonMenuItem item = new JRadioButtonMenuItem(new AbstractAction((scale * 100) + "%") {
                    @Override
                    public void actionPerformed(ActionEvent event) {
                        runtime.setHostScale(selectedScale);
                        HostPanel.this.requestFocusInWindow();
                    }
                });
                item.setSelected(!HostScale.isFullscreen(runtime.hostScaleSetting())
                        && HostScale.fixedScaleOrDefault(runtime.hostScaleSetting(), MIN_HOST_SCALE) == scale);
                group.add(item);
                menu.add(item);
            }
            JRadioButtonMenuItem fullscreenItem = new JRadioButtonMenuItem(new AbstractAction(HostScale.label(HostScale.FULLSCREEN_ID)) {
                @Override
                public void actionPerformed(ActionEvent event) {
                    runtime.setHostScale(HostScale.FULLSCREEN_ID);
                    HostPanel.this.requestFocusInWindow();
                }
            });
            fullscreenItem.setSelected(HostScale.isFullscreen(runtime.hostScaleSetting()));
            group.add(fullscreenItem);
            menu.add(fullscreenItem);
            return menu;
        }

        private void refreshPreferredSize() {
            Dimension preferred = runtime.hostPreferredSize();
            setPreferredSize(preferred);
            setMinimumSize(preferred);
            setMaximumSize(preferred);
            setSize(preferred);
            revalidate();
        }

        @Override
        public void paintComponent(opendoja.compat.awt.Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                Dimension frameSize = runtime.scaledHostFrameSize();
                Point origin = frameOriginForHostScale(runtime.hostScaleSetting(), getSize(), frameSize);
                g2.translate(origin.x, origin.y);
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                runtime.externalFrameRenderer.paint(g2, runtime.currentFrame, runtime.presentedFrame,
                        runtime.displayWidth(), runtime.displayHeight(), runtime.hostScaleFactor());
            } finally {
                g2.dispose();
            }
        }
    }
}
