# Phase 8: Observability — Learning Guide

## What You'll Learn

- Why observability matters: you cannot improve what you cannot measure
- The three pillars of observability: metrics, logs, traces (we built metrics)
- Prometheus: pull-based metrics collection and the `/actuator/prometheus` endpoint
- Micrometer: the vendor-neutral metrics facade (like SLF4J for metrics)
- Metric types: Counter vs Gauge vs Timer vs Histogram
- Our metrics: `edgeflow_requests_total`, `edgeflow_request_duration_seconds`, and more
- `ProxyMetrics` class walkthrough
- Spring Boot Actuator: health, info, prometheus endpoints
- Grafana dashboards and PromQL queries
- RED metrics: Rate, Error, Duration
- How real systems do observability: Datadog, New Relic, OpenTelemetry

---

## 1. Why Observability Matters

A production API gateway handles thousands of requests per second across multiple routes, upstreams, and features. Without observability, you are flying blind:

```
Without observability:
  User reports: "The API is slow"
  You: "Which endpoint? Since when? How slow? For everyone or just you?"
  User: "I don't know, it's just slow"
  You: SSH into server, tail logs, guess, repeat.

With observability:
  Dashboard shows: /api/payments p99 latency spiked from 50ms to 2s at 14:32
  Rate limit rejections increased 10x at 14:30
  Upstream order-svc-2 error rate at 45%
  → Root cause identified in minutes, not hours.
```

### What Can We Answer Now?

| Question | Metric |
|----------|--------|
| How many requests per second? | `edgeflow_requests_total` (rate) |
| What is the error rate? | `edgeflow_requests_total` filtered by status 5xx / total |
| How fast are responses? | `edgeflow_request_duration_seconds` (mean, p50, p99) |
| How many requests are rate-limited? | `edgeflow_rate_limit_rejected_total` |
| Which upstreams are failing? | `edgeflow_upstream_errors_total` |
| Are health checks finding problems? | `edgeflow_health_checks_total` |
| How often are feature flags evaluated? | `edgeflow_flag_evaluations_total` |

---

## 2. The Three Pillars of Observability

### Metrics (What We Built)

Metrics are **numerical measurements** collected over time. They answer "how much?" and "how fast?":

```
edgeflow_requests_total{route="/api/orders", method="GET", status="200"} 45231
edgeflow_request_duration_seconds_sum{route="/api/orders"} 1823.45
```

**Pros:** Low overhead, aggregatable, good for dashboards and alerting.
**Cons:** No detail about individual requests.

### Logs (Built into Spring Boot)

Logs are **textual records** of discrete events:

```
2024-01-15 14:32:05 INFO  ProxyController - Proxied GET /api/orders to http://order-svc:8081 (200, 45ms)
2024-01-15 14:32:06 ERROR ProxyController - Upstream unreachable: http://order-svc-2:8082
```

**Pros:** Rich detail, easy to search, good for debugging specific incidents.
**Cons:** High volume, expensive to store, hard to aggregate.

### Traces (Not Built Yet)

Traces follow a **single request** across multiple services:

```
Trace ID: abc123
  [Gateway]         ──── 0ms-150ms ────
    [order-svc]     ──── 5ms-100ms ────
      [payment-svc] ──── 20ms-80ms  ────
      [inventory]   ──── 25ms-90ms  ────
```

**Pros:** Shows the full journey of a request, identifies bottlenecks across services.
**Cons:** Requires instrumentation in every service, complex infrastructure.

### What We Built

EdgeFlow Phase 8 focuses on **metrics** using Micrometer + Prometheus. This gives us the foundation to build dashboards, set alerts, and understand system behavior at a glance.

---

## 3. Prometheus: Pull-Based Metrics

### How Prometheus Works

Unlike most monitoring systems that receive data (push-based), Prometheus **scrapes** endpoints (pull-based):

```
Push-based (Datadog, StatsD):
  Application ──→ push metrics ──→ Monitoring Server
  Application must know where to send data
  If monitoring is down, metrics are lost

Pull-based (Prometheus):
  Prometheus ──→ GET /actuator/prometheus ──→ Application
  Application just exposes an endpoint
  Prometheus controls scrape frequency
  If Prometheus is down, application is unaffected
```

### The Scrape Model

```
┌──────────────┐     GET /actuator/prometheus      ┌──────────────┐
│              │ ──────────── every 15s ──────────→ │  EdgeFlow    │
│  Prometheus  │ ←─────── metrics response ──────── │  :8080       │
│              │                                    │              │
│              │     GET /actuator/prometheus      ┌──────────────┐
│              │ ──────────── every 15s ──────────→ │  EdgeFlow    │
│              │ ←─────── metrics response ──────── │  :8081       │
└──────────────┘                                    └──────────────┘
```

Prometheus scrape config (prometheus.yml):

```yaml
scrape_configs:
  - job_name: 'edgeflow'
    scrape_interval: 15s
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['gateway-1:8080', 'gateway-2:8081']
```

### What the Endpoint Returns

```
GET /actuator/prometheus

# HELP edgeflow_requests_total
# TYPE edgeflow_requests_total counter
edgeflow_requests_total{method="GET",route="/api/orders",status="200"} 1523.0
edgeflow_requests_total{method="GET",route="/api/orders",status="502"} 12.0
edgeflow_requests_total{method="POST",route="/api/payments",status="201"} 342.0

# HELP edgeflow_request_duration_seconds
# TYPE edgeflow_request_duration_seconds summary
edgeflow_request_duration_seconds_count{method="GET",route="/api/orders"} 1535.0
edgeflow_request_duration_seconds_sum{method="GET",route="/api/orders"} 67.234

# HELP edgeflow_rate_limit_rejected_total
# TYPE edgeflow_rate_limit_rejected_total counter
edgeflow_rate_limit_rejected_total{key_type="IP",route="/api/orders"} 45.0
```

This is the Prometheus exposition format: plain text, one metric per line, with labels in curly braces.

---

## 4. Micrometer: The Metrics Facade

### What Is Micrometer?

Micrometer is to metrics what SLF4J is to logging:

```
Logging:
  Your code → SLF4J (facade) → Logback / Log4j2 / JUL (implementation)
  Switch logging backend without changing code.

Metrics:
  Your code → Micrometer (facade) → Prometheus / Datadog / CloudWatch (implementation)
  Switch monitoring backend without changing code.
```

Your code uses Micrometer's API (`Counter`, `Timer`, `Gauge`). The `micrometer-registry-prometheus` dependency translates these into Prometheus format automatically.

### Dependency

```groovy
implementation 'org.springframework.boot:spring-boot-starter-actuator'
implementation 'io.micrometer:micrometer-registry-prometheus'
```

`spring-boot-starter-actuator` provides the `/actuator/*` endpoints. `micrometer-registry-prometheus` makes metrics available in Prometheus format at `/actuator/prometheus`.

---

## 5. Metric Types

### Counter

A monotonically increasing value. Only goes up (or resets to zero on restart).

```java
Counter.builder("edgeflow_requests_total")
        .tag("route", route)
        .tag("method", method)
        .tag("status", String.valueOf(status))
        .register(registry)
        .increment();
```

**Use for:** Request counts, error counts, events processed.
**Do NOT use for:** Values that can decrease (active connections, queue size).

```
edgeflow_requests_total: 0 → 1 → 2 → 3 → ... → 45231
                         never decreases
```

### Gauge

A value that can go up or down. A snapshot of the current state.

```java
Gauge.builder("edgeflow_active_connections", connectionPool, Pool::activeCount)
        .register(registry);
```

**Use for:** Active connections, queue depth, cache size, thread pool usage.
**Do NOT use for:** Cumulative counts (use Counter).

```
edgeflow_active_connections: 5 → 12 → 8 → 3 → 15
                             goes up and down
```

### Timer

Measures both the count and duration of events. Combines a Counter (how many) with a distribution (how long).

```java
Timer.Sample sample = Timer.start(registry);     // start stopwatch
// ... do work ...
sample.stop(timer);                              // stop and record
```

**Use for:** Request latency, processing time, external call duration.
**Produces:**
- `_count`: number of observations (like a Counter)
- `_sum`: total duration (for calculating averages)
- `_max`: maximum observed duration

```
edgeflow_request_duration_seconds_count: 1535     (number of requests)
edgeflow_request_duration_seconds_sum: 67.234     (total seconds spent)
edgeflow_request_duration_seconds_max: 2.341      (worst case)
mean = sum / count = 67.234 / 1535 = 0.0438s = 43.8ms
```

### Histogram

Like a Timer but also records the distribution of values in configurable buckets.

```
edgeflow_request_duration_seconds_bucket{le="0.01"} 892    (892 requests under 10ms)
edgeflow_request_duration_seconds_bucket{le="0.05"} 1423   (1423 under 50ms)
edgeflow_request_duration_seconds_bucket{le="0.1"}  1510   (1510 under 100ms)
edgeflow_request_duration_seconds_bucket{le="1.0"}  1530   (1530 under 1s)
edgeflow_request_duration_seconds_bucket{le="+Inf"} 1535   (all requests)
```

**Use for:** Latency percentiles (p50, p95, p99) when using Prometheus.

### Comparison

| Type | Goes Up? | Goes Down? | EdgeFlow Use |
|------|----------|------------|--------------|
| **Counter** | Yes | No (reset only) | `edgeflow_requests_total`, `edgeflow_rate_limit_rejected_total` |
| **Gauge** | Yes | Yes | Not used yet (future: active connections) |
| **Timer** | N/A (records duration) | N/A | `edgeflow_request_duration_seconds` |
| **Histogram** | N/A (records distribution) | N/A | Not used yet (Timers produce summaries by default) |

---

## 6. Code Walkthrough — ProxyMetrics

```java
@Component
public class ProxyMetrics {

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, Timer> timers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();

    public ProxyMetrics(MeterRegistry registry) {
        this.registry = registry;
    }
```

### Why ConcurrentHashMap for Timers?

Micrometer's `Timer.builder(...).register(registry)` will return the same Timer instance if called multiple times with the same name and tags. But looking up by name+tags has overhead. The `ConcurrentHashMap` provides O(1) lookup by a simple string key:

```java
String timerKey = route + ":" + method;
Timer timer = timers.computeIfAbsent(timerKey, k ->
        Timer.builder("edgeflow_request_duration_seconds")
                .tag("route", route)
                .tag("method", method)
                .register(registry));
```

First request to `GET /api/orders`: creates and caches the Timer.
Subsequent requests: reuses the cached Timer (no builder overhead).

### startTimer() and recordRequest()

```java
public Timer.Sample startTimer() {
    return Timer.start(registry);     // captures start time
}

public void recordRequest(Timer.Sample sample, String route, String method, int status) {
    // Stop the timer and record duration
    String timerKey = route + ":" + method;
    Timer timer = timers.computeIfAbsent(timerKey, k ->
            Timer.builder("edgeflow_request_duration_seconds")
                    .tag("route", route)
                    .tag("method", method)
                    .register(registry));
    sample.stop(timer);

    // Increment request counter with status tag
    Counter.builder("edgeflow_requests_total")
            .tag("route", route)
            .tag("method", method)
            .tag("status", String.valueOf(status))
            .register(registry)
            .increment();
}
```

Usage in `ProxyController`:

```java
Timer.Sample timer = metrics.startTimer();     // at the beginning

// ... route resolution, rate limiting, proxying ...

metrics.recordRequest(timer, route.pathPrefix(), method, 200);   // at the end
```

The timer captures the full request lifecycle: route resolution + rate limit check + proxy + response.

### recordRateLimitRejection()

```java
public void recordRateLimitRejection(String route, String keyType) {
    Counter.builder("edgeflow_rate_limit_rejected_total")
            .tag("route", route)
            .tag("key_type", keyType)
            .register(registry)
            .increment();
}
```

This is separate from the request counter. A rate-limited request increments both:
- `edgeflow_requests_total{status="429"}` (it was a request)
- `edgeflow_rate_limit_rejected_total` (it was specifically rate-limited)

Having both lets you calculate the rate limit rejection percentage:

```
rejection_rate = rate_limit_rejected_total / requests_total * 100
```

### recordUpstreamError() and recordHealthCheck()

```java
public void recordUpstreamError(String route, String upstream, String errorType) {
    Counter.builder("edgeflow_upstream_errors_total")
            .tag("route", route)
            .tag("upstream", upstream)
            .tag("error_type", errorType)
            .register(registry)
            .increment();
}

public void recordHealthCheck(String upstream, boolean healthy) {
    Counter.builder("edgeflow_health_checks_total")
            .tag("upstream", upstream)
            .tag("result", healthy ? "healthy" : "unhealthy")
            .register(registry)
            .increment();
}
```

These provide visibility into specific subsystems:
- `edgeflow_upstream_errors_total` tells you which upstreams are failing
- `edgeflow_health_checks_total` shows the ratio of healthy vs unhealthy checks per upstream

---

## 7. Integration — ProxyController Metrics

Every code path in `ProxyController` records metrics:

```java
@RequestMapping("/**")
public ResponseEntity<byte[]> proxy(...) {
    Timer.Sample timer = metrics.startTimer();            // START TIMER

    // No route matched
    if (routeOpt.isEmpty()) {
        metrics.recordRequest(timer, "unknown", method, 404);  // record 404
        return 404;
    }

    // Rate limited
    if (!rateLimitService.isAllowed(request, route.routeId())) {
        metrics.recordRequest(timer, route.pathPrefix(), method, 429);
        metrics.recordRateLimitRejection(route.pathPrefix(), "IP");
        return 429;
    }

    try {
        // Successful proxy
        ResponseEntity<byte[]> response = restClient...;
        metrics.recordRequest(timer, route.pathPrefix(), method, status);
        return response;

    } catch (HttpClientErrorException | HttpServerErrorException e) {
        // Upstream returned 4xx/5xx
        metrics.recordRequest(timer, route.pathPrefix(), method, status);
        return error;

    } catch (ResourceAccessException e) {
        // Upstream unreachable
        metrics.recordRequest(timer, route.pathPrefix(), method, 502);
        metrics.recordUpstreamError(route.pathPrefix(), upstream, "unreachable");
        return 502;
    }
}
```

Every exit path records a metric. This ensures 100% of requests are counted and timed, regardless of success or failure.

---

## 8. Spring Boot Actuator

### What It Provides

Actuator exposes operational endpoints for monitoring and management:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  endpoint:
    health:
      show-details: always
  prometheus:
    metrics:
      export:
        enabled: true
```

| Endpoint | Purpose |
|----------|---------|
| `/actuator/health` | Health check status (UP/DOWN) |
| `/actuator/info` | Application info (version, git commit) |
| `/actuator/prometheus` | All metrics in Prometheus exposition format |
| `/actuator/metrics` | List of all metric names |
| `/actuator/metrics/{name}` | Detail for a specific metric |

### Health Endpoint

```
GET /actuator/health

{
  "status": "UP",
  "components": {
    "db": { "status": "UP", "details": { "database": "H2" } },
    "diskSpace": { "status": "UP" },
    "ping": { "status": "UP" }
  }
}
```

`show-details: always` exposes component-level health. In production, you might set this to `when-authorized` to require authentication.

### MetricsAdminController — Custom Summary

In addition to the raw Prometheus endpoint, EdgeFlow provides a human-readable summary:

```
GET /admin/api/v1/metrics/summary

{
  "totalRequests": 45231,
  "rateLimitRejections": 123,
  "upstreamErrors": 7,
  "latency": {
    "count": 45231,
    "total_ms": 67234.5,
    "mean_ms": 1.49,
    "max_ms": 2341.0
  }
}
```

This aggregates across all routes and methods into a single dashboard-friendly response.

---

## 9. RED Metrics

RED is a methodology for monitoring request-driven services:

```
R — Rate:     requests per second
E — Errors:   error rate (percentage of failed requests)
D — Duration: response time distribution (p50, p95, p99)
```

### How EdgeFlow Maps to RED

```
Rate:
  PromQL: rate(edgeflow_requests_total[5m])
  "We're handling 250 req/sec"

Errors:
  PromQL: rate(edgeflow_requests_total{status=~"5.."}[5m])
          / rate(edgeflow_requests_total[5m]) * 100
  "0.3% error rate"

Duration:
  PromQL: rate(edgeflow_request_duration_seconds_sum[5m])
          / rate(edgeflow_request_duration_seconds_count[5m])
  "Average latency: 43ms"
```

### Grafana Dashboard Concept

Grafana visualizes Prometheus metrics as dashboards:

```
┌────────────────────────────────────────────────────────┐
│  EdgeFlow Dashboard                                     │
├───────────────────┬────────────────────────────────────┤
│  Requests/sec     │  Error Rate                         │
│  ████████ 250/s   │  ▁▁▁▁▁▁▁█ 0.3%                    │
├───────────────────┼────────────────────────────────────┤
│  p50 Latency      │  p99 Latency                        │
│  12ms             │  ████████████████ 340ms             │
├───────────────────┼────────────────────────────────────┤
│  Rate Limit       │  Upstream Errors                    │
│  Rejections: 45   │  order-svc-2: 12 errors            │
└───────────────────┴────────────────────────────────────┘
```

Example PromQL queries for a Grafana dashboard:

| Panel | PromQL |
|-------|--------|
| Request rate | `sum(rate(edgeflow_requests_total[5m]))` |
| Error rate % | `sum(rate(edgeflow_requests_total{status=~"5.."}[5m])) / sum(rate(edgeflow_requests_total[5m])) * 100` |
| Mean latency | `rate(edgeflow_request_duration_seconds_sum[5m]) / rate(edgeflow_request_duration_seconds_count[5m])` |
| Rate limit rejections/sec | `sum(rate(edgeflow_rate_limit_rejected_total[5m]))` |
| Errors by upstream | `sum by (upstream) (rate(edgeflow_upstream_errors_total[5m]))` |

---

## 10. How Real Systems Do Observability

### Datadog

```
Agent-based: Datadog Agent runs on each host
Collects: metrics, logs, traces (APM), profiling
Integration: Spring Boot auto-instrumentation via Java agent
Pricing: Per host + per metric + per log GB
```

Datadog provides a unified platform: metrics, logs, and traces in one UI. Its Java agent can auto-instrument Spring Boot applications without code changes.

### New Relic

```
Similar to Datadog: agent-based, unified platform
Special: "Errors Inbox" for error grouping and prioritization
Integration: Java agent with auto-instrumentation
```

### OpenTelemetry (OTel)

The vendor-neutral standard for observability:

```
Your Code → OTel SDK → OTel Collector → Backend (Jaeger, Prometheus, Datadog, etc.)

Benefits:
  One instrumentation for any backend
  Like Micrometer but for traces and logs too
  CNCF project, industry standard

EdgeFlow uses Micrometer (metrics only). A future phase could add OTel for distributed tracing.
```

### Comparison

| Feature | Prometheus + Grafana | Datadog | New Relic | EdgeFlow |
|---------|---------------------|---------|-----------|----------|
| Metrics | Pull-based (Prometheus) | Push-based (Agent) | Push-based (Agent) | Pull-based (Actuator) |
| Logs | Loki (separate) | Integrated | Integrated | Spring Boot default (console) |
| Traces | Jaeger/Tempo (separate) | Integrated (APM) | Integrated | Not yet |
| Cost | Free (self-hosted) | Expensive | Expensive | Free |
| Setup | Medium (run Prometheus + Grafana) | Low (install agent) | Low (install agent) | Low (just dependencies) |
| Custom metrics | PromQL | DogStatsD | NRQL | Micrometer API |

---

## 11. What Changed in This Phase

### New Files

| File | Purpose |
|------|---------|
| `metrics/ProxyMetrics.java` | Micrometer wrappers: Timer, Counters for requests, errors, rate limits, health, flags |
| `admin/MetricsAdminController.java` | REST API: `/admin/api/v1/metrics/summary` aggregated view |

### Modified Files

| File | Change |
|------|--------|
| `proxy/ProxyController.java` | Inject `ProxyMetrics`, record timing and counts on every code path |
| `build.gradle` | Added `spring-boot-starter-actuator`, `micrometer-registry-prometheus` |
| `application.yml` | Added `management.endpoints.*` config to expose health, info, prometheus, metrics |

### New Endpoints

| Endpoint | Source |
|----------|--------|
| `/actuator/health` | Spring Boot Actuator (auto-configured) |
| `/actuator/info` | Spring Boot Actuator (auto-configured) |
| `/actuator/prometheus` | Micrometer Prometheus registry (auto-configured) |
| `/actuator/metrics` | Spring Boot Actuator (auto-configured) |
| `/admin/api/v1/metrics/summary` | `MetricsAdminController` (custom) |

---

## 12. Key Concepts to Remember

| Concept | EdgeFlow Example |
|---------|-----------------|
| **Three pillars** | Metrics (built), Logs (Spring Boot default), Traces (not yet) |
| **Prometheus pull model** | Prometheus scrapes `/actuator/prometheus` every 15 seconds |
| **Micrometer** | Vendor-neutral metrics facade, like SLF4J for metrics |
| **Counter** | Monotonically increasing: `edgeflow_requests_total`, `edgeflow_rate_limit_rejected_total` |
| **Timer** | Duration + count: `edgeflow_request_duration_seconds` |
| **Gauge** | Current value (not used yet, future: active connections) |
| **Tags / Labels** | Dimensions on metrics: `route`, `method`, `status` for slicing and filtering |
| **RED metrics** | Rate, Error, Duration — the three things to monitor for any service |
| **Actuator** | Spring Boot module exposing operational endpoints (/health, /prometheus) |
| **PromQL** | Prometheus query language for dashboards and alerts |
| **ConcurrentHashMap caching** | Avoids repeated Timer/Counter creation on hot path |
| **Every exit path** | All branches in ProxyController record metrics — no blind spots |

---

## 13. What's Next

At this point, EdgeFlow has the core features of a production API gateway:

```
Phase 1: Reverse proxy (forward requests to upstreams)
Phase 2: Dynamic routing (path-based, host-based, DB-backed)
Phase 2b: Service discovery (self-registration, heartbeat)
Phase 3: Load balancing (weighted round-robin)
Phase 4: Health checks (active probing, threshold-based)
Phase 5: Rate limiting (token bucket, per-client, per-route)
Phase 6: Feature flags (percentage rollout, deterministic hashing)
Phase 7: Kafka events (distributed cache invalidation)
Phase 8: Observability (Micrometer, Prometheus, Actuator)
```

Possible future phases:
- **Circuit breaking**: Stop sending requests to a failing upstream after N errors (Hystrix pattern)
- **Retry with backoff**: Automatically retry failed requests with exponential backoff
- **Authentication/Authorization**: JWT validation, OAuth2, API key management
- **Request/response transformation**: Header manipulation, body rewriting
- **Distributed tracing**: OpenTelemetry integration for cross-service traces
- **WebSocket support**: Proxy WebSocket connections
- **gRPC proxying**: Support gRPC in addition to HTTP
