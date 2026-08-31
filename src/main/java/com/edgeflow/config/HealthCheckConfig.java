package com.edgeflow.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class HealthCheckConfig {

    @Bean("healthCheckRestClient")
    public RestClient healthCheckRestClient(
            @Value("${edgeflow.health.connect-timeout-ms:2000}") int connectTimeout,
            @Value("${edgeflow.health.read-timeout-ms:5000}") int readTimeout) {

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeout));
        factory.setReadTimeout(Duration.ofMillis(readTimeout));

        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }
}
