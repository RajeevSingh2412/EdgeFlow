package com.edgeflow.featureflag;

import java.util.Map;

public record FlagContext(
        String userId,
        Map<String, String> attributes
) {
    public FlagContext(String userId) {
        this(userId, Map.of());
    }
}
