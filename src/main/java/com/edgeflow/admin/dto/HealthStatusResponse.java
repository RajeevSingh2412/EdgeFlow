package com.edgeflow.admin.dto;

import com.edgeflow.domain.health.HealthStatus;

import java.time.LocalDateTime;

public record HealthStatusResponse(
        Long id,
        Long upstreamId,
        String upstreamUrl,
        Long routeId,
        boolean healthy,
        LocalDateTime lastCheckAt,
        Integer lastStatusCode,
        Integer lastResponseMs,
        int consecutiveFails,
        int consecutiveOk
) {
    public static HealthStatusResponse from(HealthStatus hs) {
        return new HealthStatusResponse(
                hs.getId(),
                hs.getUpstream().getId(),
                hs.getUpstream().getUrl(),
                hs.getUpstream().getRoute().getId(),
                hs.isHealthy(),
                hs.getLastCheckAt(),
                hs.getLastStatusCode(),
                hs.getLastResponseMs(),
                hs.getConsecutiveFails(),
                hs.getConsecutiveOk()
        );
    }
}
