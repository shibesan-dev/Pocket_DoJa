package opendoja.host;

import com.nttdocomo.ui.IApplication;
import opendoja.host.compat.GameCompatibility;
import opendoja.host.compat.GameIdentity;
import opendoja.host.compat.GameProfiles;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public final class JamLauncher {
    private JamLauncher() {
    }

    public static IApplication launch(Path jamPath) throws IOException, ClassNotFoundException {
        return launch(jamPath, false, LaunchConfig.LaunchTypeOption.resolveConfigured());
    }

    public static IApplication launch(Path jamPath, boolean exitOnShutdown) throws IOException, ClassNotFoundException {
        return launch(jamPath, exitOnShutdown, LaunchConfig.LaunchTypeOption.resolveConfigured());
    }

    public static IApplication launch(Path jamPath, LaunchConfig.LaunchTypeOption launchType)
            throws IOException, ClassNotFoundException {
        return launch(jamPath, false, launchType);
    }

    public static IApplication launch(Path jamPath, boolean exitOnShutdown, LaunchConfig.LaunchTypeOption launchType)
            throws IOException, ClassNotFoundException {
        return DesktopLauncher.launch(buildLaunchConfig(jamPath, exitOnShutdown, launchType));
    }
    public static LaunchConfig buildLaunchConfig(Path jamPath, boolean exitOnShutdown)
            throws IOException, ClassNotFoundException {
        return buildLaunchConfig(jamPath, exitOnShutdown, LaunchConfig.LaunchTypeOption.resolveConfigured(), null);
    }

    public static LaunchConfig buildLaunchConfig(Path jamPath, boolean exitOnShutdown, Path scratchpadRoot)
            throws IOException, ClassNotFoundException {
        return buildLaunchConfig(jamPath, exitOnShutdown, LaunchConfig.LaunchTypeOption.resolveConfigured(), scratchpadRoot);
    }

    public static LaunchConfig buildLaunchConfig(Path jamPath, boolean exitOnShutdown, LaunchConfig.LaunchTypeOption launchType)
            throws IOException, ClassNotFoundException {
        return buildLaunchConfig(jamPath, exitOnShutdown, launchType, null);
    }

    public static LaunchConfig buildLaunchConfig(Path jamPath, boolean exitOnShutdown, LaunchConfig.LaunchTypeOption launchType, Path scratchpadRoot)
            throws IOException, ClassNotFoundException {
        Properties properties = JamMetadataResolver.loadJamProperties(jamPath);
        return buildLaunchConfig(jamPath, properties, exitOnShutdown, launchType, scratchpadRoot);
    }

    private static LaunchConfig buildLaunchConfig(Path jamPath, Properties properties, boolean exitOnShutdown,
                                                  LaunchConfig.LaunchTypeOption launchType, Path scratchpadRoot)
            throws IOException, ClassNotFoundException {
        String appClassName = properties.getProperty("AppClass");
        if (appClassName == null || appClassName.isBlank()) {
            throw new IllegalArgumentException("JAM/ADF missing AppClass: " + jamPath);
        }
        Path gameJarPath = locateGameJarIfPresent(jamPath, properties);
        opendoja.host.OpenDoJaPaths.setGameJarPath(gameJarPath);
        GameIdentity gameIdentity = GameIdentity.from(jamPath, properties, gameJarPath);
        GameCompatibility compatibility = GameProfiles.resolve(gameIdentity);
        OpenDoJaLog.debug(JamLauncher.class,
                () -> "compat identity " + gameIdentity.summary()
                        + " profile=" + (compatibility.profileId() == null ? "-" : compatibility.profileId()));
        seedGameStorage(gameJarPath);
        // Install the bridge before any app code runs so bootstrap-owned Class.getResource*
        // lookups can still fall back to the selected JAM jar.
        JamNamedModuleResourceBridge.install(gameJarPath);
        // Legacy titles ask Java for "SJIS"/"Shift_JIS", but DoJa's mapping is not identical to
        // the desktop JDK binding. Install the OpenDoJa-owned SJIS_i provider before app code
        // runs, and disable the built-in Shift_JIS binding so those names resolve to it.
        DoJaSjisCompatibility.install();
        Class<?> rawClass = loadApplicationClass(appClassName.trim(), gameJarPath);
        if (!IApplication.class.isAssignableFrom(rawClass)) {
            throw new IllegalArgumentException("AppClass does not extend IApplication: " + appClassName);
        }
        @SuppressWarnings("unchecked")
        Class<? extends IApplication> applicationClass = (Class<? extends IApplication>) rawClass;
        int[] scratchpadSizes = parseScratchpadSizes(properties.getProperty("SPsize"));
        String sourceUrl = resolvePackageUrl(jamPath, properties.getProperty("PackageURL"));
        LaunchConfig.Builder builder = LaunchConfig.builder(applicationClass)
                .title(properties.getProperty("AppName", applicationClass.getSimpleName()))
                .sourceUrl(sourceUrl)
                .scratchpadSizes(scratchpadSizes)
                .launchType((launchType == null ? LaunchConfig.LaunchTypeOption.NORMAL : launchType).launchType)
                .iAppliType(LaunchConfig.IAppliType.fromJamProperties(properties))
                .exitOnShutdown(exitOnShutdown)
                .compatibility(compatibility);
        ResolvedScratchpad scratchpad = null;
        if (scratchpadSizes.length > 0) {
            scratchpad = resolveScratchpad(jamPath);
            builder.scratchpadPackedFile(scratchpad.path());
        } else {
            if (scratchpadRoot == null) {
                builder.scratchpadRoot(null)
                        .scratchpadPackedFile(null);
            } else {
                builder.scratchpadRoot(scratchpadRoot)
                        .scratchpadPackedFile(null);
            }
        }
        Map<String, String> effectiveParameters = JamMetadataResolver.resolveEffectiveParameters(jamPath, properties);
        applyOptionalDrawArea(builder, properties.getProperty("DrawArea"), effectiveParameters.get("TargetDevice"));
        seedNamespacePlaceholders(effectiveParameters, sourceUrl, appClassName, gameJarPath);
        String appParam = properties.getProperty("AppParam");
        if (appParam != null && !appParam.isBlank() && gameJarPath != null
                && jarContainsEntry(gameJarPath, "fat.bin")) {
            appParam = appParam.replace("PD=0", "PD=1");
            appParam = removeAppParamToken(appParam, "AUTH");
        }
        if (appParam != null && !appParam.isBlank()) {
            builder.args(appParam.trim().split("\\s+"));
        }
        for (Map.Entry<String, String> entry : effectiveParameters.entrySet()) {
            builder.parameter(entry.getKey(), entry.getValue());
        }
        if (scratchpad != null && !scratchpad.found()) {
            ResolvedScratchpad missingScratchpad = scratchpad;
            OpenDoJaLog.warn(JamLauncher.class,
                    () -> "No .sp file found for " + jamPath + " next to the JAM or in ../sp; "
                            + "continuing and using " + missingScratchpad.path() + " if the game creates one at runtime");
        }
        return builder.build();
    }

    public static void main(String[] args) throws Exception {
        LaunchConfig.LaunchTypeOption launchType = LaunchConfig.LaunchTypeOption.resolveConfigured();
        List<String> effectiveArgs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (OpenDoJaCliFlags.PHONE_MODEL.equals(args[i])) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("Usage: " + usageLine());
                }
                OpenDoJaLaunchArgs.set(OpenDoJaLaunchArgs.MICROEDITION_PLATFORM_OVERRIDE, args[++i]);
                continue;
            }
            if (OpenDoJaCliFlags.LAUNCH_TYPE.equals(args[i])) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("Usage: " + usageLine());
                }
                launchType = requireLaunchType(args[++i]);
                OpenDoJaLaunchArgs.set(OpenDoJaLaunchArgs.LAUNCH_TYPE, launchType.id);
                continue;
            }
            if (OpenDoJaCliFlags.SCREEN_ROTATION.equals(args[i])) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("Usage: " + usageLine());
                }
                OpenDoJaLaunchArgs.set(OpenDoJaLaunchArgs.DISPLAY_ROTATION, requireDisplayRotation(args[++i]));
                continue;
            }
            effectiveArgs.add(args[i]);
        }
        if (effectiveArgs.size() != 1) {
            throw new IllegalArgumentException("Usage: " + usageLine());
        }
        Path jamPath = Path.of(effectiveArgs.get(0));
        LaunchCompatibility.reexecJamLauncherWithRequiredAddOpensIfNeeded(jamPath);
        LaunchCompatibility.reexecJamLauncherIfNeeded(jamPath);
        try {
            launch(jamPath, true, launchType);
        } catch (VerifyError error) {
            // A few handset-era jars contain bytecode that modern HotSpot rejects up front even
            // though the same title otherwise runs once verification is disabled. Retry once from
            // the top launch boundary so the default path stays strict for all normal titles.
            if (!LaunchCompatibility.reexecJamLauncherOnVerifyError(jamPath)) {
                throw error;
            }
            return;
        }
        DoJaRuntime runtime = DoJaRuntime.current();
        if (runtime != null) {
            runtime.awaitShutdown();
        }
        System.exit(0);
    }

    private static String usageLine() {
        return "JamLauncher [" + OpenDoJaCliFlags.PHONE_MODEL + " <model>] ["
                + OpenDoJaCliFlags.LAUNCH_TYPE + " <normal|standby>] ["
                + OpenDoJaCliFlags.SCREEN_ROTATION + " <" + OpenDoJaLaunchArgs.displayRotationChoices() + ">] <path-to-jam>";
    }

    private static LaunchConfig.LaunchTypeOption requireLaunchType(String value) {
        LaunchConfig.LaunchTypeOption launchType = LaunchConfig.LaunchTypeOption.fromId(value);
        if (launchType == null) {
            throw new IllegalArgumentException("Unknown launch type: " + value + ". Expected normal or standby.");
        }
        return launchType;
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

    private static String resolvePackageUrl(Path jamPath, String packageUrl) {
        if (packageUrl == null || packageUrl.isBlank()) {
            return jamPath.toUri().toString();
        }
        String trimmed = packageUrl.trim();
        if (trimmed.contains("://")) {
            return normalizePackageUri(URI.create(trimmed)).toString();
        }
        String relativePath = packagePathPart(trimmed);
        if (relativePath.isEmpty()) {
            return jamPath.toUri().toString();
        }
        Path base = jamPath.getParent();
        Path resolved = (base == null ? Path.of(relativePath) : base.resolve(relativePath)).normalize();
        if (isJarName(lastPathSegment(relativePath))) {
            Path absoluteResolved = resolved.toAbsolutePath().normalize();
            Path parent = absoluteResolved.getParent();
            return toDirectoryUri(parent == null ? absoluteResolved : parent);
        }
        return resolved.toUri().toString();
    }

    private static URI normalizePackageUri(URI packageUri) {
        if (!packageUri.isAbsolute()) {
            return packageUri;
        }
        String path = packageUri.getPath();
        if (path != null && path.endsWith("/")) {
            return stripQueryAndFragment(packageUri);
        }
        // DoJa titles commonly treat getSourceURL() as a base URL and append
        // relative endpoints directly. Normalize any concrete package/download
        // URL such as ".../game.jar" or ".../jar.php?uid=..." to its parent
        // directory so that concatenation continues to work.
        return stripQueryAndFragment(packageUri.resolve("."));
    }

    private static URI stripQueryAndFragment(URI uri) {
        try {
            return new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), null, null);
        } catch (java.net.URISyntaxException exception) {
            throw new IllegalArgumentException("Could not normalize package URI: " + uri, exception);
        }
    }

    private static boolean isJarPath(Path path) {
        return path != null && isJarName(path.toString());
    }

    private static boolean isJarName(String name) {
        return name != null && name.length() >= 4 && name.regionMatches(true, name.length() - 4, ".jar", 0, 4);
    }

    private static String lastPathSegment(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static String packagePathPart(String raw) {
        int cut = raw.length();
        int query = raw.indexOf('?');
        if (query >= 0) {
            cut = Math.min(cut, query);
        }
        int fragment = raw.indexOf('#');
        if (fragment >= 0) {
            cut = Math.min(cut, fragment);
        }
        return raw.substring(0, cut).trim();
    }

    private static String toDirectoryUri(Path directory) {
        String uri = directory.toUri().toString();
        return uri.endsWith("/") ? uri : uri + "/";
    }

    private static Class<?> loadApplicationClass(String className, Path gameJarPath) throws ClassNotFoundException {
        if (gameJarPath != null) {
            ClassLoader parentLoader = Thread.currentThread().getContextClassLoader();
            if (parentLoader == null) {
                parentLoader = JamLauncher.class.getClassLoader();
            }
            ClassLoader gameLoader = JamGameClassLoaderFactory.createLoader(gameJarPath, parentLoader);
            return Class.forName(className, false, gameLoader);
        }
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        if (contextLoader != null) {
            return Class.forName(className, false, contextLoader);
        }
        return Class.forName(className, false, JamLauncher.class.getClassLoader());
    }

    private static Path locateGameJarIfPresent(Path jamPath, Properties properties) {
        try {
            return JamGameJarLocator.locate(jamPath, properties);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static String stripExtension(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static void applyOptionalDrawArea(LaunchConfig.Builder builder, String rawDrawArea, String targetDevice) {
        int[] drawArea = parseDrawArea(rawDrawArea);
        if (drawArea == null) {
            drawArea = DoJaProfile.documentedDrawAreaForTargetDevice(targetDevice);
        }
        if (builder == null || drawArea == null) {
            // DrawArea is optional. When it is absent and the JAM does not expose a documented
            // TargetDevice, leave the launch on the existing 240x240 host default viewport.
            return;
        }
        builder.viewport(drawArea[0], drawArea[1]);
    }

    private static int[] parseDrawArea(String rawDrawArea) {
        if (rawDrawArea == null || rawDrawArea.isBlank()) {
            return null;
        }
        String[] parts = rawDrawArea.trim().split("[xX]");
        if (parts.length != 2) {
            return null;
        }
        try {
            return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static ResolvedScratchpad resolveScratchpad(Path jamPath) {
        String baseName = stripExtension(jamPath.getFileName().toString());
        Path sibling = jamPath.resolveSibling(baseName + ".sp").normalize();
        if (Files.exists(sibling)) {
            return new ResolvedScratchpad(sibling, true);
        }
        Path fallback = jamPath.getParent() == null ? null
                : jamPath.getParent().resolveSibling("sp").resolve(baseName + ".sp").normalize();
        if (fallback != null && Files.exists(fallback)) {
            return new ResolvedScratchpad(fallback, true);
        }
        return new ResolvedScratchpad(sibling, false);
    }

    private static int[] parseScratchpadSizes(String raw) {
        if (raw == null || raw.isBlank()) {
            return new int[0];
        }
        String[] parts = raw.split(",");
        int[] result = new int[parts.length];
        int count = 0;
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            result[count++] = Integer.parseInt(trimmed);
        }
        if (count == result.length) {
            return result;
        }
        int[] compact = new int[count];
        System.arraycopy(result, 0, compact, 0, count);
        return compact;
    }

    private static void seedGameStorage(Path gameJarPath) {
        if (gameJarPath == null || !java.nio.file.Files.isRegularFile(gameJarPath)) {
            return;
        }
        java.nio.file.Path storageRoot;
        try {
            storageRoot = opendoja.host.storage.DoJaStorageHost.deviceRoot();
            java.nio.file.Files.createDirectories(storageRoot);
        } catch (IOException ignored) {
            return;
        }
        try (java.util.jar.JarFile jarFile = new java.util.jar.JarFile(gameJarPath.toFile())) {
            java.util.Enumeration<java.util.jar.JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                java.util.jar.JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (name == null || name.isBlank() || name.endsWith(".class")
                        || name.regionMatches(true, 0, "META-INF/", 0, "META-INF/".length())) {
                    continue;
                }
                java.nio.file.Path target = storageRoot.resolve(name).normalize();
                if (!target.startsWith(storageRoot)) {
                    continue;
                }
                if (java.nio.file.Files.exists(target) && !"fat.bin".equalsIgnoreCase(target.getFileName().toString())) {
                    continue;
                }
                if ("fat.bin".equalsIgnoreCase(target.getFileName().toString())) {
                    continue;
                }
                java.nio.file.Path parent = target.getParent();
                if (parent != null) {
                    java.nio.file.Files.createDirectories(parent);
                }
                try (java.io.InputStream in = jarFile.getInputStream(entry)) {
                    java.nio.file.Files.copy(in, target);
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static void seedNamespacePlaceholders(Map<String, String> parameters, String sourceUrl,
                                                 String applicationClassName, Path gameJarPath) {
        String appParam = parameters == null ? null : parameters.get("AppParam");
        int tr = (appParam != null && appParam.matches("(?i).*\\bTR=1\\b.*")) ? 1 : 0;
        int access = tr == 1 ? 3 : 0;
        int share = tr == 1 ? 16 : 8;
        Path namespaceRoot;
        try {
            namespaceRoot = opendoja.host.storage.DoJaStorageHost.resolveNamespaceRoot(
                    parameters == null ? java.util.Map.of() : parameters,
                    sourceUrl, applicationClassName, access, share);
        } catch (RuntimeException ignored) {
            return;
        }
        try {
            java.nio.file.Files.createDirectories(namespaceRoot);
        } catch (IOException ignored) {
            return;
        }
        // Seed supporting resources from the JAR (never fat.bin).
        if (gameJarPath != null && java.nio.file.Files.isRegularFile(gameJarPath)) {
            try (java.util.jar.JarFile jarFile = new java.util.jar.JarFile(gameJarPath.toFile())) {
                java.util.Enumeration<java.util.jar.JarEntry> entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    java.util.jar.JarEntry entry = entries.nextElement();
                    if (entry.isDirectory()) {
                        continue;
                    }
                    String name = entry.getName();
                    if (name == null || name.isBlank() || name.endsWith(".class")
                            || name.regionMatches(true, 0, "META-INF/", 0, "META-INF/".length())
                            || "fat.bin".equalsIgnoreCase(name)) {
                        continue;
                    }
                    java.nio.file.Path target = namespaceRoot.resolve(name).normalize();
                    if (!target.startsWith(namespaceRoot) || java.nio.file.Files.exists(target)) {
                        continue;
                    }
                    java.nio.file.Path parent = target.getParent();
                    if (parent != null) {
                        java.nio.file.Files.createDirectories(parent);
                    }
                    try (java.io.InputStream in = jarFile.getInputStream(entry)) {
                        java.nio.file.Files.copy(in, target);
                    } catch (IOException ignored) {
                    }
                }
            } catch (IOException ignored) {
            }
        }
        // Obtain fat.bin directly from the JAR (authoritative), refreshing the namespace copy.
        byte[] data = null;
        java.nio.file.Path fatBin = namespaceRoot.resolve("fat.bin");
        if (gameJarPath != null && java.nio.file.Files.isRegularFile(gameJarPath)) {
            try (java.util.jar.JarFile jarFile = new java.util.jar.JarFile(gameJarPath.toFile())) {
                java.util.jar.JarEntry entry = (java.util.jar.JarEntry) jarFile.getEntry("fat.bin");
                if (entry != null) {
                    try (java.io.InputStream in = jarFile.getInputStream(entry)) {
                        data = in.readAllBytes();
                    }
                }
            } catch (IOException ignored) {
            }
        }
        if (data == null && java.nio.file.Files.isRegularFile(fatBin)) {
            try {
                data = java.nio.file.Files.readAllBytes(fatBin);
            } catch (IOException ignored) {
            }
        }
        opendoja.host.OpenDoJaLog.info(JamLauncher.class, "seedNamespacePlaceholders: namespace=" + namespaceRoot + " fatBinData=" + (data == null ? 0 : data.length));
        if (data != null) {
            try {
                java.nio.file.Files.write(fatBin, data, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException ignored) {
            }
        }
        if (data == null) {
            return;
        }
        int count = (data.length - 16) / 36;
        int created = 0;
        for (int i = 0; i < count; i++) {
            int rec = 16 + i * 36;
            if (rec + 36 > data.length) {
                break;
            }
            int len = 0;
            while (len < 15 && data[rec + 9 + len] != (byte) ' ' && data[rec + 9 + len] != 0) {
                len++;
            }
            if (len <= 0) {
                continue;
            }
            String name = new String(data, rec + 9, len, java.nio.charset.StandardCharsets.US_ASCII).trim();
            if (name.isEmpty()) {
                continue;
            }
            int size = ((data[rec + 32] & 0xFF) << 24)
                    | ((data[rec + 33] & 0xFF) << 16)
                    | ((data[rec + 34] & 0xFF) << 8)
                    | (data[rec + 35] & 0xFF);
            if (size <= 0) {
                continue;
            }
            java.nio.file.Path target = namespaceRoot.resolve(name).normalize();
            if (!target.startsWith(namespaceRoot)) {
                continue;
            }
            try {
                if (java.nio.file.Files.exists(target)) {
                    if (java.nio.file.Files.size(target) == size) {
                        continue;
                    }
                    java.nio.file.Files.delete(target);
                }
                java.nio.file.Path parent = target.getParent();
                if (parent != null) {
                    java.nio.file.Files.createDirectories(parent);
                }
                try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(target)) {
                    byte[] buf = new byte[8192];
                    java.util.Arrays.fill(buf, (byte) 1);
                    long remaining = size;
                    while (remaining > 0) {
                        int w = (int) Math.min(buf.length, remaining);
                        out.write(buf, 0, w);
                        remaining -= w;
                    }
                }
                created++;
            } catch (IOException exception) {
                opendoja.host.OpenDoJaLog.warn(JamLauncher.class, "failed to create placeholder " + name + " at " + target, exception);
            }
        }
        opendoja.host.OpenDoJaLog.info(JamLauncher.class, "seedNamespacePlaceholders: created " + created + " placeholders");
    }

    private static boolean jarContainsEntry(Path jarPath, String entryName) {
        if (jarPath == null || entryName == null || entryName.isBlank()) {
            return false;
        }
        try (java.util.jar.JarFile jarFile = new java.util.jar.JarFile(jarPath.toFile())) {
            return jarFile.getEntry(entryName) != null;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static String removeAppParamToken(String appParam, String tokenName) {
        if (appParam == null || appParam.isBlank() || tokenName == null || tokenName.isBlank()) {
            return appParam;
        }
        String prefix = tokenName.trim() + "=";
        StringBuilder result = new StringBuilder();
        for (String token : appParam.trim().split("\\s+")) {
            if (token.regionMatches(true, 0, prefix, 0, prefix.length())) {
                continue;
            }
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(token);
        }
        return result.toString();
    }

    private record ResolvedScratchpad(Path path, boolean found) {
    }
}
