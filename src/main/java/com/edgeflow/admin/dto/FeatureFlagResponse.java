package com.edgeflow.admin.dto;

import com.edgeflow.domain.flag.FeatureFlag;

import java.time.LocalDateTime;

public record FeatureFlagResponse(
        Long id,
        String flagKey,
        String description,
        boolean enabled,
        int rolloutPct,
        Long targetRouteId,
        String strategy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static FeatureFlagResponse from(FeatureFlag flag) {
        return new FeatureFlagResponse(
                flag.getId(),
                flag.getFlagKey(),
                flag.getDescription(),
                flag.isEnabled(),
                flag.getRolloutPct(),
                flag.getTargetRoute() != null ? flag.getTargetRoute().getId() : null,
                flag.getStrategy(),
                flag.getCreatedAt(),
                flag.getUpdatedAt()
        );
    }
}
