package com.edgeflow.admin.dto;

import com.edgeflow.domain.ratelimit.RateLimitRule;

import java.time.LocalDateTime;

public record RateLimitRuleResponse(
        Long id,
        String name,
        Long routeId,
        String keyType,
        int maxTokens,
        int refillRate,
        int refillIntervalMs,
        boolean enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static RateLimitRuleResponse from(RateLimitRule rule) {
        return new RateLimitRuleResponse(
                rule.getId(),
                rule.getName(),
                rule.getRoute() != null ? rule.getRoute().getId() : null,
                rule.getKeyType(),
                rule.getMaxTokens(),
                rule.getRefillRate(),
                rule.getRefillIntervalMs(),
                rule.isEnabled(),
                rule.getCreatedAt(),
                rule.getUpdatedAt()
        );
    }
}
