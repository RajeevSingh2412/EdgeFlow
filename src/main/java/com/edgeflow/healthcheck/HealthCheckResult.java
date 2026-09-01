package com.edgeflow.healthcheck;

public record HealthCheckResult(
        Long upstreamId,
        boolean success,
        int statusCode,
        int responseTimeMs
) {}
