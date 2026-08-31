# Phase 4: Health Checks — Learning Guide

## What You'll Learn

- What health checking is and why load balancing alone isn't enough
- Push vs pull health detection — and why real systems use both
- Threshold-based failure detection (avoiding flapping)
- Spring's `@Async` and `@Scheduled` for parallel background tasks
- The self-injection pattern for `@Async` within the same class
- How NGINX, HAProxy, and Kubernetes handle health checking
- RestClient configuration: timeouts and connection factories

---

## 1. Why Health Checks?

Phase 3 added load balancing — requests are distributed across multiple upstreams via round-robin. But there's a critical gap: **what if one of those upstreams is dead?**

```
Without health checks:
  Request 1 → order-svc-1 ✅ (200 OK)
  Request 2 → order-svc-2 ❌ (crashed, 502 Bad Gateway)
  Request 3 → order-svc-3 ✅ (200 OK)
  Request 4 → order-svc-1 ✅ (200 OK)
  Request 5 → order-svc-2 ❌ (still crashed, another 502)
  ...

With health checks:
  Health checker detects order-svc-2 is down → disables upstream
  Request 1 → order-svc-1 ✅
  Request 2 → order-svc-3 ✅  (skipped svc-2)
  Request 3 → order-svc-1 ✅
  ...
  Health checker detects order-svc-2 recovered → re-enables upstream
  Request N → order-svc-2 ✅  (back in rotation)
```

Without health checks, users experience intermittent 502 errors. With health checks, broken instances are automatically removed and recovered instances are automatically re-added.

---

## 2. Push vs Pull — Two Approaches to Failure Detection

### Push-Based (Heartbeat) — Already Built in Phase 2B

The service tells the gateway "I'm alive":

```
Service → POST /registry/heartbeat → Gateway
  Every 10 seconds
  No heartbeat for 30s → presumed dead
```

**Pros:**
- Simple — service makes an HTTP call on a timer
- Service controls when to register/deregister
- Works through firewalls (outbound from service to gateway)

**Cons:**
- Service might be "alive" but not functioning (process running, but all requests fail)
- Network partition between service and gateway = false positive
- Requires the service to include heartbeat client code

### Pull-Based (Health Check) — What We Built in Phase 4

The gateway actively checks the service:

```
Gateway → GET /health → Service
  Every 15 seconds
  3 consecutive failures → mark unhealthy
  2 consecutive successes → mark healthy again
```

**Pros:**
- Tests actual service functionality (not just "process is running")
- Gateway controls the checking schedule
- Service doesn't need any special client code (just expose `/health`)

**Cons:**
- Gateway must know where to check (chicken-and-egg without service discovery)
- Must handle firewalls / network segmentation
- More network traffic (gateway polls every upstream)

### Why EdgeFlow Uses Both

Real systems combine push and pull:

```
Push (heartbeat):
  Service registers → auto-creates upstream → sends heartbeats
  Stops heartbeating → presumed dead → upstream disabled

Pull (health check):
  Gateway pings /health every 15s
  Service running but broken → /health returns 500 → upstream disabled
  Service recovers → /health returns 200 → upstream re-enabled
```

The two mechanisms coexist through the shared `upstream.enabled` flag:

| Scenario | Push (heartbeat) | Pull (health check) |
|----------|-------------------|---------------------|
| Service crash | Detects after 30s (no heartbeat) | Detects after ~45s (3 × 15s fails) |
| Service hanging | Doesn't detect (process still alive, heartbeat still sent) | **Detects** (/health times out) |
| Network partition | False positive (no heartbeat) | Also false positive (can't reach service) |
| Graceful shutdown | Immediate (deregister call) | Detects next cycle |

The heartbeat detects "process is dead" faster. The health check catches "process is alive but broken" — a gap the heartbeat can't fill.

---

## 3. Threshold-Based Failure Detection (Anti-Flapping)

### The Flapping Problem

A naive approach: one failed health check = mark unhealthy.

```
t=0    /health → 200   healthy
t=15   /health → timeout   UNHEALTHY!     ← momentary network hiccup
t=30   /health → 200   HEALTHY!           ← back to normal
t=45   /health → timeout   UNHEALTHY!     ← another hiccup
t=60   /health → 200   HEALTHY!

Result: upstream keeps flipping between healthy and unhealthy.
        Each flip invalidates the route cache.
        Massive overhead from a minor network issue.
```

This is called **flapping** — rapid oscillation between states.

### The Solution: Consecutive Thresholds

Only change state after N **consecutive** results:

```
Failure threshold: 3 consecutive failures to go DOWN
Success threshold: 2 consecutive successes to go UP

t=0    /health → timeout   fails=1   still HEALTHY (need 3)
t=15   /health → 200       fails=0   HEALTHY (reset by success)
t=30   /health → timeout   fails=1   still HEALTHY
t=45   /health → timeout   fails=2   still HEALTHY
t=60   /health → timeout   fails=3   → UNHEALTHY!

t=75   /health → 200       ok=1      still UNHEALTHY (need 2)
t=90   /health → 200       ok=2      → HEALTHY!
```

A single network glitch doesn't cause state changes. The upstream must fail *three times in a row* to be considered truly unhealthy.

### The health_status Record

```sql
CREATE TABLE health_status (
    upstream_id       BIGINT NOT NULL UNIQUE,
    healthy           BOOLEAN NOT NULL DEFAULT TRUE,
    consecutive_fails INT NOT NULL DEFAULT 0,
    consecutive_ok    INT NOT NULL DEFAULT 0,
    last_check_at     TIMESTAMP,
    last_status_code  INT,
    last_response_ms  INT
);
```

Key fields:
- `consecutive_fails` — resets to 0 on any success
- `consecutive_ok` — resets to 0 on any failure
- When `consecutive_fails >= threshold` and currently healthy → flip to unhealthy
- When `consecutive_ok >= threshold` and currently unhealthy → flip to healthy

### The State Machine

```
                    consecutive_ok >= 2
          ┌─────────────────────────────────┐
          │                                 │
          ▼                                 │
     ┌─────────┐    consecutive_fails >= 3    ┌──────────┐
     │ HEALTHY │ ──────────────────────────→ │UNHEALTHY │
     │         │                              │          │
     └─────────┘                              └──────────┘
          │                                 ▲
          │         success: reset fails    │
          │         failure: increment      │
          └──── success: increment ok ──────┘
                failure: reset ok
```

---

## 4. Code Walkthrough — HealthStatusManager

The core of the health check system. This class takes a `HealthCheckResult` and applies threshold logic.

### processResult() — Step by Step

```java
@Transactional
public void processResult(HealthCheckResult result) {
    // 1. Find the upstream
    Upstream upstream = upstreamRepository.findById(result.upstreamId()).orElse(null);
    if (upstream == null) return;

    // 2. Find or create the health status record
    HealthStatus status = healthStatusRepository.findByUpstreamId(result.upstreamId())
            .orElseGet(() -> createInitialStatus(upstream));

    // 3. Record the check details
    status.setLastCheckAt(LocalDateTime.now());
    status.setLastStatusCode(result.statusCode());
    status.setLastResponseMs(result.responseTimeMs());

    boolean previouslyHealthy = status.isHealthy();

    if (result.success()) {
        // 4a. Success: increment ok counter, reset fails
        status.setConsecutiveOk(status.getConsecutiveOk() + 1);
        status.setConsecutiveFails(0);

        // 4b. Recovery check: was unhealthy, now has enough consecutive successes
        if (!previouslyHealthy && status.getConsecutiveOk() >= successThreshold) {
            status.setHealthy(true);
            upstream.setEnabled(true);                    // re-add to pool
            upstreamRepository.save(upstream);
            routeResolver.invalidateCache();              // force route reload
        }
    } else {
        // 5a. Failure: increment fails counter, reset ok
        status.setConsecutiveFails(status.getConsecutiveFails() + 1);
        status.setConsecutiveOk(0);

        // 5b. Failure check: was healthy, now has enough consecutive failures
        if (previouslyHealthy && status.getConsecutiveFails() >= failureThreshold) {
            status.setHealthy(false);
            upstream.setEnabled(false);                   // remove from pool
            upstreamRepository.save(upstream);
            routeResolver.invalidateCache();              // force route reload
        }
    }

    healthStatusRepository.save(status);
}
```

### Why @Transactional?

The method modifies two entities atomically:
1. `HealthStatus` — the health tracking record
2. `Upstream` — the `enabled` flag that controls routing

If we update the upstream but fail to save the health status (or vice versa), we'd have inconsistent state. `@Transactional` ensures both writes succeed or both are rolled back.

### Why Check `previouslyHealthy`?

```java
if (previouslyHealthy && status.getConsecutiveFails() >= failureThreshold) {
```

This guard prevents redundant state changes. Without it, every check on an already-unhealthy upstream that fails would:
1. Set `upstream.setEnabled(false)` (already false)
2. Call `routeResolver.invalidateCache()` (unnecessary, wastes resources)

The guard ensures we only invalidate cache when the status actually changes.

---

## 5. Code Walkthrough — HealthChecker

The scheduled poller that orchestrates health checks across all upstreams.

### The Scheduled Check

```java
@Scheduled(fixedDelayString = "${edgeflow.health.check-interval-ms:15000}",
           initialDelayString = "${edgeflow.health.initial-delay-ms:5000}")
public void checkAll() {
    List<Upstream> allUpstreams = upstreamRepository.findAll();
    for (Upstream upstream : allUpstreams) {
        self.checkSingleAsync(upstream);       // via Spring proxy
    }
}
```

Key decisions:

**Why `findAll()` and not just enabled upstreams?**

We check ALL upstreams, including disabled ones. If we only checked enabled upstreams, a disabled upstream could never recover — it would never be checked, so it could never accumulate consecutive successes.

**Why `fixedDelay` not `fixedRate`?**

`fixedDelay = 15000` means: wait 15 seconds *after the previous execution finishes*. If the checks take 3 seconds, the actual cycle is 18 seconds. This prevents overlap — if a check cycle is slow, the next one doesn't pile on top of it.

**Why `initialDelay = 5000`?**

Give the application 5 seconds to fully start before running the first health check. This avoids checking upstreams while Spring is still wiring beans and Flyway is running migrations.

### The Self-Injection Pattern

```java
@Lazy
private final HealthChecker self;

public HealthChecker(..., @Lazy HealthChecker self) {
    this.self = self;
}
```

This solves a Spring proxy limitation. When you annotate a method with `@Async`, Spring creates a proxy that intercepts the call and submits it to the thread pool. But this only works when the method is called from *outside* the class:

```java
// BROKEN: Direct call bypasses the proxy
this.checkSingleAsync(upstream);
// The @Async annotation is IGNORED. Runs synchronously.

// WORKS: Call through the Spring proxy
self.checkSingleAsync(upstream);
// Spring intercepts the call, submits to healthCheckExecutor.
```

The `@Lazy` annotation prevents a circular dependency — without it, Spring would try to inject `HealthChecker` into itself during construction, which fails.

This is a well-known Spring pattern. The alternatives are:
- Extract the async method into a separate class (more files, more complexity)
- Use `ApplicationContext.getBean(HealthChecker.class)` (less clean)
- Don't use `@Async`, use `CompletableFuture.supplyAsync()` (less Spring-idiomatic)

### The Health Check HTTP Call

```java
private HealthCheckResult performCheck(Upstream upstream) {
    String checkPath = upstream.getHealthCheckPath() != null
            ? upstream.getHealthCheckPath() : defaultCheckPath;
    String url = upstream.getUrl() + checkPath;

    long start = System.currentTimeMillis();
    try {
        ResponseEntity<Void> response = healthRestClient.get()
                .uri(url)
                .retrieve()
                .toBodilessEntity();
        int elapsed = (int) (System.currentTimeMillis() - start);
        boolean success = response.getStatusCode().is2xxSuccessful();
        return new HealthCheckResult(upstream.getId(), success,
                response.getStatusCode().value(), elapsed);
    } catch (Exception e) {
        int elapsed = (int) (System.currentTimeMillis() - start);
        return new HealthCheckResult(upstream.getId(), false, 0, elapsed);
    }
}
```

- **URL construction:** `upstream.getUrl()` + `healthCheckPath` (e.g., `http://10.0.0.5:8081` + `/health`)
- **`toBodilessEntity()`:** We don't care about the response body, just the status code
- **Catch-all `Exception`:** Timeout, connection refused, DNS failure, anything — all treated as a failed check
- **Status code 0:** Means the request never completed (timeout, connection error)
- **Response time tracking:** Used for monitoring, visible in the admin API

---

## 6. Spring @Async — Parallel Background Tasks

### How It Works

```java
@Configuration
@EnableAsync
public class AsyncConfig {
    @Bean("healthCheckExecutor")
    public Executor healthCheckExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("health-check-");
        executor.initialize();
        return executor;
    }
}
```

`@EnableAsync` tells Spring to look for `@Async` annotations and wrap those methods with proxy logic that submits calls to a thread pool.

```java
@Async("healthCheckExecutor")
public void checkSingleAsync(Upstream upstream) {
    // Runs on a thread from the healthCheckExecutor pool
}
```

The `"healthCheckExecutor"` qualifier tells Spring which executor to use. Without it, Spring uses a default `SimpleAsyncTaskExecutor` which creates a new thread per call (bad for performance).

### Thread Pool Configuration

```java
executor.setCorePoolSize(10);    // 10 threads always alive
executor.setMaxPoolSize(10);     // never grow beyond 10
executor.setQueueCapacity(100);  // queue up to 100 tasks if all threads busy
```

If you have 30 upstreams and 10 threads:
- 10 checks start immediately (one per thread)
- 20 checks wait in the queue
- As threads finish, they pick up queued work
- All 30 checks complete in ~3 "waves"

### Why Not Virtual Threads?

Java 21 has virtual threads (`Thread.ofVirtual()`). We could use them, but:
- `ThreadPoolTaskExecutor` is the standard Spring approach
- Virtual threads are better for I/O-heavy workloads with thousands of concurrent tasks
- With ~10-100 upstreams, a fixed thread pool is simpler and sufficient
- Virtual threads with Spring's `@Async` require additional configuration

---

## 7. RestClient Configuration — Timeouts

### The Problem

The default `RestClient.create()` (used by `ProxyController`) has no explicit timeout:

```java
// ProxyController — no timeout config
this.restClient = RestClient.create();
```

For proxying, this might be acceptable — you want to wait for the upstream to respond. For health checks, you want to fail fast:

```
Health check with no timeout:
  GET http://crashed-service:8081/health → hangs for 30+ seconds
  × 30 upstreams = 15 minutes to complete one check cycle

Health check with 5s timeout:
  GET http://crashed-service:8081/health → timeout after 5s
  × 30 upstreams = ~5 seconds total (parallel)
```

### The Solution — Dedicated RestClient Bean

```java
@Bean("healthCheckRestClient")
public RestClient healthCheckRestClient() {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofMillis(2000));    // 2s to establish connection
    factory.setReadTimeout(Duration.ofMillis(5000));       // 5s to receive response

    return RestClient.builder()
            .requestFactory(factory)
            .build();
}
```

**Connect timeout (2s):** How long to wait to establish a TCP connection. If the server is down (connection refused) or unreachable (firewalled), this triggers quickly.

**Read timeout (5s):** How long to wait for the response after the connection is established. If the server accepts the connection but hangs processing the request, this triggers.

### SimpleClientHttpRequestFactory

This uses Java's built-in `HttpURLConnection`. It's the simplest option. Alternatives:

| Factory | Backend | When to Use |
|---------|---------|-------------|
| `SimpleClientHttpRequestFactory` | Java `HttpURLConnection` | Simple use cases, our choice |
| `JdkClientHttpRequestFactory` | Java 11+ `HttpClient` | HTTP/2, async support |
| `HttpComponentsClientHttpRequestFactory` | Apache HttpClient 5 | Connection pooling, advanced config |
| `ReactorNettyClientRequestFactory` | Reactor Netty | WebFlux / reactive apps |

For health checks (simple GET requests, no connection pooling needed), `SimpleClientHttpRequestFactory` is the right choice.

---

## 8. How Real Systems Do Health Checks

### NGINX

```nginx
upstream order_service {
    server 10.0.0.1:8081 max_fails=3 fail_timeout=30s;
    server 10.0.0.2:8081 max_fails=3 fail_timeout=30s;
}
```

NGINX's open-source version uses **passive health checking** — it counts failures from real proxied requests, not from dedicated health pings. After `max_fails` consecutive failures within `fail_timeout`, the upstream is removed for the duration of `fail_timeout`, then automatically retried.

NGINX Plus (commercial) adds **active health checks**:

```nginx
location /api/orders {
    proxy_pass http://order_service;
    health_check interval=5 fails=3 passes=2 uri=/health;
}
```

This is essentially what EdgeFlow does — periodic pings with threshold-based detection.

### HAProxy

```
backend order_service
    option httpchk GET /health
    server order-1 10.0.0.1:8081 check inter 5s fall 3 rise 2
    server order-2 10.0.0.2:8081 check inter 5s fall 3 rise 2
```

HAProxy's terminology:
- `check` — enable health checking
- `inter 5s` — check interval (our `check-interval-ms`)
- `fall 3` — consecutive failures to go DOWN (our `failure-threshold`)
- `rise 2` — consecutive successes to go UP (our `success-threshold`)
- `option httpchk GET /health` — the HTTP request to make

This is almost identical to EdgeFlow's model. Our naming (`failure-threshold`, `success-threshold`) mirrors HAProxy's concept directly.

### Kubernetes

```yaml
livenessProbe:
  httpGet:
    path: /health
    port: 8081
  initialDelaySeconds: 5
  periodSeconds: 15
  failureThreshold: 3
  successThreshold: 1

readinessProbe:
  httpGet:
    path: /ready
    port: 8081
  periodSeconds: 5
  failureThreshold: 1
```

Kubernetes distinguishes two types:
- **Liveness probe:** Is the container alive? Failed → **restart** the container.
- **Readiness probe:** Can the container accept traffic? Failed → **remove** from Service endpoints.

EdgeFlow's health check is most like a **readiness probe** — we remove the upstream from the routing pool but don't restart it.

### Comparison

| Feature | NGINX (Plus) | HAProxy | K8s | EdgeFlow |
|---------|-------------|---------|-----|----------|
| Check type | HTTP, TCP | HTTP, TCP, Script | HTTP, TCP, Exec, gRPC | HTTP |
| Passive checks | ✅ (OSS) | ✅ | ❌ | ❌ |
| Active checks | ✅ (Plus only) | ✅ | ✅ | ✅ |
| Failure threshold | max_fails | fall | failureThreshold | failure-threshold |
| Recovery threshold | ❌ (time-based) | rise | successThreshold | success-threshold |
| Parallel checks | ✅ | ✅ | ✅ (per-pod) | ✅ (@Async) |
| Check timeout | ✅ | ✅ | timeoutSeconds | connect/read timeout |

---

## 9. What Changed in This Phase

### New Files

| File | Purpose |
|------|---------|
| `db/migration/V5__create_health_status.sql` | Schema: `health_check_path` column + `health_status` table |
| `domain/health/HealthStatus.java` | JPA entity for health check results |
| `domain/health/HealthStatusRepository.java` | Queries: by upstream, by route, unhealthy only |
| `healthcheck/HealthCheckResult.java` | Record: `(upstreamId, success, statusCode, responseTimeMs)` |
| `healthcheck/HealthStatusManager.java` | Core threshold logic, flips `upstream.enabled` on state change |
| `healthcheck/HealthChecker.java` | `@Scheduled` poller, `@Async` parallel checks |
| `config/AsyncConfig.java` | `@EnableAsync` + thread pool for health checks |
| `config/HealthCheckConfig.java` | `RestClient` bean with 2s/5s timeouts |
| `admin/HealthAdminController.java` | REST API: view health, trigger checks |
| `admin/dto/HealthStatusResponse.java` | Response DTO for health API |

### Modified Files

| File | Change |
|------|--------|
| `domain/route/Upstream.java` | Added `healthCheckPath` field |
| `admin/dto/UpstreamRequest.java` | Added `healthCheckPath` field |
| `admin/RouteAdminController.java` | Propagate `healthCheckPath` when creating upstreams |
| `application.yml` | Added `edgeflow.health.*` config block |

### Unchanged

`DatabaseRouteResolver.java` — already filters by `Upstream::isEnabled`, no changes needed. The health checker updates the flag; the resolver just reads it. Same for `RoundRobinLoadBalancer`, `ProxyController`, `ServiceRegistry`.

---

## 10. Configuration Reference

```yaml
edgeflow:
  health:
    check-interval-ms: 15000       # How often to check (15 seconds)
    initial-delay-ms: 5000         # Wait before first check (5 seconds)
    connect-timeout-ms: 2000       # TCP connect timeout (2 seconds)
    read-timeout-ms: 5000          # HTTP read timeout (5 seconds)
    failure-threshold: 3           # Consecutive failures → mark unhealthy
    success-threshold: 2           # Consecutive successes → mark healthy
    thread-pool-size: 10           # Parallel health check threads
    check-path-default: /health    # Default health endpoint path
```

### Tuning Guidelines

| Parameter | Low Value | High Value |
|-----------|-----------|------------|
| `check-interval-ms` | Fast detection, more network traffic | Slow detection, less traffic |
| `failure-threshold` | Fast removal, risk of false positives | Tolerant of glitches, slower to detect real failures |
| `success-threshold` | Fast recovery, risk of routing to flapping service | Cautious recovery |
| `connect-timeout-ms` | Fast failure for dead hosts | Tolerant of slow connections |
| `thread-pool-size` | Less parallelism, slower cycles | More resources, faster cycles |

---

## 11. Key Concepts to Remember

| Concept | EdgeFlow Example |
|---------|-----------------|
| **Active health check** | Gateway pings `/health` on each upstream periodically |
| **Passive health check** | Counting failures from real proxy traffic (not built yet) |
| **Threshold detection** | 3 consecutive failures → unhealthy, avoids flapping |
| **Flapping** | Rapidly switching between healthy/unhealthy states |
| **@Scheduled** | `fixedDelay` runs method on interval, `initialDelay` waits before first run |
| **@Async** | Submits method to thread pool, runs in background |
| **Self-injection** | `@Lazy HealthChecker self` — call self.method() to go through Spring proxy |
| **RestClient timeouts** | `SimpleClientHttpRequestFactory` with connect/read timeout |
| **Push vs Pull** | Heartbeat (push) detects crashes, health check (pull) detects broken services |
| **Coexistence** | Both mechanisms use `upstream.enabled` — compatible, not conflicting |

---

## 12. What's Next — Phase 5: Rate Limiting

Phase 5 adds protection against abuse — limit how many requests a client can make per time window:

```
user_123 → 100 requests/minute allowed
  Request  1-100: OK
  Request 101:    429 Too Many Requests

Implementation:
  Token bucket algorithm (in-memory first, Redis later)
  Rate limit rules per route, keyed by client IP or API key
```

This is the first phase that adds a **rejection filter** — requests can be blocked before reaching any upstream.
