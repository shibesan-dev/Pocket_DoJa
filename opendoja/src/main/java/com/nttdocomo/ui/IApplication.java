package com.nttdocomo.ui;

import com.nttdocomo.util.ScheduleDate;
import opendoja.host.DoJaRuntime;
import opendoja.host.LaunchConfig;
import opendoja.host.OpenDoJaLog;
import opendoja.host.system.DoJaExternalActionSupport;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Provides the template for an application.
 * Applications must extend this class.
 * This class defines the normal application lifecycle and provides access to
 * the download-source URL, ADF parameters, launch type, suspend information,
 * and automatic-launch settings.
 */
public abstract class IApplication {
    private static final boolean TRACE_FAILURES = opendoja.host.OpenDoJaLaunchArgs.getBoolean(opendoja.host.OpenDoJaLaunchArgs.TRACE_FAILURES);
    /** Launch type indicating that the application was started from the menu (=0). */
    public static final int LAUNCHED_FROM_MENU = 0;
    /** Launch type indicating that the application was started immediately after download (=1). */
    public static final int LAUNCHED_AFTER_DOWNLOAD = 1;
    /** Launch type indicating that the application was started by a timer (=2). */
    public static final int LAUNCHED_FROM_TIMER = 2;
    /** Launch type indicating concierge startup (=3). */
    public static final int LAUNCHED_AS_CONCIERGE = 3;
    /** Launch type indicating startup from an external function (=4). */
    public static final int LAUNCHED_FROM_EXT = 4;
    /** Launch type indicating startup from the browser (=5). */
    public static final int LAUNCHED_FROM_BROWSER = 5;
    /** Launch type indicating startup from the mailer (=6). */
    public static final int LAUNCHED_FROM_MAILER = 6;
    /** Launch type indicating startup from another i-appli (=7). */
    public static final int LAUNCHED_FROM_IAPPLI = 7;
    /** Launch type indicating startup from the launcher mode (=8). */
    public static final int LAUNCHED_FROM_LAUNCHER = 8;
    /** Launch type indicating startup as an i-let (=9). */
    public static final int LAUNCHED_AS_ILET = 9;
    /** Launch type indicating startup for a received message (=10). */
    public static final int LAUNCHED_MSG_RECEIVED = 10;
    /** Launch type indicating startup for a sent message (=11). */
    public static final int LAUNCHED_MSG_SENT = 11;
    /** Launch type indicating startup for an unsent message (=12). */
    public static final int LAUNCHED_MSG_UNSENT = 12;
    /** Launch type indicating startup from location information (=13). */
    public static final int LAUNCHED_FROM_LOCATION_INFO = 13;
    /** Launch type indicating startup from a location image (=14). */
    public static final int LAUNCHED_FROM_LOCATION_IMAGE = 14;
    /** Launch type indicating startup from the phone book (=15). */
    public static final int LAUNCHED_FROM_PHONEBOOK = 15;
    /** Launch type indicating startup from a digital television application (=17). */
    public static final int LAUNCHED_FROM_DTV = 17;
    /** Launch type indicating startup from ToruCa linkage (=18). */
    public static final int LAUNCHED_FROM_TORUCA = 18;
    /** Launch type indicating startup from FeliCa ad-hoc transfer (=19). */
    public static final int LAUNCHED_FROM_FELICA_ADHOC = 19;
    /** Launch type indicating startup from the IC application deletion screen (=20). */
    public static final int LAUNCHED_FROM_MENU_FOR_DELETION = 20;
    /** Launch type indicating startup from BML linkage (=21). */
    public static final int LAUNCHED_FROM_BML = 21;
    /** Launch target indicating browser startup (=1). */
    public static final int LAUNCH_BROWSER = 1;
    /** Launch target indicating automatic version-up startup (=2). */
    public static final int LAUNCH_VERSIONUP = 2;
    /** Launch target indicating i-appli linkage startup (=3). */
    public static final int LAUNCH_IAPPLI = 3;
    /** Launch target indicating i-appli launcher-mode startup (=4). */
    public static final int LAUNCH_AS_LAUNCHER = 4;
    /** Launch target indicating mailer-menu startup (=5). */
    public static final int LAUNCH_MAILMENU = 5;
    /** Launch target indicating scheduler startup (=6). */
    public static final int LAUNCH_SCHEDULER = 6;
    /** Launch target indicating startup of the received-mail view (=7). */
    public static final int LAUNCH_MAIL_RECEIVED = 7;
    /** Launch target indicating startup of the sent-mail view (=8). */
    public static final int LAUNCH_MAIL_SENT = 8;
    /** Launch target indicating startup of the unsent-mail view (=9). */
    public static final int LAUNCH_MAIL_UNSENT = 9;
    /** Launch target indicating startup of the last incoming mail (=10). */
    public static final int LAUNCH_MAIL_LAST_INCOMING = 10;
    /** Launch target indicating digital television startup (=12). */
    public static final int LAUNCH_DTV = 12;
    /** Launch target indicating browser startup while the i-appli is suspended (=13). */
    public static final int LAUNCH_BROWSER_SUSPEND = 13;
    /** Suspend-information bit indicating suspension triggered by native behavior (=1). */
    public static final int SUSPEND_BY_NATIVE = 1;
    /** Suspend-information bit indicating suspension triggered by the application (=2). */
    public static final int SUSPEND_BY_IAPP = 2;
    /** Suspend-information bit indicating packet arrival during suspension (=256). */
    public static final int SUSPEND_PACKETIN = 256;
    /** Suspend-information bit indicating an outgoing call during suspension (=512). */
    public static final int SUSPEND_CALL_OUT = 512;
    /** Suspend-information bit indicating an incoming call during suspension (=1024). */
    public static final int SUSPEND_CALL_IN = 1024;
    /** Suspend-information bit indicating mail sending during suspension (=2048). */
    public static final int SUSPEND_MAIL_SEND = 2048;
    /** Suspend-information bit indicating mail reception during suspension (=4096). */
    public static final int SUSPEND_MAIL_RECEIVE = 4096;
    /** Suspend-information bit indicating message reception during suspension (=8192). */
    public static final int SUSPEND_MESSAGE_RECEIVE = 8192;
    /** Suspend-information bit indicating schedule notification during suspension (=16384). */
    public static final int SUSPEND_SCHEDULE_NOTIFY = 16384;
    /** Suspend-information bit indicating a multitask application event during suspension (=32768). */
    public static final int SUSPEND_MULTITASK_APPLICATION = 32768;

    private static volatile IApplication currentApp;

    private static final String[] NO_ARGS = new String[0];

    private final String[] args;
    private final Map<String, String> parameters;
    private final String sourceUrl;
    private final int launchType;
    private final Map<Integer, ScheduleDate> launchTimes = new HashMap<>();
    private final PushManager pushManager = new PushManager();
    private boolean moved;
    private boolean movedFromOtherTerminal;
    private int suspendInfo;

    /**
     * Applications must not instantiate subclasses directly.
     * The application runtime creates the instance.
     */
    public IApplication() {
        LaunchConfig config = DoJaRuntime.consumePreparedLaunch();
        if (config == null) {
            this.args = NO_ARGS;
            this.parameters = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            this.sourceUrl = null;
            this.launchType = LAUNCHED_FROM_MENU;
        } else {
            String[] configuredArgs = config.args();
            this.args = configuredArgs == null ? NO_ARGS : configuredArgs;
            this.parameters = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            this.parameters.putAll(config.parameters());
            this.sourceUrl = config.sourceUrl();
            this.launchType = config.launchType();
        }
        currentApp = this;
        DoJaRuntime.bindPreparedApplication(this);
    }

    /**
     * Gets the currently running application object.
     *
     * @return the current application object
     */
    public static IApplication getCurrentApp() {
        return currentApp;
    }

    /** Clears the current application reference so a stale app cannot leak into a new session. */
    public static void clearCurrentApp() {
        currentApp = null;
    }

    /**
     * Gets the startup arguments passed to this application.
     *
     * @return a copy of the startup arguments
     */
    public final String[] getArgs() {
        return args.clone();
    }

    /**
     * Gets an ADF parameter value.
     *
     * @param key the parameter key
     * @return the parameter value, or {@code null}
     */
    public final String getParameter(String key) {
        return parameters.get(key);
    }

    /**
     * Starts the application.
     */
    public abstract void start();

    /**
     * Called immediately after the application returns from suspension.
     * The default implementation does nothing.
     */
    public void resume() {
        return;
    }

    /**
     * Terminates the current application.
     */
    /**
     * Terminates the current application.
     * <p>
     * DoJa games call this to exit, but some games use it as flow control
     * (e.g. returning to a menu after an error) rather than a true application
     * exit. Suppressing the actual shutdown here prevents games from
     * unintentionally terminating the emulator session. Users should use the
     * emulator's "Exit Game" button to exit properly, which performs a full
     * session reset matching force-stop behavior.
     */
    public final void terminate() {
        try {
            Class<?> logClass = Class.forName("android.util.Log");
            java.lang.reflect.Method m = logClass.getMethod("i", String.class, String.class);
            DoJaRuntime runtime = DoJaRuntime.current();
            String sessionId = runtime == null ? "none" : String.valueOf(runtime.sessionId());
            boolean alreadyShuttingDown = runtime != null && runtime.isShutdownRequested();
            int keypadState = runtime != null ? runtime.keypadState() : -1;
            boolean bit4Set = (keypadState & 0x10) != 0;
            long ts = System.nanoTime();
            String threadName = Thread.currentThread().getName();
            StringBuilder sb = new StringBuilder("IApplication.terminate() SUPPRESSED, game=" + getClass().getName()
                    + " session=" + sessionId + " thread=" + threadName
                    + " runtimeAlreadyShuttingDown=" + alreadyShuttingDown
                    + " tsNs=" + ts
                    + " keypadState=0x" + Integer.toHexString(keypadState) + " (" + Integer.toBinaryString(keypadState) + ")"
                    + " bit4Set(0x10)=" + bit4Set
                    + " display=" + (Display.getCurrent() == null ? "null" : Display.getCurrent().getWidth() + "x" + Display.getCurrent().getHeight())
                    + " sourceUrl=" + sourceUrl
                    + " launchType=" + launchType);
            for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
                sb.append("\n  at ").append(e);
            }
            m.invoke(null, "OpenDoJa", sb.toString());
        } catch (Throwable ignored) {
        }
        // Intentionally do NOT call runtime.shutdown() — suppress game-initiated
        // termination so the game continues running. Use "Exit Game" to exit properly.
    }

    public String getUtn() {
        return opendoja.host.OpenDoJaIdentity.terminalId();
    }

    /**
     * Gets the source URL from JAM.
     *
     * @return the source URL, or {@code null}
     */
    public final String getSourceURL() {
        return sourceUrl;
    }

    /**
     * Gets the launch type.
     *
     * @return the launch type of the current startup
     */
    public final int getLaunchType() {
        return launchType;
    }

    /**
     * Launches another application from a Java application.
     * The meaning of {@code args} depends on {@code type}.
     *
     * @param type the kind of application to launch
     * @param args the arguments passed to the application to launch
     */
    public final void launch(int type, String[] args) {
        DoJaExternalActionSupport.launch(type, args, sourceUrl);
    }

    /**
     * Gets information about why the application was suspended and what
     * occurred during suspension.
     *
     * @return the suspend-information bit set
     */
    public int getSuspendInfo() {
        return suspendInfo;
    }

    /**
     * Sets an automatic-launch time for this application.
     *
     * @param kind the launch-time slot
     * @param launchTime the launch time to set
     */
    public void setLaunchTime(int kind, ScheduleDate launchTime) {
        launchTimes.put(kind, launchTime);
    }

    /**
     * Gets an automatic-launch time that was set for this application.
     *
     * @param kind the launch-time slot
     * @return the configured launch time, or {@code null}
     */
    public ScheduleDate getLaunchTime(int kind) {
        return launchTimes.get(kind);
    }

    /**
     * Gets the push manager for this application.
     *
     * @return the push manager
     * @throws SecurityException if access to the push manager is not permitted
     */
    public PushManager getPushManager() throws SecurityException {
        return pushManager;
    }

    /**
     * Tests whether the application has been moved.
     *
     * @return {@code true} if the application has been moved, or
     *         {@code false} otherwise
     */
    public final boolean isMoved() {
        return moved;
    }

    /**
     * Clears the moved flag.
     */
    public final void clearMoved() {
        moved = false;
    }

    /**
     * Tests whether the application was moved from another terminal.
     *
     * @return {@code true} if the application was moved from another terminal,
     *         or {@code false} otherwise
     */
    public final boolean isMovedFromOtherTerminal() {
        return movedFromOtherTerminal;
    }
}
