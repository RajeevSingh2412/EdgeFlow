package com.edgeflow.event;

import java.time.Instant;
import java.util.UUID;

public record ConfigEvent(
        String eventId,
        String eventType,
        Instant timestamp,
        String sourceInstanceId,
        Long entityId,
        String action,
        String description
) {
    public static ConfigEvent of(String eventType, Long entityId, String action, String description) {
        return new ConfigEvent(
                UUID.randomUUID().toString(),
                eventType,
                Instant.now(),
                System.getenv().getOrDefault("HOSTNAME", "gateway-local"),
                entityId,
                action,
                description
        );
    }

    public static ConfigEvent routeChanged(Long routeId, String action) {
        return of("ROUTE_CHANGED", routeId, action, "Route " + action.toLowerCase());
    }

    public static ConfigEvent flagChanged(Long flagId, String action) {
        return of("FLAG_CHANGED", flagId, action, "Feature flag " + action.toLowerCase());
    }

    public static ConfigEvent rateLimitChanged(Long ruleId, String action) {
        return of("RATE_LIMIT_CHANGED", ruleId, action, "Rate limit rule " + action.toLowerCase());
    }
}
