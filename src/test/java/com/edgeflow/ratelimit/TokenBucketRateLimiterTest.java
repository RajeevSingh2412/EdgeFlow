package com.edgeflow.ratelimit;

import com.edgeflow.domain.ratelimit.RateLimitRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {

    private TokenBucketRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new TokenBucketRateLimiter();
    }

    @Test
    void allowsRequestsUpToMaxTokens() {
        RateLimitRule rule = createRule(1L, 5, 0, 1000);

        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire("client-1", rule), "Request " + (i + 1) + " should be allowed");
        }
    }

    @Test
    void rejectsAfterTokensExhausted() {
        RateLimitRule rule = createRule(1L, 3, 0, 1000);

        assertTrue(limiter.tryAcquire("client-1", rule));
        assertTrue(limiter.tryAcquire("client-1", rule));
        assertTrue(limiter.tryAcquire("client-1", rule));
        assertFalse(limiter.tryAcquire("client-1", rule));
    }

    @Test
    void differentClients_separateBuckets() {
        RateLimitRule rule = createRule(1L, 2, 0, 1000);

        assertTrue(limiter.tryAcquire("client-1", rule));
        assertTrue(limiter.tryAcquire("client-1", rule));
        assertFalse(limiter.tryAcquire("client-1", rule));

        // client-2 should have its own bucket
        assertTrue(limiter.tryAcquire("client-2", rule));
        assertTrue(limiter.tryAcquire("client-2", rule));
        assertFalse(limiter.tryAcquire("client-2", rule));
    }

    @Test
    void differentRules_separateBuckets() {
        RateLimitRule rule1 = createRule(1L, 2, 0, 1000);
        RateLimitRule rule2 = createRule(2L, 2, 0, 1000);

        assertTrue(limiter.tryAcquire("client-1", rule1));
        assertTrue(limiter.tryAcquire("client-1", rule1));
        assertFalse(limiter.tryAcquire("client-1", rule1));

        // Same client, different rule should have its own bucket
        assertTrue(limiter.tryAcquire("client-1", rule2));
    }

    @Test
    void tokensRefillOverTime() throws InterruptedException {
        // 2 tokens max, refill 10 tokens per 1000ms (= 1 token per 100ms)
        RateLimitRule rule = createRule(1L, 2, 10, 1000);

        assertTrue(limiter.tryAcquire("client-1", rule));
        assertTrue(limiter.tryAcquire("client-1", rule));
        assertFalse(limiter.tryAcquire("client-1", rule));

        // Wait for refill (200ms should add ~2 tokens)
        Thread.sleep(250);

        assertTrue(limiter.tryAcquire("client-1", rule), "Token should have refilled");
    }

    @Test
    void refillDoesNotExceedMax() throws InterruptedException {
        RateLimitRule rule = createRule(1L, 3, 100, 1000);

        // Exhaust all tokens
        limiter.tryAcquire("client-1", rule);
        limiter.tryAcquire("client-1", rule);
        limiter.tryAcquire("client-1", rule);

        // Wait long enough to refill way beyond max
        Thread.sleep(200);

        // Should only have max 3 tokens
        assertTrue(limiter.tryAcquire("client-1", rule));
        assertTrue(limiter.tryAcquire("client-1", rule));
        assertTrue(limiter.tryAcquire("client-1", rule));
        assertFalse(limiter.tryAcquire("client-1", rule));
    }

    private RateLimitRule createRule(Long id, int maxTokens, int refillRate, int refillIntervalMs) {
        RateLimitRule rule = new RateLimitRule();
        rule.setId(id);
        rule.setMaxTokens(maxTokens);
        rule.setRefillRate(refillRate);
        rule.setRefillIntervalMs(refillIntervalMs);
        return rule;
    }
}
