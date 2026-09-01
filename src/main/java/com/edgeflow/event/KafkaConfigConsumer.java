package com.edgeflow.event;

import com.edgeflow.featureflag.FeatureFlagService;
import com.edgeflow.ratelimit.RateLimitService;
import com.edgeflow.routing.RouteResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "edgeflow.kafka.enabled", havingValue = "true")
public class KafkaConfigConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfigConsumer.class);

    private final RouteResolver routeResolver;
    private final FeatureFlagService featureFlagService;
    private final RateLimitService rateLimitService;
    private final ObjectMapper objectMapper;

    public KafkaConfigConsumer(RouteResolver routeResolver,
                               FeatureFlagService featureFlagService,
                               RateLimitService rateLimitService) {
        this.routeResolver = routeResolver;
        this.featureFlagService = featureFlagService;
        this.rateLimitService = rateLimitService;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    @KafkaListener(topics = "edgeflow.config.routes", groupId = "${edgeflow.kafka.group-id:edgeflow-${random.uuid}}")
    public void onRouteChange(String message) {
        try {
            ConfigEvent event = objectMapper.readValue(message, ConfigEvent.class);
            log.info("Received route change event: {} ({})", event.eventType(), event.action());
            routeResolver.invalidateCache();
        } catch (Exception e) {
            log.error("Failed to process route change event: {}", e.getMessage());
        }
    }

    @KafkaListener(topics = "edgeflow.config.flags", groupId = "${edgeflow.kafka.group-id:edgeflow-${random.uuid}}")
    public void onFlagChange(String message) {
        try {
            ConfigEvent event = objectMapper.readValue(message, ConfigEvent.class);
            log.info("Received flag change event: {} ({})", event.eventType(), event.action());
            featureFlagService.invalidateCache();
        } catch (Exception e) {
            log.error("Failed to process flag change event: {}", e.getMessage());
        }
    }

    @KafkaListener(topics = "edgeflow.config.rate-limits", groupId = "${edgeflow.kafka.group-id:edgeflow-${random.uuid}}")
    public void onRateLimitChange(String message) {
        try {
            ConfigEvent event = objectMapper.readValue(message, ConfigEvent.class);
            log.info("Received rate limit change event: {} ({})", event.eventType(), event.action());
            rateLimitService.invalidateCache();
        } catch (Exception e) {
            log.error("Failed to process rate limit change event: {}", e.getMessage());
        }
    }
}
