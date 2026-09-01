package com.edgeflow.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@ConditionalOnProperty(name = "edgeflow.kafka.enabled", havingValue = "true")
public class KafkaConfig {

    @Bean
    public NewTopic routesTopic() {
        return TopicBuilder.name("edgeflow.config.routes")
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic flagsTopic() {
        return TopicBuilder.name("edgeflow.config.flags")
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic rateLimitsTopic() {
        return TopicBuilder.name("edgeflow.config.rate-limits")
                .partitions(3)
                .replicas(1)
                .build();
    }
}
