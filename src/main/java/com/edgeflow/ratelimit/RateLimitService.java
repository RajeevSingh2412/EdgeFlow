package com.edgeflow.ratelimit;

import com.edgeflow.domain.ratelimit.RateLimitRule;
import com.edgeflow.domain.ratelimit.RateLimitRuleRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
public class RateLimitService {

    private final RateLimitRuleRepository ruleRepository;
    private final RateLimiter rateLimiter;
    private final RateLimitKeyResolver keyResolver;

    private final Cache<String, List<RateLimitRule>> ruleCache;

    public RateLimitService(RateLimitRuleRepository ruleRepository,
                            RateLimiter rateLimiter,
                            RateLimitKeyResolver keyResolver) {
        this.ruleRepository = ruleRepository;
        this.rateLimiter = rateLimiter;
        this.keyResolver = keyResolver;
        this.ruleCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(30))
                .maximumSize(500)
                .build();
    }

    /**
     * Check if the request is allowed under all applicable rate limit rules.
     *
     * @param request the incoming HTTP request
     * @param routeId the resolved route ID (null if no route matched yet)
     * @return true if allowed, false if rate limited
     */
    public boolean isAllowed(HttpServletRequest request, Long routeId) {
        List<RateLimitRule> rules = getApplicableRules(routeId);

        for (RateLimitRule rule : rules) {
            String key = keyResolver.resolve(request, rule.getKeyType());
            if (!rateLimiter.tryAcquire(key, rule)) {
                return false;
            }
        }

        return true;
    }

    private List<RateLimitRule> getApplicableRules(Long routeId) {
        String cacheKey = routeId != null ? "route:" + routeId : "global";
        return ruleCache.get(cacheKey, k -> loadRules(routeId));
    }

    private List<RateLimitRule> loadRules(Long routeId) {
        // Get global rules (no specific route) + route-specific rules
        List<RateLimitRule> globalRules = ruleRepository.findAllByRouteIdIsNullAndEnabledTrue();

        if (routeId != null) {
            List<RateLimitRule> routeRules = ruleRepository.findAllByRouteIdAndEnabledTrue(routeId);
            return java.util.stream.Stream.concat(globalRules.stream(), routeRules.stream()).toList();
        }

        return globalRules;
    }

    public void invalidateCache() {
        ruleCache.invalidateAll();
    }
}
