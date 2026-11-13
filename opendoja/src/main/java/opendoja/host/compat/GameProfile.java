package opendoja.host.compat;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A bundled compatibility profile. A profile matches when all fingerprints
 * in any one match block are present on the game identity.
 */
public final class GameProfile {
    /**
     * One AND group of fingerprints. When several profiles match, the block
     * with the highest score wins.
     */
    public record MatchRule(Set<String> fingerprints, int score) {
        public MatchRule {
            fingerprints = Collections.unmodifiableSet(Set.copyOf(fingerprints));
        }
    }

    private final String id;
    private final String reason;
    private final List<MatchRule> matchRules;
    private final Map<CompatFeature, Boolean> overrides;

    public GameProfile(String id, String reason,
                       List<MatchRule> matchRules, Map<CompatFeature, Boolean> overrides) {
        this.id = id;
        this.reason = reason;
        this.matchRules = List.copyOf(matchRules);
        this.overrides = Collections.unmodifiableMap(Map.copyOf(overrides));
    }

    public String id() {
        return id;
    }

    public String reason() {
        return reason;
    }

    public Map<CompatFeature, Boolean> overrides() {
        return overrides;
    }

    public int bestMatchScore(Set<String> identityFingerprints) {
        int best = -1;
        for (MatchRule rule : matchRules) {
            if (identityFingerprints.containsAll(rule.fingerprints()) && rule.score() > best) {
                best = rule.score();
            }
        }
        return best;
    }

    public GameCompatibility compatibility() {
        return new GameCompatibility(id, overrides);
    }
}
