package com.edgeflow.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class RateLimitKeyResolver {

    /**
     * Extract the client identity key from the request based on the key type.
     */
    public String resolve(HttpServletRequest request, String keyType) {
        return switch (keyType.toUpperCase()) {
            case "IP" -> getClientIp(request);
            case "HEADER" -> resolveFromHeader(request);
            case "API_KEY" -> resolveApiKey(request);
            default -> getClientIp(request);
        };
    }

    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String resolveFromHeader(HttpServletRequest request) {
        String userId = request.getHeader("X-User-Id");
        if (userId != null && !userId.isEmpty()) {
            return userId;
        }
        return getClientIp(request);
    }

    private String resolveApiKey(HttpServletRequest request) {
        String apiKey = request.getHeader("X-API-Key");
        if (apiKey != null && !apiKey.isEmpty()) {
            return apiKey;
        }
        return getClientIp(request);
    }
}
