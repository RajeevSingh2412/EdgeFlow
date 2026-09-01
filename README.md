# EdgeFlow

A lightweight API Gateway & Feature Flag platform built in Java from scratch — for learning distributed systems concepts hands-on.

EdgeFlow sits between clients and backend services, deciding where each request goes and how it should be handled. Think of it as building a small version of **NGINX/API Gateway + LaunchDarkly** yourself.

## What You Get

A self-hosted API gateway that runs with a single command:

```bash
git clone <repo>
docker compose up
```

This starts the full stack:

| Service | Port | Purpose |
|---------|------|---------|
| EdgeFlow Gateway | `:8080` | Routes traffic to your backend services |
| Admin Dashboard | `:3000` | Web UI for managing routes and feature flags |
| PostgreSQL | `:5432` | Persistent storage for routes, flags |
| Redis | `:6379` | Distributed rate limiting, caching |
| Kafka | `:9092` | Config change events across instances |
| Prometheus | `:9090` | Metrics collection |
| Grafana | `:3001` | Pre-built dashboards for latency, throughput, errors |
| Sample Services | `:8081-8083` | Demo backends to test routing |

## User Manual

### 1. Start the stack
```bash
docker compose up
```
Open the dashboard at `http://localhost:3000`.

### 2. Add a route
Point a path prefix to your backend service — via the dashboard or the API:
```bash
curl -X POST http://localhost:8080/admin/routes \
  -H "Content-Type: application/json" \
  -d '{"prefix": "/api/orders", "target": "http://order-service:8081"}'
```
Now `GET /api/orders/123` is proxied to `order-service:8081/api/orders/123`.

### 3. Create a feature flag
Roll out a feature to a percentage of users:
```bash
curl -X POST http://localhost:8080/admin/flags \
  -H "Content-Type: application/json" \
  -d '{"name": "new-checkout", "enabled": true, "rolloutPercentage": 30}'
```
30% of users now see the new checkout. Set to `0` for instant rollback.

### 4. Monitor
Open Grafana at `http://localhost:3001` to see:
- Request throughput (req/sec)
- Latency percentiles (P50, P95, P99)
- Error rates (5xx, 429s)
- Service health status

### 5. Scale
Run multiple EdgeFlow instances to verify distributed behavior:
- Rate limiting counters are shared via Redis
- Config changes propagate via Kafka
- Load balancing distributes across healthy upstreams

## What This Is Not

- **Not a SaaS** — no auth system, no multi-tenancy, no hosted offering
- **Not a library/SDK** — it's a standalone application you deploy
- **Not production-hardened** — built for learning and demonstration, not for replacing Kong or AWS API Gateway in production

## Architecture

```
                         CLIENTS
                            │
                            ▼
                ┌──────────────────────┐
                │       EdgeFlow       │
                │       :8080          │
                │                      │
                │  Reverse Proxy       │
                │  Dynamic Routing     │
                │  Load Balancing      │
                │  Rate Limiting       │
                │  Feature Flags       │
                │  Health Checking     │
                │  Metrics             │
                └──────────┬───────────┘
                           │
             ┌─────────────┼─────────────┐
             ▼             ▼             ▼
         Service A      Service B      Service C
             │
             │
        ┌────┴───────────────────────────┐
        │                                │
        ▼                                ▼
      Redis                         PostgreSQL
      Cache + Rate Limiting          Source of Truth
                                    (routes, flags)

                     Kafka
                       │
                 Configuration
                    Events
```

Clients only know `api.myapp.com`. EdgeFlow figures out everything else.

## Tech Stack

- **Java 21** + **Spring Boot 3.4**
- **Gradle** (Groovy DSL)
- **PostgreSQL** — persistent storage for routes, feature flags, rate limit rules
- **Caffeine** — in-process caching for routes, flags, rules
- **Kafka** — configuration change events across instances
- **Micrometer + Prometheus** — metrics collection and export
- **H2** — in-memory database for local development

## Build Plan (Incremental)

This project is built one layer at a time. Each phase introduces a real distributed systems concept.

### Phase 1: Reverse Proxy ✅
**Concept:** A single entry point that forwards client requests to the right backend service.

```
Client → EdgeFlow :8080 → Backend Service
```

- Catch-all HTTP handler forwards requests based on path prefix matching
- Configured via `application.yml`
- Preserves method, headers, body, query params
- Returns 404 for unmatched routes, 502 if upstream is unreachable
- Includes mock backend endpoints for testing

**What you learn:** How reverse proxies work (NGINX, API gateways), HTTP forwarding.

---

### Phase 2: Dynamic Routing ✅
**Concept:** Change where requests go without restarting the application.

```yaml
# Before
/orders → service-a:8081
# After (no restart needed)
/orders → service-b:8082
```

- Store routes in PostgreSQL instead of YAML
- Admin REST API to CRUD routes (`POST /admin/routes`, etc.)
- Routes reload from DB on change
- **Host-based routing** — support path-based, subdomain-based, and hybrid strategies:
  ```
  Path-based:    api.company.com/users/*        → user-service
  Subdomain:     users.company.com/**           → user-service
  Hybrid:        admin.company.com/users/*      → user-admin-service
  ```
- **Self-registration service discovery** — backend services register on startup, send heartbeats, auto-removed when they stop:
  ```
  Service starts  → POST /registry/register   → upstream added
  Every 10s       → POST /registry/heartbeat   → still alive
  Service crashes → no heartbeat for 30s       → upstream disabled
  ```

**What you learn:** Configuration-driven systems, runtime reconfiguration, routing strategies, service discovery, heartbeat pattern.

---

### Phase 3: Load Balancing ✅
**Concept:** Distribute requests across multiple instances of the same service.

```
              EdgeFlow
                 │
        ┌────────┼────────┐
        ▼        ▼        ▼
     Order-1  Order-2  Order-3
```

- Multiple upstream URLs per route
- Round-robin algorithm (implement yourself)
- Request #1 → instance 1, Request #2 → instance 2, etc.

**What you learn:** Load balancing algorithms, why they matter at scale.

---

### Phase 4: Health Checks ✅
**Concept:** Stop sending traffic to broken instances.

```
Order-1 ✅  ←── receives traffic
Order-2 ❌  ←── removed from pool
Order-3 ✅  ←── receives traffic
```

- Periodic `GET /health` calls to each upstream
- Mark unhealthy after N consecutive failures
- Re-add to pool when recovered
- Scheduled background task

**What you learn:** Health checking, failure detection, circuit concepts.

---

### Phase 5: Rate Limiting ✅
**Concept:** Protect backends from abuse — max N requests/minute/user.

```
user_123 → 100 requests → HTTP 429 Too Many Requests
```

- Token bucket or sliding window algorithm
- In-memory first, then Redis for distributed rate limiting
- Why Redis: multiple EdgeFlow instances need shared counters

```
          Redis
            ↑
     ┌──────┼──────┐
   Edge1  Edge2  Edge3
```

**What you learn:** Rate limiting algorithms, distributed state, Redis.

---

### Phase 6: Feature Flags ✅
**Concept:** Control feature rollout without deployments (like LaunchDarkly).

```
new-checkout = 10%    →    10% of users get Checkout V2
new-checkout = OFF    →    everyone gets Checkout V1 (instant rollback)
```

- Store flags in PostgreSQL (name, enabled, rollout_percentage)
- Deterministic routing: `hash(userId + flagName) % 100` ensures same user always gets same version
- Cache flags in Redis / in-memory (don't query DB per request)
- Admin API to create/update flags

**What you learn:** Feature flags, consistent hashing, percentage rollouts, caching strategies.

---

### Phase 7: Event-Driven Config Updates (Kafka) ✅
**Concept:** When config changes, notify all EdgeFlow instances.

```
Admin changes flag → PostgreSQL updated → Kafka event published
     → EdgeFlow-1 updates cache
     → EdgeFlow-2 updates cache
     → EdgeFlow-3 updates cache
```

- Publish `feature.flag.updated` / `route.updated` events to Kafka
- Each EdgeFlow instance consumes and updates its local cache
- Graceful fallback: if Kafka/Redis is down, use last known config

**What you learn:** Event-driven architecture, Kafka, distributed configuration, fault tolerance.

---

### Phase 8: Observability (Prometheus + Grafana) ✅
**Concept:** Measure everything — you can't improve what you can't measure.

Expose metrics:
- `requests_total`, `requests_per_second`
- `request_latency` (P50, P95, P99)
- `http_500_count`, `http_429_count`
- `active_connections`, `service_health`

Load test EdgeFlow and get real numbers:
```
Requests/sec:    8,420
P50 latency:     4ms
P95 latency:     13ms
P99 latency:     28ms
Error rate:      0.03%
```

**What you learn:** Observability, metrics collection, dashboarding, performance benchmarking.

---

## Project Structure

```
EdgeFlow/
├── build.gradle
├── docker-compose.yml
├── Dockerfile
└── src/main/
    ├── java/com/edgeflow/
    │   ├── EdgeFlowApplication.java           # Spring Boot entry point
    │   ├── config/                            # Configuration & wiring
    │   │   ├── RouteConfig.java               # YAML route binding (fallback)
    │   │   ├── AsyncConfig.java               # @EnableAsync + thread pool
    │   │   ├── HealthCheckConfig.java         # RestClient with timeouts
    │   │   └── KafkaConfig.java               # Topic creation (when enabled)
    │   ├── domain/                            # JPA entities & repositories
    │   │   ├── route/                         # Route + Upstream
    │   │   ├── registry/                      # ServiceInstance
    │   │   ├── health/                        # HealthStatus
    │   │   ├── ratelimit/                     # RateLimitRule
    │   │   └── flag/                          # FeatureFlag
    │   ├── routing/                           # Route resolution
    │   │   ├── RouteResolver.java             # Interface + ResolvedRoute record
    │   │   ├── DatabaseRouteResolver.java     # Primary: DB + Caffeine cache
    │   │   └── YamlRouteResolver.java         # Fallback: YAML config
    │   ├── loadbalancer/                      # Traffic distribution
    │   │   ├── LoadBalancer.java              # Interface
    │   │   └── RoundRobinLoadBalancer.java    # Weighted round-robin
    │   ├── healthcheck/                       # Upstream monitoring
    │   │   ├── HealthChecker.java             # @Scheduled poller
    │   │   ├── HealthStatusManager.java       # Threshold logic
    │   │   └── HealthCheckResult.java         # Result record
    │   ├── ratelimit/                         # Request throttling
    │   │   ├── RateLimiter.java               # Interface
    │   │   ├── TokenBucketRateLimiter.java    # In-memory token bucket
    │   │   ├── RateLimitService.java          # Rules + cache coordination
    │   │   └── RateLimitKeyResolver.java      # Client identity extraction
    │   ├── featureflag/                       # Feature rollout
    │   │   ├── FeatureFlagService.java        # Cache + lookup
    │   │   ├── FeatureFlagEvaluator.java      # Deterministic hashing
    │   │   └── FlagContext.java               # userId + attributes
    │   ├── event/                             # Config propagation (Kafka)
    │   │   ├── ConfigEvent.java               # Event envelope record
    │   │   ├── KafkaConfigPublisher.java      # Publish on mutations
    │   │   └── KafkaConfigConsumer.java       # Consume + invalidate caches
    │   ├── metrics/                           # Observability
    │   │   └── ProxyMetrics.java              # Micrometer counters/timers
    │   ├── registry/                          # Service discovery
    │   │   └── ServiceRegistry.java           # Register, heartbeat, cleanup
    │   ├── admin/                             # Management APIs
    │   │   ├── RouteAdminController.java
    │   │   ├── HealthAdminController.java
    │   │   ├── RateLimitAdminController.java
    │   │   ├── FeatureFlagAdminController.java
    │   │   ├── MetricsAdminController.java
    │   │   └── RegistryController.java
    │   ├── proxy/                             # Core gateway engine
    │   │   └── ProxyController.java           # Catches /** and forwards
    │   └── mock/
    │       └── MockBackendController.java     # Fake backends for testing
    └── resources/
        ├── application.yml
        └── db/migration/                      # Flyway migrations V1-V7
```

## Quick Start

```bash
# Build
./gradlew build

# Run
./gradlew bootRun

# Test the proxy
curl http://localhost:8080/api/orders/123
# → {"orderId":123,"status":"SHIPPED"}

curl http://localhost:8080/api/users/1
# → {"id":1,"name":"User 1","email":"user1@example.com"}

curl http://localhost:8080/api/payments/42
# → {"paymentId":42,"status":"COMPLETED"}

curl http://localhost:8080/unknown
# → 404 {"error": "No route matched"}
```

## Concepts You'll Learn

| Phase | Concept | Technology |
|-------|---------|------------|
| 1 | Reverse proxying, HTTP forwarding | Java, Spring Boot |
| 2 | Runtime reconfiguration | PostgreSQL, REST APIs |
| 3 | Load balancing algorithms | Round-robin, concurrency |
| 4 | Failure detection | Scheduled tasks, health endpoints |
| 5 | Rate limiting, distributed state | Redis, token bucket |
| 6 | Feature flags, consistent hashing | Hashing, caching |
| 7 | Event-driven systems | Kafka, fault tolerance |
| 8 | Observability, benchmarking | Prometheus, Grafana |