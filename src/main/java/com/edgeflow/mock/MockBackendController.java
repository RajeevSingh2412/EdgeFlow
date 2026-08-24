package com.edgeflow.mock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@ConditionalOnProperty(name = "edgeflow.mock.enabled", havingValue = "true", matchIfMissing = true)
public class MockBackendController {

    @GetMapping(value = "/mock/users/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getUser(@PathVariable int id) {
        return Map.of(
                "id", id,
                "name", "User " + id,
                "email", "user" + id + "@example.com"
        );
    }

    @GetMapping(value = "/mock/orders/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getOrder(@PathVariable int id) {
        return Map.of(
                "orderId", id,
                "status", "SHIPPED",
                "amount", 49.99
        );
    }

    @GetMapping(value = "/mock/payments/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getPayment(@PathVariable int id) {
        return Map.of(
                "paymentId", id,
                "status", "COMPLETED",
                "method", "CREDIT_CARD"
        );
    }
}