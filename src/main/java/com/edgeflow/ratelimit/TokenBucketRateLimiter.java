package com.edgeflow.ratelimit;

import com.edgeflow.domain.ratelimit.RateLimitRule;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class TokenBucketRateLimiter implements RateLimiter {

    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquire(String key, RateLimitRule rule) {
        String bucketKey = rule.getId() + ":" + key;
        TokenBucket bucket = buckets.computeIfAbsent(bucketKey,
                k -> new TokenBucket(rule.getMaxTokens(), rule.getRefillRate(), rule.getRefillIntervalMs()));
        return bucket.tryConsume();
    }

    private static class TokenBucket {
        private final int maxTokens;
        private final int refillRate;
        private final long refillIntervalMs;

        private double tokens;
        private long lastRefillTime;

        TokenBucket(int maxTokens, int refillRate, long refillIntervalMs) {
            this.maxTokens = maxTokens;
            this.refillRate = refillRate;
            this.refillIntervalMs = refillIntervalMs;
            this.tokens = maxTokens;
            this.lastRefillTime = System.currentTimeMillis();
        }

        synchronized boolean tryConsume() {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        private void refill() {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRefillTime;
            if (elapsed <= 0) return;

            double tokensToAdd = (double) elapsed / refillIntervalMs * refillRate;
            tokens = Math.min(maxTokens, tokens + tokensToAdd);
            lastRefillTime = now;
        }
    }
}
