package com.edgeflow.featureflag;

import com.edgeflow.domain.flag.FeatureFlag;
import com.edgeflow.domain.flag.FeatureFlagRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Service
public class FeatureFlagService {

    private final FeatureFlagRepository flagRepository;
    private final FeatureFlagEvaluator evaluator;

    private final Cache<String, Optional<FeatureFlag>> flagCache;

    public FeatureFlagService(FeatureFlagRepository flagRepository,
                              FeatureFlagEvaluator evaluator) {
        this.flagRepository = flagRepository;
        this.evaluator = evaluator;
        this.flagCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(30))
                .maximumSize(500)
                .build();
    }

    /**
     * Evaluate a flag for the given context.
     *
     * @return true if the flag is enabled and the user falls within the rollout percentage
     */
    public boolean isEnabled(String flagKey, FlagContext context) {
        Optional<FeatureFlag> flag = getFlag(flagKey);
        return flag.map(f -> evaluator.evaluate(f, context)).orElse(false);
    }

    /**
     * Get all flags for a route (used for setting X-Feature-* headers on proxied requests).
     */
    public List<FeatureFlag> getFlagsForRoute(Long routeId) {
        return flagRepository.findAllByTargetRouteId(routeId);
    }

    public Optional<FeatureFlag> getFlag(String flagKey) {
        return flagCache.get(flagKey, k -> flagRepository.findByFlagKey(k));
    }

    public void invalidateCache() {
        flagCache.invalidateAll();
    }
}
