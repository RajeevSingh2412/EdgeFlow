package com.edgeflow;

import com.edgeflow.config.RouteConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(RouteConfig.class)
public class EdgeFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(EdgeFlowApplication.class, args);
    }
}