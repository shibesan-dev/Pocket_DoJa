package opendoja.host.compat;

import java.util.Collections;
import java.util.Map;

/**
 * Compatibility overrides for the current game, resolved from a bundled
 * profile at launch. Empty when no profile matched.
 */
public final class GameCompatibility {
    public static final GameCompatibility NONE = new GameCompatibility(null, Map.of());

    private static volatile GameCompatibility active = NONE;

    private final String profileId;
    private final Map<CompatFeature, Boolean> overrides;

    public GameCompatibility(String profileId, Map<CompatFeature, Boolean> overrides) {
        this.profileId = profileId;
        this.overrides = overrides == null || overrides.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(Map.copyOf(overrides));
    }

    public String profileId() {
        return profileId;
    }

    public boolean isEnabled(CompatFeature feature) {
        Boolean override = overrides.get(feature);
        return override == null ? feature.defaultValue() : override;
    }

    public static void install(GameCompatibility compatibility) {
        active = compatibility == null ? NONE : compatibility;
    }

    public static boolean isFeatureEnabled(CompatFeature feature) {
        return active.isEnabled(feature);
    }
}
