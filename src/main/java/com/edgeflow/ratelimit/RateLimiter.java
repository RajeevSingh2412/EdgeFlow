package com.edgeflow.ratelimit;

import com.edgeflow.domain.ratelimit.RateLimitRule;

public interface RateLimiter {

    /**
     * Try to acquire a token for the given key under the given rule.
     *
     * @param key  the client identifier (IP, API key, etc.)
     * @param rule the rate limit rule to apply
     * @return true if the request is allowed, false if rate limited
     */
    boolean tryAcquire(String key, RateLimitRule rule);
}
