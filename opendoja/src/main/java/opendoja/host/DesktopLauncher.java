package opendoja.host;

import com.nttdocomo.ui.IApplication;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

public final class DesktopLauncher {
    private DesktopLauncher() {
    }

    public static IApplication launch(Class<? extends IApplication> applicationClass, String... args) {
        LaunchConfig config = LaunchConfig.builder(applicationClass)
                .args(args)
                .build();
        return launch(config);
    }

    public static IApplication launch(LaunchConfig config) {
        DoJaRuntime runtime = null;
        boolean launched = false;
        ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
        // JAM-loaded titles expect ordinary context-loader resource lookups to resolve against the app jar.
        Thread.currentThread().setContextClassLoader(config.applicationClass().getClassLoader());
        try {
            DoJaRuntime.prepareLaunch(config);
            runtime = DoJaRuntime.bootstrap(config);
            IApplication app = config.applicationClass().getDeclaredConstructor().newInstance();
            runtime.attachApplication(app);
            runtime.startApplication();
            launched = true;
            return app;
        } catch (InvocationTargetException e) {
            Throwable target = e.getTargetException();
            String gameId = config.applicationClass().getName();
            String sourceUrl = config.sourceUrl();
            if (target instanceof Error error) {
                OpenDoJaLog.error(DesktopLauncher.class,
                        "Original launch failure before abortLaunch: gameId=" + gameId
                                + " sourceUrl=" + sourceUrl + " thread=" + Thread.currentThread().getName(),
                        error);
                throw error;
            }
            if (target instanceof RuntimeException runtimeException) {
                OpenDoJaLog.error(DesktopLauncher.class,
                        "Original launch failure before abortLaunch: gameId=" + gameId
                                + " sourceUrl=" + sourceUrl + " thread=" + Thread.currentThread().getName(),
                        runtimeException);
                throw runtimeException;
            }
            IllegalStateException wrapped = new IllegalStateException("Failed to launch " + gameId, target);
            OpenDoJaLog.error(DesktopLauncher.class,
                    "Original launch failure before abortLaunch: gameId=" + gameId
                            + " sourceUrl=" + sourceUrl + " thread=" + Thread.currentThread().getName(),
                    wrapped);
            throw wrapped;
        } catch (ReflectiveOperationException e) {
            OpenDoJaLog.error(DesktopLauncher.class,
                    "Original launch failure before abortLaunch: gameId=" + config.applicationClass().getName()
                            + " sourceUrl=" + config.sourceUrl() + " thread=" + Thread.currentThread().getName(),
                    e);
            throw new IllegalStateException("Failed to launch " + config.applicationClass().getName(), e);
        } catch (Throwable t) {
            String gameId = config.applicationClass().getName();
            String sourceUrl = config.sourceUrl();
            String sessionId = "none";
            try {
                DoJaRuntime currentRuntime = DoJaRuntime.current();
                if (currentRuntime != null) {
                    sessionId = String.valueOf(currentRuntime.sessionId());
                }
            } catch (Throwable ignored) {
            }
            OpenDoJaLog.error(DesktopLauncher.class,
                    "Original launch failure before abortLaunch: gameId=" + gameId
                            + " sourceUrl=" + sourceUrl + " sessionId=" + sessionId
                            + " thread=" + Thread.currentThread().getName(),
                    t);
            throw t;
        } finally {
            if (!launched && runtime != null) {
                try {
                    runtime.abortLaunch();
                } catch (Throwable cleanup) {
                    OpenDoJaLog.warn(DesktopLauncher.class,
                            "abortLaunch cleanup threw while preserving original launch failure: gameId="
                                    + config.applicationClass().getName() + " thread=" + Thread.currentThread().getName(),
                            cleanup);
                }
            }
            DoJaRuntime.clearPreparedLaunch();
            Thread.currentThread().setContextClassLoader(previousLoader);
        }
    }

    public static void main(String[] args) throws ClassNotFoundException {
        List<String> effectiveArgs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (OpenDoJaCliFlags.PHONE_MODEL.equals(args[i])) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException(
                            "Usage: DesktopLauncher [" + OpenDoJaCliFlags.PHONE_MODEL + " <model>] ["
                                    + OpenDoJaCliFlags.SCREEN_ROTATION + " <" + OpenDoJaLaunchArgs.displayRotationChoices() + ">] ["
                                    + OpenDoJaCliFlags.SHOW_OPEN_GLES_FPS
                                    + "] <fully.qualified.IApplicationClass> [args...]");
                }
                OpenDoJaLaunchArgs.set(OpenDoJaLaunchArgs.MICROEDITION_PLATFORM_OVERRIDE, args[++i]);
                continue;
            }
            if (OpenDoJaCliFlags.SCREEN_ROTATION.equals(args[i])) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException(
                            "Usage: DesktopLauncher [" + OpenDoJaCliFlags.PHONE_MODEL + " <model>] ["
                                    + OpenDoJaCliFlags.SCREEN_ROTATION + " <" + OpenDoJaLaunchArgs.displayRotationChoices() + ">] ["
                                    + OpenDoJaCliFlags.SHOW_OPEN_GLES_FPS
                                    + "] <fully.qualified.IApplicationClass> [args...]");
                }
                OpenDoJaLaunchArgs.set(OpenDoJaLaunchArgs.DISPLAY_ROTATION, requireDisplayRotation(args[++i]));
                continue;
            }
            if (OpenDoJaCliFlags.SHOW_OPEN_GLES_FPS.equals(args[i])) {
                OpenDoJaLaunchArgs.set(OpenDoJaLaunchArgs.SHOW_OPEN_GLES_FPS, Boolean.TRUE.toString());
                continue;
            }
            effectiveArgs.add(args[i]);
        }
        if (effectiveArgs.isEmpty()) {
            throw new IllegalArgumentException(
                    "Usage: DesktopLauncher [" + OpenDoJaCliFlags.PHONE_MODEL + " <model>] ["
                            + OpenDoJaCliFlags.SCREEN_ROTATION + " <" + OpenDoJaLaunchArgs.displayRotationChoices() + ">] ["
                            + OpenDoJaCliFlags.SHOW_OPEN_GLES_FPS
                            + "] <fully.qualified.IApplicationClass> [args...]");
        }
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        Class<?> rawClass = contextLoader == null
                ? Class.forName(effectiveArgs.get(0), false, DesktopLauncher.class.getClassLoader())
                : Class.forName(effectiveArgs.get(0), false, contextLoader);
        if (!IApplication.class.isAssignableFrom(rawClass)) {
            throw new IllegalArgumentException(effectiveArgs.get(0) + " does not extend com.nttdocomo.ui.IApplication");
        }
        @SuppressWarnings("unchecked")
        Class<? extends IApplication> applicationClass = (Class<? extends IApplication>) rawClass;
        String[] appArgs = new String[Math.max(0, effectiveArgs.size() - 1)];
        if (appArgs.length > 0) {
            for (int i = 1; i < effectiveArgs.size(); i++) {
                appArgs[i - 1] = effectiveArgs.get(i);
            }
        }
        LaunchConfig config = LaunchConfig.builder(applicationClass)
                .args(appArgs)
                .exitOnShutdown(true)
                .build();
        launch(config);
        DoJaRuntime runtime = DoJaRuntime.current();
        if (runtime != null) {
            try {
                runtime.awaitShutdown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        System.exit(0);
    }

    private static String requireDisplayRotation(String value) {
        String normalized = OpenDoJaLaunchArgs.normalizeDisplayRotation(value);
        if (!normalized.equals(value == null ? null : value.trim().toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException(
                    "Unknown screen rotation: " + value + ". Expected "
                            + OpenDoJaLaunchArgs.displayRotationChoicesText() + ".");
        }
        return normalized;
    }
}
