package com.edgeflow.featureflag;

import com.edgeflow.domain.flag.FeatureFlag;
import org.springframework.stereotype.Component;

@Component
public class FeatureFlagEvaluator {

    /**
     * Evaluate whether a flag is active for the given context.
     * Uses deterministic hashing: hash(userId + flagKey) % 100 < rolloutPct
     * This ensures the same user always gets the same result for the same flag.
     */
    public boolean evaluate(FeatureFlag flag, FlagContext context) {
        if (!flag.isEnabled()) {
            return false;
        }

        if (flag.getRolloutPct() >= 100) {
            return true;
        }

        if (flag.getRolloutPct() <= 0) {
            return false;
        }

        // Deterministic percentage rollout
        String hashInput = context.userId() + ":" + flag.getFlagKey();
        int bucket = Math.abs(hashInput.hashCode() % 100);
        return bucket < flag.getRolloutPct();
    }
}
