package com.edgeflow.featureflag;

import com.edgeflow.domain.flag.FeatureFlag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FeatureFlagEvaluatorTest {

    private FeatureFlagEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new FeatureFlagEvaluator();
    }

    @Test
    void disabledFlag_returnsFalse() {
        FeatureFlag flag = createFlag("test-flag", false, 100);
        assertFalse(evaluator.evaluate(flag, new FlagContext("user-1")));
    }

    @Test
    void enabledWith100Percent_alwaysTrue() {
        FeatureFlag flag = createFlag("test-flag", true, 100);

        for (int i = 0; i < 100; i++) {
            assertTrue(evaluator.evaluate(flag, new FlagContext("user-" + i)));
        }
    }

    @Test
    void enabledWith0Percent_alwaysFalse() {
        FeatureFlag flag = createFlag("test-flag", true, 0);

        for (int i = 0; i < 100; i++) {
            assertFalse(evaluator.evaluate(flag, new FlagContext("user-" + i)));
        }
    }

    @Test
    void deterministicForSameUser() {
        FeatureFlag flag = createFlag("test-flag", true, 50);
        FlagContext context = new FlagContext("user-42");

        boolean first = evaluator.evaluate(flag, context);
        for (int i = 0; i < 10; i++) {
            assertEquals(first, evaluator.evaluate(flag, context),
                    "Same user should always get the same result");
        }
    }

    @Test
    void percentageRollout_roughlyMatchesPercentage() {
        FeatureFlag flag = createFlag("test-flag", true, 30);

        int enabledCount = 0;
        int totalUsers = 10000;

        for (int i = 0; i < totalUsers; i++) {
            if (evaluator.evaluate(flag, new FlagContext("user-" + i))) {
                enabledCount++;
            }
        }

        double ratio = (double) enabledCount / totalUsers * 100;
        // Should be roughly 30% (within 5% tolerance for hash distribution)
        assertTrue(ratio > 25 && ratio < 35,
                "Expected ~30% but got " + ratio + "%");
    }

    @Test
    void differentFlags_differentDistribution() {
        FeatureFlag flag1 = createFlag("flag-a", true, 50);
        FeatureFlag flag2 = createFlag("flag-b", true, 50);

        // Same user should potentially get different results for different flags
        // (not guaranteed but statistically likely over many users)
        boolean anyDifference = false;
        for (int i = 0; i < 100; i++) {
            FlagContext ctx = new FlagContext("user-" + i);
            if (evaluator.evaluate(flag1, ctx) != evaluator.evaluate(flag2, ctx)) {
                anyDifference = true;
                break;
            }
        }
        assertTrue(anyDifference, "Different flags should produce different distributions");
    }

    private FeatureFlag createFlag(String key, boolean enabled, int rolloutPct) {
        FeatureFlag flag = new FeatureFlag();
        flag.setFlagKey(key);
        flag.setEnabled(enabled);
        flag.setRolloutPct(rolloutPct);
        flag.setStrategy("PERCENTAGE");
        return flag;
    }
}
