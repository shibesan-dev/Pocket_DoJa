package opendoja.host.compat;

import opendoja.host.OpenDoJaLog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Reads the compatibility profiles bundled in the app. Profiles are listed in
 * opendoja/compat/profiles.index and loaded from opendoja/compat/profiles/.
 * Nothing here is read from user storage or exposed in the UI.
 */
public final class GameProfiles {
    private static final String INDEX_RESOURCE = "opendoja/compat/profiles.index";
    private static final String PROFILE_RESOURCE_PREFIX = "opendoja/compat/profiles/";

    private static volatile List<GameProfile> profiles;

    private GameProfiles() {
    }

    public static GameCompatibility resolve(GameIdentity identity) {
        if (identity == null) {
            return GameCompatibility.NONE;
        }
        List<GameProfile> all = profiles();
        if (all.isEmpty()) {
            OpenDoJaLog.debug(GameProfiles.class,
                    () -> "No bundled compatibility profiles; game=" + identity.stableKey());
            return GameCompatibility.NONE;
        }
        Set<String> fingerprints = identity.fingerprints();
        GameProfile winner = null;
        int winningScore = -1;
        for (GameProfile profile : all) {
            int score = profile.bestMatchScore(fingerprints);
            if (score < 0) {
                continue;
            }
            if (score > winningScore) {
                winner = profile;
                winningScore = score;
            } else if (score == winningScore) {
                String currentId = winner == null ? "<none>" : winner.id();
                OpenDoJaLog.warn(GameProfiles.class,
                        "Compatibility profile ambiguity for " + identity.stableKey()
                                + ": keeping " + currentId + " over " + profile.id()
                                + " at score " + score);
            }
        }
        if (winner == null) {
            OpenDoJaLog.debug(GameProfiles.class,
                    () -> "No compatibility profile matched game=" + identity.stableKey()
                            + " fingerprints=" + fingerprints);
            return GameCompatibility.NONE;
        }
        GameProfile resolved = winner;
        OpenDoJaLog.info(GameProfiles.class,
                "compat profile=" + resolved.id() + " game=" + identity.stableKey()
                        + " overrides=" + resolved.overrides()
                        + " reason=" + (resolved.reason() == null ? "-" : resolved.reason()));
        return resolved.compatibility();
    }

    private static List<GameProfile> profiles() {
        List<GameProfile> cached = profiles;
        if (cached != null) {
            return cached;
        }
        synchronized (GameProfiles.class) {
            cached = profiles;
            if (cached == null) {
                cached = loadProfiles();
                profiles = cached;
            }
            return cached;
        }
    }

    private static List<GameProfile> loadProfiles() {
        List<String> ids = readIndex();
        if (ids.isEmpty()) {
            return List.of();
        }
        List<GameProfile> loaded = new ArrayList<>();
        for (String id : ids) {
            GameProfile profile = loadProfile(id);
            if (profile != null) {
                loaded.add(profile);
            }
        }
        OpenDoJaLog.debug(GameProfiles.class,
                () -> "Loaded " + loaded.size() + " bundled compatibility profile(s)");
        return List.copyOf(loaded);
    }

    private static List<String> readIndex() {
        try (InputStream input = openResource(INDEX_RESOURCE)) {
            if (input == null) {
                OpenDoJaLog.debug(GameProfiles.class, "No compatibility profile index present");
                return List.of();
            }
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    String id = normalizeProfileId(trimmed);
                    if (id == null) {
                        OpenDoJaLog.warn(GameProfiles.class,
                                "Ignoring invalid compatibility profile id: " + trimmed);
                        continue;
                    }
                    ids.add(id);
                }
            }
            return List.copyOf(ids);
        } catch (IOException exception) {
            OpenDoJaLog.warn(GameProfiles.class,
                    "Could not read compatibility profile index: " + exception);
            return List.of();
        }
    }

    private static GameProfile loadProfile(String id) {
        String resource = PROFILE_RESOURCE_PREFIX + id + ".properties";
        Properties properties = new Properties();
        try (InputStream input = openResource(resource)) {
            if (input == null) {
                OpenDoJaLog.warn(GameProfiles.class,
                        "Compatibility profile listed but missing: " + resource);
                return null;
            }
            try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        } catch (IOException | IllegalArgumentException exception) {
            OpenDoJaLog.warn(GameProfiles.class,
                    "Could not read compatibility profile " + resource + ": " + exception);
            return null;
        }

        String reason = trimToNull(properties.getProperty("reason"));
        List<GameProfile.MatchRule> rules = parseMatchRules(id, properties);
        if (rules.isEmpty()) {
            OpenDoJaLog.warn(GameProfiles.class,
                    "Compatibility profile " + id + " has no match fingerprints; ignoring it");
            return null;
        }
        Map<CompatFeature, Boolean> overrides = parseOverrides(id, properties);
        if (overrides.isEmpty()) {
            OpenDoJaLog.warn(GameProfiles.class,
                    "Compatibility profile " + id + " has no overrides; keeping it as documentation only");
        }
        return new GameProfile(id, reason, rules, overrides);
    }

    private static List<GameProfile.MatchRule> parseMatchRules(String id, Properties properties) {
        Map<Integer, Set<String>> blocks = new LinkedHashMap<>();
        for (String key : properties.stringPropertyNames()) {
            String name = key.trim().toLowerCase(Locale.ROOT);
            if (!name.startsWith("match.") || !name.endsWith(".fingerprints")) {
                continue;
            }
            String blockText = name.substring("match.".length(), name.length() - ".fingerprints".length());
            int block;
            if (blockText.isEmpty()) {
                block = 1;
            } else {
                try {
                    block = Integer.parseInt(blockText);
                } catch (NumberFormatException exception) {
                    OpenDoJaLog.warn(GameProfiles.class,
                            "Ignoring match block key in " + id + ": " + key);
                    continue;
                }
            }
            Set<String> fingerprints = blocks.computeIfAbsent(block, ignored -> new LinkedHashSet<>());
            for (String raw : properties.getProperty(key).split(";")) {
                String fingerprint = GameIdentity.normalizeFingerprint(raw);
                if (fingerprint == null) {
                    if (!raw.trim().isEmpty()) {
                        OpenDoJaLog.warn(GameProfiles.class,
                                "Ignoring invalid fingerprint in " + id + ": " + raw.trim());
                    }
                    continue;
                }
                fingerprints.add(fingerprint);
            }
        }
        List<Map.Entry<Integer, Set<String>>> ordered = new ArrayList<>(blocks.entrySet());
        ordered.sort(Comparator.comparingInt(Map.Entry::getKey));
        List<GameProfile.MatchRule> rules = new ArrayList<>();
        for (Map.Entry<Integer, Set<String>> entry : ordered) {
            Set<String> fingerprints = entry.getValue();
            if (fingerprints.isEmpty()) {
                continue;
            }
            int score = score(fingerprints);
            rules.add(new GameProfile.MatchRule(fingerprints, score));
        }
        return rules;
    }

    private static Map<CompatFeature, Boolean> parseOverrides(String id, Properties properties) {
        Map<CompatFeature, Boolean> overrides = new LinkedHashMap<>();
        for (String key : properties.stringPropertyNames()) {
            String name = key.trim();
            if (!name.toLowerCase(Locale.ROOT).startsWith("override.")) {
                continue;
            }
            String featureKey = name.substring("override.".length()).trim();
            CompatFeature feature = CompatFeature.fromKey(featureKey);
            if (feature == null) {
                OpenDoJaLog.warn(GameProfiles.class,
                        "Unknown compatibility feature in profile " + id + ": " + featureKey);
                continue;
            }
            String raw = trimToNull(properties.getProperty(key));
            if ("true".equalsIgnoreCase(raw)) {
                overrides.put(feature, Boolean.TRUE);
            } else if ("false".equalsIgnoreCase(raw)) {
                overrides.put(feature, Boolean.FALSE);
            } else {
                OpenDoJaLog.warn(GameProfiles.class,
                        "Invalid boolean for " + id + " override " + featureKey + ": " + raw);
            }
        }
        return overrides;
    }

    private static int score(Set<String> fingerprints) {
        int score = 0;
        for (String fingerprint : fingerprints) {
            score += switch (fingerprintType(fingerprint)) {
                case "sha256" -> 100;
                case "apid" -> 40;
                case "key" -> 30;
                case "appclass", "jar", "jarname" -> 20;
                case "cp" -> 8;
                case "name" -> 4;
                case "ver" -> 2;
                default -> 1;
            };
        }
        return score;
    }

    private static String fingerprintType(String fingerprint) {
        int separator = fingerprint.indexOf(':');
        return separator <= 0 ? "" : fingerprint.substring(0, separator);
    }

    private static InputStream openResource(String path) {
        ClassLoader loader = GameProfiles.class.getClassLoader();
        InputStream input = loader == null ? null : loader.getResourceAsStream(path);
        if (input == null) {
            input = GameProfiles.class.getResourceAsStream("/" + path);
        }
        return input;
    }

    private static String normalizeProfileId(String raw) {
        String id = raw.trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty() || !id.matches("[a-z0-9][a-z0-9._-]*")) {
            return null;
        }
        return id;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
