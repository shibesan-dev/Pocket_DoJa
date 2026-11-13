package opendoja.host.compat;

import opendoja.host.OpenDoJaLog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * JAM metadata used to recognize a game no matter what its file is called.
 * Profiles match on these fingerprints, not on the user's filename.
 */
public record GameIdentity(
        String appName,
        String appClass,
        String appVer,
        String cpName,
        String packageUrl,
        String packageBase,
        String jarName,
        String trustedApiId,
        String fileName,
        String sha256) {

    /**
     * Builds an identity from the JAM properties and the resolved game jar.
     * Filename and SHA-256 are only for diagnostics and exact matches.
     */
    public static GameIdentity from(Path jamPath, Properties properties, Path gameJarPath) {
        String appName = property(properties, "AppName");
        String appClass = property(properties, "AppClass");
        String appVer = property(properties, "AppVer");
        String cpName = property(properties, "CPName");
        String packageUrl = property(properties, "PackageURL");
        String packageBase = packageBase(packageUrl);
        String jarName = fileBase(gameJarPath == null || gameJarPath.getFileName() == null
                ? null
                : gameJarPath.getFileName().toString());
        String trustedApiId = meaningfulTrustedApiId(property(properties, "TrustedAPID"));
        String fileName = jamPath == null || jamPath.getFileName() == null
                ? null
                : jamPath.getFileName().toString();
        String sha256 = sha256(jamPath);
        return new GameIdentity(appName, appClass, appVer, cpName, packageUrl, packageBase,
                jarName, trustedApiId, fileName, sha256);
    }

    /** Fingerprints a bundled profile can match against. */
    public Set<String> fingerprints() {
        LinkedHashSet<String> fingerprints = new LinkedHashSet<>();
        addFingerprint(fingerprints, "appclass", normalizeToken(appClass));
        addFingerprint(fingerprints, "jar", packageBase);
        addFingerprint(fingerprints, "jarname", jarName);
        addFingerprint(fingerprints, "cp", normalizeToken(cpName));
        addFingerprint(fingerprints, "name", normalizeToken(appName));
        addFingerprint(fingerprints, "ver", normalizeToken(appVer));
        addFingerprint(fingerprints, "apid", normalizeToken(trustedApiId));
        addFingerprint(fingerprints, "sha256", normalizeSha256(sha256));
        addFingerprint(fingerprints, "key", stableKey());
        return fingerprints;
    }

    /**
     * Short readable id for logs. Profiles match on {@link #fingerprints()}, not
     * on this value alone.
     */
    public String stableKey() {
        String app = firstNonBlank(normalizeToken(appClass), normalizeToken(appName));
        String archive = firstNonBlank(packageBase, jarName);
        if (app != null && archive != null) {
            return app + "/" + archive;
        }
        if (app != null) {
            return app;
        }
        if (archive != null) {
            return "jar/" + archive;
        }
        String normalizedFile = normalizeToken(fileBase(fileName));
        return normalizedFile == null ? "unknown" : "file/" + normalizedFile;
    }

    public String summary() {
        return "key=" + stableKey()
                + " appClass=" + nullToDash(appClass)
                + " package=" + nullToDash(packageUrl)
                + " packageBase=" + nullToDash(packageBase)
                + " jarName=" + nullToDash(jarName)
                + " cp=" + nullToDash(cpName)
                + " appVer=" + nullToDash(appVer)
                + " apid=" + nullToDash(trustedApiId)
                + " file=" + nullToDash(fileName)
                + " sha256=" + nullToDash(sha256);
    }

    static String normalizeFingerprint(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        int separator = trimmed.indexOf(':');
        if (separator <= 0 || separator == trimmed.length() - 1) {
            return null;
        }
        String type = trimmed.substring(0, separator).trim().toLowerCase(Locale.ROOT);
        String value = trimmed.substring(separator + 1).trim();
        String normalized;
        if ("sha256".equals(type)) {
            normalized = normalizeSha256(value);
        } else if ("key".equals(type)) {
            normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}/_-]", "");
        } else {
            normalized = normalizeToken(value);
        }
        return normalized == null ? null : type + ":" + normalized;
    }

    static String normalizeToken(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                out.append(Character.toLowerCase(ch));
            }
        }
        return out.length() == 0 ? null : out.toString();
    }

    private static void addFingerprint(Set<String> fingerprints, String type, String value) {
        if (value != null && !value.isBlank()) {
            fingerprints.add(type + ":" + value);
        }
    }

    private static String property(Properties properties, String name) {
        if (properties == null) {
            return null;
        }
        String value = properties.getProperty(name);
        if (value == null) {
            return null;
        }
        value = value.trim();
        return value.isEmpty() ? null : value;
    }

    private static String meaningfulTrustedApiId(String value) {
        if (value == null) {
            return null;
        }
        String normalized = normalizeToken(value);
        if (normalized == null) {
            return null;
        }
        // Many retail JAMs carry an all-zero placeholder instead of a real id.
        for (int i = 0; i < normalized.length(); i++) {
            if (normalized.charAt(i) != '0') {
                return value;
            }
        }
        return null;
    }

    private static String packageBase(String packageUrl) {
        if (packageUrl == null || packageUrl.isBlank()) {
            return null;
        }
        String path = packageUrl.trim();
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        int fragment = path.indexOf('#');
        if (fragment >= 0) {
            path = path.substring(0, fragment);
        }
        path = path.replace('\\', '/');
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        int slash = path.lastIndexOf('/');
        String tail = slash >= 0 ? path.substring(slash + 1) : path;
        return fileBase(tail);
    }

    private static String fileBase(CharSequence name) {
        if (name == null) {
            return null;
        }
        String value = name.toString().trim();
        if (value.isEmpty()) {
            return null;
        }
        int dot = value.lastIndexOf('.');
        if (dot > 0) {
            value = value.substring(0, dot);
        }
        return normalizeToken(value);
    }

    private static String normalizeSha256(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.matches("[0-9a-f]{64}") ? normalized : null;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String sha256(Path file) {
        if (file == null) {
            return null;
        }
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | java.security.NoSuchAlgorithmException exception) {
            OpenDoJaLog.debug(GameIdentity.class, () -> "Could not hash JAM " + file + ": " + exception);
            return null;
        }
    }
}
