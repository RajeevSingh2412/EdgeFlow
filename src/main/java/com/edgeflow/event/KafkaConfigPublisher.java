package com.edgeflow.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

@Component
public class KafkaConfigPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfigPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${edgeflow.kafka.enabled:false}")
    private boolean kafkaEnabled;

    public KafkaConfigPublisher(@Nullable KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    public void publishRouteChange(Long routeId, String action) {
        publish("edgeflow.config.routes", ConfigEvent.routeChanged(routeId, action));
    }

    public void publishFlagChange(Long flagId, String action) {
        publish("edgeflow.config.flags", ConfigEvent.flagChanged(flagId, action));
    }

    public void publishRateLimitChange(Long ruleId, String action) {
        publish("edgeflow.config.rate-limits", ConfigEvent.rateLimitChanged(ruleId, action));
    }

    private void publish(String topic, ConfigEvent event) {
        if (!kafkaEnabled || kafkaTemplate == null) {
            log.debug("Kafka disabled, skipping event: {}", event.eventType());
            return;
        }

        try {
            String json = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(topic, event.entityId().toString(), json)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to publish event to {}: {}", topic, ex.getMessage());
                        } else {
                            log.debug("Published {} to {}", event.eventType(), topic);
                        }
                    });
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize event: {}", e.getMessage());
        }
    }
}
