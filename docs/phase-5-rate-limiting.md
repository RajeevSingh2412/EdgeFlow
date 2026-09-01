# Phase 5: Rate Limiting — Learning Guide

## What You'll Learn

- What rate limiting is and why every API gateway needs it
- The token bucket algorithm in detail (math, refill mechanics, burst behavior)
- Other rate limiting algorithms: sliding window, fixed window, leaky bucket
- In-memory vs distributed (Redis) rate limiting
- Key resolution: identifying clients by IP, API key, user ID, X-Forwarded-For
- How `TokenBucketRateLimiter` works: bucket keys, synchronized per-bucket, refill calculation
- How `RateLimitService` ties rules to the limiter with Caffeine cache
- Global rules vs route-specific rules
- HTTP 429 Too Many Requests and the Retry-After header
- How NGINX, HAProxy, Kong, and AWS API Gateway handle rate limiting

---

## 1. Why Rate Limiting?

Without rate limiting, a single client can overwhelm your entire system:

```
No rate limiting:
  Malicious bot → 10,000 req/sec → /api/orders
  Legitimate user → 1 req/sec → /api/orders → timeout (server overloaded)

With rate limiting:
  Malicious bot → 10,000 req/sec → /api/orders
    First 100: OK
    Remaining 9,900: 429 Too Many Requests (rejected at the gateway)
  Legitimate user → 1 req/sec → /api/orders → 200 OK (server is fine)
```

Rate limiting protects against:

| Threat | Example |
|--------|---------|
| **Abuse / scraping** | Bot scraping your product catalog at 1000 req/sec |
| **DDoS** | Distributed attack flooding your API |
| **Buggy clients** | Mobile app with a retry loop sending requests in a tight loop |
| **Cost control** | Expensive upstream API that charges per-call |
| **Fair usage** | Free-tier users consuming resources meant for paying customers |

Rate limiting is the first **rejection filter** in EdgeFlow. Previous phases always forwarded requests. This phase can say "no" before any upstream is contacted.

---

## 2. The Token Bucket Algorithm

Token bucket is the most widely used rate limiting algorithm. It is simple, allows bursts, and is easy to reason about.

### How It Works

Imagine a bucket that holds tokens:

```
Bucket (capacity = 5 tokens):

  [T] [T] [T] [T] [T]      ← starts full (5 tokens)

  Request arrives → remove 1 token
  [T] [T] [T] [T] [ ]      ← 4 tokens left, request ALLOWED

  3 more requests arrive rapidly
  [T] [ ] [ ] [ ] [ ]      ← 1 token left

  Another request
  [ ] [ ] [ ] [ ] [ ]      ← 0 tokens, next request DENIED

  Time passes... tokens refill at a steady rate
  [T] [T] [ ] [ ] [ ]      ← 2 tokens added by refill
```

### The Math

Two parameters control the behavior:

- **maxTokens** (bucket capacity): The maximum burst size
- **refillRate / refillIntervalMs**: How fast tokens are added back

The refill calculation:

```
tokensToAdd = (elapsedMs / refillIntervalMs) * refillRate
newTokens   = min(maxTokens, currentTokens + tokensToAdd)
```

### Example: 100 requests/minute

```
maxTokens = 100
refillRate = 10
refillIntervalMs = 6000   (every 6 seconds, add 10 tokens)

Sustained rate: 10 tokens / 6 seconds = 100 tokens / 60 seconds = 100 req/min
Burst capacity: 100 requests instantly (bucket starts full)
Recovery: After a burst, takes 60 seconds to fully refill

Timeline:
  t=0      tokens=100   Burst: 100 requests → all allowed, tokens=0
  t=6s     tokens=10    10 more requests allowed
  t=12s    tokens=10    10 more requests allowed
  t=60s    tokens=100   Fully recovered
```

### Why Token Bucket Allows Bursts

This is a feature, not a bug. Real traffic is bursty. A user might load a dashboard that makes 20 API calls simultaneously, then nothing for a minute. Token bucket handles this gracefully:

```
Fixed window: 100 req/min
  Dashboard load (20 requests in 1s) → all allowed
  But if 90 requests came in the last second of minute 1
  and 90 in the first second of minute 2 → 180 in 2 seconds!

Token bucket: 100 tokens, refill 100/min
  Dashboard load (20 requests) → allowed (80 tokens left)
  Burst of 80 more → allowed (0 tokens left)
  Must wait for refill → smooth, predictable
```

---

## 3. Other Rate Limiting Algorithms

### Fixed Window

Divide time into fixed windows (e.g., 1-minute blocks). Count requests per window.

```
Window: 12:00:00 - 12:00:59  limit=100
  Request 1-100: allowed     count=100
  Request 101:   DENIED      count=101 > 100

Window: 12:01:00 - 12:01:59  limit=100  (counter resets)
  Request 1: allowed         count=1
```

**Problem:** Boundary spike. 100 requests at 12:00:59 + 100 at 12:01:00 = 200 requests in 2 seconds.

### Sliding Window Log

Track the timestamp of every request. Count requests in the last N seconds.

```
Request at 12:00:45 → count requests since 11:59:45
  Found 99 → ALLOWED (100th)
Request at 12:00:46 → count requests since 11:59:46
  Found 100 → DENIED
```

**Pros:** Perfectly accurate. **Cons:** Memory-heavy (stores every timestamp).

### Sliding Window Counter

Hybrid of fixed window + sliding window. Interpolates between two adjacent fixed windows.

```
Previous window (11:59): 80 requests
Current window (12:00): 20 requests so far
Time into current window: 30s (50%)

Estimated count = 80 * (1 - 0.5) + 20 = 60
```

**Pros:** Low memory, smooth. **Cons:** Approximate.

### Leaky Bucket

Requests enter a queue (bucket). They are processed at a fixed rate (leak rate). If the queue is full, new requests are rejected.

```
Queue capacity: 10
Leak rate: 5 req/sec

Burst of 10 requests → all enter queue
  Processed: 5/sec → queue drains in 2 seconds
  During that time, new requests must wait or be rejected if queue full
```

**Pros:** Smooths out bursts entirely. **Cons:** Adds latency (requests wait in queue).

### Algorithm Comparison

| Algorithm | Burst Handling | Memory | Accuracy | Our Choice? |
|-----------|---------------|--------|----------|-------------|
| **Token Bucket** | Allows controlled bursts | Low (per-key state) | Good | Yes |
| Fixed Window | Boundary spike problem | Low | Approximate | No |
| Sliding Window Log | No bursts | High | Perfect | No |
| Sliding Window Counter | No bursts | Low | Good | No |
| Leaky Bucket | Smooths all bursts | Low | Good | No |

---

## 4. Key Resolution: Identifying the Client

Rate limiting needs to answer: "WHO is making this request?" Different key types give different granularity.

### RateLimitKeyResolver

```java
public String resolve(HttpServletRequest request, String keyType) {
    return switch (keyType.toUpperCase()) {
        case "IP" -> getClientIp(request);
        case "HEADER" -> resolveFromHeader(request);   // X-User-Id
        case "API_KEY" -> resolveApiKey(request);       // X-API-Key
        default -> getClientIp(request);                // fallback
    };
}
```

### IP-Based (Most Common)

```java
private String getClientIp(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isEmpty()) {
        return forwarded.split(",")[0].trim();    // first IP = original client
    }
    return request.getRemoteAddr();              // direct connection IP
}
```

Why `X-Forwarded-For`? Behind a load balancer or CDN, `getRemoteAddr()` returns the proxy's IP, not the client's. The `X-Forwarded-For` header chain tracks the original:

```
Client (1.2.3.4) → CDN (5.6.7.8) → Gateway

Without X-Forwarded-For:
  request.getRemoteAddr() = "5.6.7.8"   ← CDN's IP (wrong!)
  All users share one bucket = useless

With X-Forwarded-For: "1.2.3.4, 5.6.7.8"
  forwarded.split(",")[0] = "1.2.3.4"   ← client's real IP (correct!)
```

### Fallback Strategy

Every key resolver falls back to IP if the expected header is missing:

```java
private String resolveApiKey(HttpServletRequest request) {
    String apiKey = request.getHeader("X-API-Key");
    if (apiKey != null && !apiKey.isEmpty()) {
        return apiKey;
    }
    return getClientIp(request);     // fallback to IP
}
```

This prevents unauthenticated requests from bypassing rate limiting entirely.

---

## 5. Code Walkthrough — TokenBucketRateLimiter

### The Bucket Key

```java
@Override
public boolean tryAcquire(String key, RateLimitRule rule) {
    String bucketKey = rule.getId() + ":" + key;
    // bucketKey = "1:192.168.1.100"   (rule ID 1, client IP)
    // bucketKey = "2:api-key-abc123"  (rule ID 2, API key)

    TokenBucket bucket = buckets.computeIfAbsent(bucketKey,
            k -> new TokenBucket(rule.getMaxTokens(), rule.getRefillRate(),
                                 rule.getRefillIntervalMs()));
    return bucket.tryConsume();
}
```

The bucket key is `ruleId:clientKey`. This means:
- Same client under different rules gets separate buckets
- Different clients under the same rule get separate buckets
- Rule "global-100rpm" for IP 1.2.3.4 = bucket `1:1.2.3.4`
- Rule "orders-10rps" for IP 1.2.3.4 = bucket `2:1.2.3.4`

### The tryConsume() Method

```java
synchronized boolean tryConsume() {
    refill();                    // 1. Add tokens based on elapsed time
    if (tokens >= 1.0) {         // 2. Check if a token is available
        tokens -= 1.0;           // 3. Take one token
        return true;             // 4. Request allowed
    }
    return false;                // 5. No tokens = request denied
}
```

`synchronized` on the bucket instance means only one thread at a time can access a single bucket. Different buckets (different clients) are not blocked. This is fine because contention on a single bucket is low (one client rarely sends truly concurrent requests).

### The refill() Method

```java
private void refill() {
    long now = System.currentTimeMillis();
    long elapsed = now - lastRefillTime;
    if (elapsed <= 0) return;    // clock hasn't moved, no refill

    double tokensToAdd = (double) elapsed / refillIntervalMs * refillRate;
    tokens = Math.min(maxTokens, tokens + tokensToAdd);
    lastRefillTime = now;
}
```

Step-by-step example:

```
Config: maxTokens=100, refillRate=10, refillIntervalMs=1000

State: tokens=0, lastRefillTime=t0

t = t0 + 500ms (half a second later):
  elapsed = 500
  tokensToAdd = 500 / 1000 * 10 = 5.0
  tokens = min(100, 0 + 5.0) = 5.0
  → 5 tokens available

t = t0 + 1500ms:
  elapsed = 1000 (since lastRefillTime was updated to t0+500)
  tokensToAdd = 1000 / 1000 * 10 = 10.0
  tokens = min(100, 5.0 + 10.0) = 15.0
```

The `Math.min(maxTokens, ...)` cap prevents tokens from accumulating beyond the bucket size. A client who was idle for an hour still only has `maxTokens` available, not an infinite burst.

### Why `double` for Tokens?

Fractional tokens enable smooth refill. If the refill rate is 10 tokens per second and a request comes after 100ms, we have 1.0 token available instead of rounding down to 0.

---

## 6. Code Walkthrough — RateLimitService

### Loading and Caching Rules

```java
private final Cache<String, List<RateLimitRule>> ruleCache;

// Caffeine cache: rules cached for 30 seconds, max 500 entries
this.ruleCache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofSeconds(30))
        .maximumSize(500)
        .build();
```

Why cache rules? The database query to find applicable rules runs on every request. With thousands of requests per second, that is thousands of queries. The Caffeine cache reduces this to one query every 30 seconds per unique cache key.

### Global vs Route-Specific Rules

```java
private List<RateLimitRule> loadRules(Long routeId) {
    // Global rules: route_id IS NULL (apply to all routes)
    List<RateLimitRule> globalRules = ruleRepository
            .findAllByRouteIdIsNullAndEnabledTrue();

    if (routeId != null) {
        // Route-specific rules: route_id = X
        List<RateLimitRule> routeRules = ruleRepository
                .findAllByRouteIdAndEnabledTrue(routeId);
        return Stream.concat(globalRules.stream(), routeRules.stream()).toList();
    }

    return globalRules;
}
```

This produces a layered rule system:

```
Global rule: "100 requests/minute per IP across all routes"
Route rule:  "10 requests/second per IP for /api/payments"

Request to /api/orders:
  Check global rule → allowed (under 100/min)
  No route-specific rule → pass

Request to /api/payments:
  Check global rule → allowed (under 100/min)
  Check payments rule → allowed? (under 10/sec)
  Both must pass for the request to proceed.
```

### The isAllowed() Method

```java
public boolean isAllowed(HttpServletRequest request, Long routeId) {
    List<RateLimitRule> rules = getApplicableRules(routeId);

    for (RateLimitRule rule : rules) {
        String key = keyResolver.resolve(request, rule.getKeyType());
        if (!rateLimiter.tryAcquire(key, rule)) {
            return false;     // ANY rule violation = denied
        }
    }

    return true;              // all rules passed
}
```

This is a short-circuit evaluation. If the first rule denies the request, we do not even check the second rule. This is efficient and correct — if any rule says "no," the answer is "no."

---

## 7. Integration Point: ProxyController

The rate limit check happens after route resolution but before proxying:

```java
@RequestMapping("/**")
public ResponseEntity<byte[]> proxy(HttpServletRequest request, ...) {
    // 1. Resolve route
    var routeOpt = routeResolver.resolve(host, path);
    if (routeOpt.isEmpty()) { return 404; }

    ResolvedRoute route = routeOpt.get();

    // 2. Rate limiting check ← NEW IN PHASE 5
    if (!rateLimitService.isAllowed(request, route.routeId())) {
        metrics.recordRequest(timer, route.pathPrefix(), method, 429);
        metrics.recordRateLimitRejection(route.pathPrefix(), "IP");
        return ResponseEntity.status(429)
                .body("{\"error\": \"Rate limit exceeded\"}".getBytes());
    }

    // 3. Forward to upstream (only reached if rate limit allows)
    ...
}
```

The order matters:

```
Request flow:
  1. Route resolution (Which backend?)
  2. Rate limiting    (Is this client allowed?) ← Phase 5
  3. Proxy            (Forward to upstream)

Why after route resolution?
  We need the routeId to load route-specific rules.
  We need the route to record metrics with the correct route tag.

Why before proxying?
  No point forwarding a request we're going to reject.
  Saves upstream resources and network bandwidth.
```

---

## 8. HTTP 429 Too Many Requests

HTTP 429 is the standard response code for rate limiting, defined in RFC 6585:

```
HTTP/1.1 429 Too Many Requests
Content-Type: application/json
Retry-After: 6

{"error": "Rate limit exceeded"}
```

### The Retry-After Header

A well-behaved API includes `Retry-After` to tell clients when to try again:

```
Retry-After: 6        ← wait 6 seconds
Retry-After: 120      ← wait 2 minutes
```

EdgeFlow does not currently set `Retry-After`. To calculate it, you would need to know when the next token refills:

```
Time until next token = refillIntervalMs / refillRate
Example: 1000ms / 10 = 100ms → Retry-After: 1 (rounded up to seconds)
```

### Common Rate Limit Response Headers

Many APIs also include informational headers:

```
X-RateLimit-Limit: 100          ← your limit
X-RateLimit-Remaining: 0        ← tokens left
X-RateLimit-Reset: 1625000060   ← Unix timestamp when bucket refills
```

These are not standardized but widely adopted (GitHub, Twitter, Stripe all use them).

---

## 9. In-Memory vs Distributed Rate Limiting

### Our Approach: In-Memory (ConcurrentHashMap)

```java
private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();
```

**Pros:** Zero latency, no external dependencies, simple.
**Cons:** Per-instance. Each gateway instance has its own counters.

```
Problem with multiple instances:
  Instance A: bucket for IP 1.2.3.4 → 50 tokens used
  Instance B: bucket for IP 1.2.3.4 → 50 tokens used
  Total: 100 requests from IP 1.2.3.4 — but each instance thinks only 50!
  Effective limit: 2x what you configured.
```

### Distributed: Redis

Production rate limiters use Redis for shared state:

```
Redis token bucket (pseudocode):
  MULTI
    GET  rate_limit:1:1.2.3.4:tokens
    GET  rate_limit:1:1.2.3.4:last_refill
    ... refill logic ...
    SET  rate_limit:1:1.2.3.4:tokens {new_count} EX 300
  EXEC
```

**Pros:** All instances share one counter. Accurate across the cluster.
**Cons:** Network round-trip to Redis on every request (1-2ms). Redis becomes a dependency.

### When Does It Matter?

| Setup | In-Memory OK? |
|-------|---------------|
| Single gateway instance | Yes, perfectly fine |
| 2-3 instances behind a load balancer | Usually fine (limit is 2-3x nominal) |
| 10+ instances | No, need distributed (Redis) |
| Strict compliance (billing) | No, need distributed |

EdgeFlow uses in-memory for simplicity. Upgrading to Redis would mean swapping `TokenBucketRateLimiter` for a `RedisTokenBucketRateLimiter` — the `RateLimiter` interface makes this a clean swap.

---

## 10. How Real Systems Handle Rate Limiting

### NGINX

```nginx
# Define a rate limit zone
limit_req_zone $binary_remote_addr zone=api:10m rate=10r/s;

server {
    location /api/ {
        limit_req zone=api burst=20 nodelay;
        # rate=10r/s: sustained rate
        # burst=20: allow 20 extra requests before rejecting
        # nodelay: don't queue burst requests, serve immediately
    }
}
```

NGINX uses a leaky bucket algorithm. The `rate` parameter controls the leak rate. The `burst` parameter sets the bucket size. `$binary_remote_addr` keys by client IP (uses the binary representation for memory efficiency).

### HAProxy

```
frontend http
    stick-table type ip size 100k expire 30s store http_req_rate(10s)
    http-request deny if { sc_http_req_rate(0) gt 100 }
    http-request track-sc0 src
```

HAProxy uses stick tables — shared memory tables that track per-IP counters. `http_req_rate(10s)` counts requests over a 10-second sliding window. Stick tables can be synced between HAProxy peers for distributed rate limiting.

### Kong

```yaml
plugins:
  - name: rate-limiting
    config:
      second: 10
      minute: 100
      policy: redis        # or "local" for in-memory
      redis_host: redis
```

Kong supports both in-memory and Redis policies. It can set limits per second, minute, hour, day, month, and year simultaneously.

### AWS API Gateway

```
Usage Plan:
  Rate: 100 requests/second (token bucket refill rate)
  Burst: 200 (token bucket capacity)
  Quota: 10,000 requests/day
```

AWS uses a token bucket algorithm — the same algorithm EdgeFlow uses. "Rate" maps to our `refillRate`, "Burst" maps to `maxTokens`.

### Comparison

| Feature | NGINX | HAProxy | Kong | AWS API GW | EdgeFlow |
|---------|-------|---------|------|------------|----------|
| Algorithm | Leaky bucket | Sliding window | Fixed window | Token bucket | Token bucket |
| Distributed | No (NGINX Plus: yes) | Stick-table sync | Redis | Managed | No (in-memory) |
| Per-route | Yes (per location) | Yes (ACL) | Yes (per service) | Yes (usage plan) | Yes (route_id) |
| Key types | IP, header, etc. | IP, header | Consumer, IP, header | API key | IP, header, API key |
| Response | 503 (default) | 429 | 429 | 429 | 429 |

---

## 11. What Changed in This Phase

### New Files

| File | Purpose |
|------|---------|
| `db/migration/V6__create_rate_limit_rules.sql` | Schema: `rate_limit_rules` table |
| `domain/ratelimit/RateLimitRule.java` | JPA entity: rule config (maxTokens, refillRate, keyType) |
| `domain/ratelimit/RateLimitRuleRepository.java` | Queries: by route, global, enabled |
| `ratelimit/RateLimiter.java` | Interface: `tryAcquire(key, rule)` |
| `ratelimit/TokenBucketRateLimiter.java` | Token bucket implementation with per-bucket synchronization |
| `ratelimit/RateLimitKeyResolver.java` | Extracts client identity from request (IP, header, API key) |
| `ratelimit/RateLimitService.java` | Orchestrates: load rules from cache, check each rule |
| `admin/RateLimitAdminController.java` | REST API: CRUD rate limit rules |
| `admin/dto/RateLimitRuleRequest.java` | Request DTO for creating/updating rules |
| `admin/dto/RateLimitRuleResponse.java` | Response DTO with `from(RateLimitRule)` factory |

### Modified Files

| File | Change |
|------|--------|
| `proxy/ProxyController.java` | Inject `RateLimitService`, check `isAllowed()` before proxying, return 429 |
| `build.gradle` | No new dependencies (Caffeine already present) |

---

## 12. Key Concepts to Remember

| Concept | EdgeFlow Example |
|---------|-----------------|
| **Token bucket** | Bucket starts full, each request takes 1 token, tokens refill over time |
| **Burst capacity** | `maxTokens` allows a burst of that many requests before throttling kicks in |
| **Refill rate** | `refillRate / refillIntervalMs` determines the sustained request rate |
| **Bucket key** | `ruleId:clientKey` — isolates buckets per rule and per client |
| **Key resolution** | Extract client identity from IP, X-Forwarded-For, X-API-Key, or X-User-Id |
| **Global vs route rules** | Global rules (route_id NULL) apply everywhere, route rules are scoped |
| **Short-circuit** | If any rule denies, the request is rejected without checking remaining rules |
| **Caffeine cache** | Rules cached 30s to avoid DB queries on every request |
| **synchronized per-bucket** | Thread safety without global locking — different clients are never blocked |
| **In-memory limitation** | Each gateway instance has independent buckets; distributed needs Redis |
| **HTTP 429** | Standard "rate limit exceeded" status code |

---

## 13. What's Next — Phase 6: Feature Flags

Phase 6 adds the ability to control feature rollout without redeploying:

```
Feature flag: "new-checkout-flow"
  Rollout: 10%
  User "alice" → hash("alice:new-checkout-flow") % 100 = 37 → 37 >= 10 → OFF
  User "bob"   → hash("bob:new-checkout-flow")   % 100 = 5  → 5  <  10 → ON

Increase rollout to 50% → more users get the new flow
Rollout to 100% → everyone gets it
Problem detected → set to 0% → instant rollback, no deployment needed
```

This is the gateway's first step toward traffic management beyond simple routing.
