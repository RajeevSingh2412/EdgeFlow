# EdgeFlow — Architecture Design

This document is the "north star" for EdgeFlow. It describes the full system design across all 8 phases — where the project is heading and how all the pieces fit together.

---

## Package Structure (Final State)

```
src/main/java/com/edgeflow/
├── EdgeFlowApplication.java
│
├── config/                          # Configuration & wiring
│   ├── RouteConfig.java             # YAML route binding (Phase 1, kept as fallback)
│   ├── DatabaseConfig.java          # DataSource, Flyway
│   ├── RedisConfig.java             # RedisTemplate, Lettuce
│   ├── KafkaConfig.java             # KafkaTemplate, topic creation
│   ├── WebConfig.java               # Interceptors, CORS
│   ├── RestClientConfig.java        # Connection-pooled RestClient
│   └── AsyncConfig.java             # Thread pool for health checks
│
├── domain/                          # JPA entities & repositories
│   ├── route/
│   │   ├── Route.java
│   │   ├── Upstream.java
│   │   ├── RouteRepository.java
│   │   └── UpstreamRepository.java
│   ├── flag/
│   │   ├── FeatureFlag.java
│   │   └── FeatureFlagRepository.java
│   ├── health/
│   │   ├── HealthStatus.java
│   │   └── HealthStatusRepository.java
│   └── ratelimit/
│       ├── RateLimitRule.java
│       └── RateLimitRuleRepository.java
│
├── routing/                         # Route resolution
│   ├── RouteResolver.java           # Interface
│   ├── DatabaseRouteResolver.java   # Primary: DB + Caffeine cache
│   └── YamlRouteResolver.java       # Fallback: wraps RouteConfig
│
├── registry/                        # Service discovery
│   └── ServiceRegistry.java         # Register, heartbeat, deregister, stale cleanup
│
├── loadbalancer/                    # Traffic distribution
│   ├── LoadBalancer.java            # Interface
│   └── RoundRobinLoadBalancer.java  # AtomicInteger-based
│
├── healthcheck/                     # Upstream monitoring
│   ├── HealthChecker.java           # @Scheduled poller
│   ├── HealthStatusManager.java     # Threshold logic
│   └── HealthCheckResult.java       # Record
│
├── ratelimit/                       # Request throttling
│   ├── RateLimiter.java             # Interface
│   ├── TokenBucketRateLimiter.java  # In-memory
│   ├── RedisTokenBucketRateLimiter.java  # Distributed
│   └── RateLimitKeyResolver.java    # Extracts client identity
│
├── featureflag/                     # Feature rollout
│   ├── FeatureFlagService.java      # Cache + lookup
│   ├── FeatureFlagEvaluator.java    # Hashing, percentage rollout
│   └── FlagContext.java             # Record: userId, attributes
│
├── proxy/                           # Core gateway engine
│   ├── ProxyController.java         # HTTP entry point
│   ├── GatewayFilter.java           # Filter interface
│   ├── GatewayFilterChain.java      # Ordered execution
│   ├── RequestContext.java          # Per-request state
│   ├── RateLimitFilter.java         # Order 100
│   ├── RouteResolutionFilter.java   # Order 200
│   ├── FeatureFlagFilter.java       # Order 300
│   └── ProxyFilter.java            # Order 400 (terminal)
│
├── admin/                           # Management APIs
│   ├── RouteAdminController.java
│   ├── FeatureFlagAdminController.java
│   ├── HealthAdminController.java
│   ├── RateLimitAdminController.java
│   └── MetricsAdminController.java
│
├── event/                           # Config propagation
│   ├── ConfigEvent.java             # Sealed interface
│   ├── RouteChangedEvent.java
│   ├── FlagChangedEvent.java
│   ├── KafkaConfigPublisher.java
│   └── KafkaConfigConsumer.java
│
├── metrics/                         # Observability
│   ├── MetricsCollector.java        # Micrometer registration
│   └── ProxyMetricsFilter.java      # Order 0, wraps chain
│
└── mock/                            # Test backends
    └── MockBackendController.java
```

---

## Routing Strategies

EdgeFlow supports three routing strategies used by real API gateways. All three use the same route model — the difference is just configuration.

### Option 1: Path-Based Routing (Most Common)

Single domain, gateway routes based on path prefix.

```
https://api.company.com/users/*      --> user-service
https://api.company.com/orders/*     --> order-service
https://api.company.com/inventory/*  --> inventory-service
```

Route config: `host = null` (matches any host), `pathPrefix = "/api/users"`.

### Option 2: Subdomain-Based Routing

Each service gets its own hostname.

```
https://users.company.com/**        --> user-service
https://orders.company.com/**       --> order-service
https://inventory.company.com/**    --> inventory-service
```

Route config: `host = "users.company.com"`, `pathPrefix = "/"`.

### Option 3: Hybrid

Combine host and path matching. Common in enterprises.

```
https://api.company.com/inventory/* --> inventory-service
https://admin.company.com/users/*   --> user-admin-service
```

Route config: `host = "api.company.com"`, `pathPrefix = "/inventory"`.

### How Matching Works

Route resolution checks **both** host and path:

```java
(route.getHost() == null || route.getHost().equals(requestHost))
    && requestPath.startsWith(route.getPathPrefix())
```

- `host = null` means "match any host" (path-only routing, Option 1)
- `host = "users.company.com"` means "only match this host" (Options 2 & 3)
- Routes are sorted by specificity: host match + longest path prefix wins

---

## Database Schema (PostgreSQL)

Managed via Flyway migrations in `src/main/resources/db/migration/`.

### routes

```sql
CREATE TABLE routes (
    id              BIGSERIAL PRIMARY KEY,
    host            VARCHAR(255),
    path_prefix     VARCHAR(255) NOT NULL,
    description     VARCHAR(500),
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    strip_prefix    BOOLEAN NOT NULL DEFAULT FALSE,
    timeout_ms      INT NOT NULL DEFAULT 30000,
    retry_count     INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(host, path_prefix)
);

CREATE INDEX idx_routes_host ON routes (host);
CREATE INDEX idx_routes_path_prefix ON routes (path_prefix);
```

- `host` is nullable — `NULL` means "match any host" (path-based routing)
- The unique constraint is on `(host, path_prefix)` instead of just `path_prefix`, allowing the same path on different hosts

### upstreams

```sql
CREATE TABLE upstreams (
    id              BIGSERIAL PRIMARY KEY,
    route_id        BIGINT NOT NULL REFERENCES routes(id) ON DELETE CASCADE,
    url             VARCHAR(500) NOT NULL,
    weight          INT NOT NULL DEFAULT 1,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);
```

### feature_flags

```sql
CREATE TABLE feature_flags (
    id              BIGSERIAL PRIMARY KEY,
    flag_key        VARCHAR(255) NOT NULL UNIQUE,
    description     VARCHAR(500),
    enabled         BOOLEAN NOT NULL DEFAULT FALSE,
    rollout_pct     INT NOT NULL DEFAULT 0 CHECK (rollout_pct BETWEEN 0 AND 100),
    target_route_id BIGINT REFERENCES routes(id) ON DELETE SET NULL,
    strategy        VARCHAR(50) NOT NULL DEFAULT 'PERCENTAGE',
    strategy_config JSONB NOT NULL DEFAULT '{}',
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);
```

### health_status

```sql
CREATE TABLE health_status (
    id                BIGSERIAL PRIMARY KEY,
    upstream_id       BIGINT NOT NULL UNIQUE REFERENCES upstreams(id) ON DELETE CASCADE,
    healthy           BOOLEAN NOT NULL DEFAULT TRUE,
    last_check_at     TIMESTAMP,
    last_status_code  INT,
    last_response_ms  INT,
    consecutive_fails INT NOT NULL DEFAULT 0,
    consecutive_ok    INT NOT NULL DEFAULT 0,
    created_at        TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP NOT NULL DEFAULT NOW()
);
```

### rate_limit_rules

```sql
CREATE TABLE rate_limit_rules (
    id                  BIGSERIAL PRIMARY KEY,
    name                VARCHAR(255) NOT NULL,
    route_id            BIGINT REFERENCES routes(id) ON DELETE CASCADE,
    key_type            VARCHAR(50) NOT NULL DEFAULT 'IP',
    max_tokens          INT NOT NULL DEFAULT 100,
    refill_rate         INT NOT NULL DEFAULT 10,
    refill_interval_ms  INT NOT NULL DEFAULT 1000,
    enabled             BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP NOT NULL DEFAULT NOW()
);
```

---

## Service Discovery (Self-Registration)

Backend services register themselves with EdgeFlow on startup and send periodic heartbeats. Instances that stop heartbeating are automatically disabled.

```
Service starts  → POST /admin/api/v1/registry/register   → upstream created
Every 10s       → POST /admin/api/v1/registry/heartbeat   → timestamp updated
Service crashes → no heartbeat for 30s                    → upstream disabled
Service restarts→ POST /admin/api/v1/registry/register   → upstream re-enabled
```

### service_instances table

```sql
CREATE TABLE service_instances (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    service_name      VARCHAR(255) NOT NULL,
    instance_id       VARCHAR(255) NOT NULL UNIQUE,
    url               VARCHAR(500) NOT NULL,
    status            VARCHAR(50) NOT NULL DEFAULT 'UP',
    health_check_path VARCHAR(255) DEFAULT '/health',
    route_id          BIGINT REFERENCES routes(id),
    upstream_id       BIGINT REFERENCES upstreams(id),
    last_heartbeat_at TIMESTAMP NOT NULL,
    registered_at     TIMESTAMP NOT NULL,
    metadata          VARCHAR(2000)
);
```

### Registry API

| Method | Path | Description |
|--------|------|-------------|
| POST | `/admin/api/v1/registry/register` | Register instance, creates upstream |
| POST | `/admin/api/v1/registry/heartbeat` | Update heartbeat timestamp |
| POST | `/admin/api/v1/registry/deregister` | Graceful shutdown, disables upstream |
| GET | `/admin/api/v1/registry/services` | List all instances |
| GET | `/admin/api/v1/registry/services/{name}` | List by service name |

---

## Admin API

All endpoints under `/admin/api/v1`. Excluded from proxy routing.

### Routes

| Method | Path | Description |
|--------|------|-------------|
| GET | `/admin/api/v1/routes` | List all routes |
| GET | `/admin/api/v1/routes/{id}` | Get route with upstreams |
| POST | `/admin/api/v1/routes` | Create route + upstreams |
| PUT | `/admin/api/v1/routes/{id}` | Update route |
| DELETE | `/admin/api/v1/routes/{id}` | Delete route (cascades) |
| POST | `/admin/api/v1/routes/{id}/upstreams` | Add upstream |
| DELETE | `/admin/api/v1/routes/{id}/upstreams/{uid}` | Remove upstream |
| POST | `/admin/api/v1/routes/reload` | Force cache invalidation |

### Feature Flags

| Method | Path | Description |
|--------|------|-------------|
| GET | `/admin/api/v1/flags` | List all flags |
| POST | `/admin/api/v1/flags` | Create flag |
| PUT | `/admin/api/v1/flags/{id}` | Update flag |
| DELETE | `/admin/api/v1/flags/{id}` | Delete flag |
| POST | `/admin/api/v1/flags/{key}/evaluate` | Evaluate for user context |

### Health

| Method | Path | Description |
|--------|------|-------------|
| GET | `/admin/api/v1/health` | All upstream health |
| GET | `/admin/api/v1/health/route/{routeId}` | Health per route |
| POST | `/admin/api/v1/health/check` | Trigger immediate check |

### Rate Limits

| Method | Path | Description |
|--------|------|-------------|
| GET | `/admin/api/v1/rate-limits` | List rules |
| POST | `/admin/api/v1/rate-limits` | Create rule |
| PUT | `/admin/api/v1/rate-limits/{id}` | Update rule |
| DELETE | `/admin/api/v1/rate-limits/{id}` | Delete rule |

### Metrics

| Method | Path | Description |
|--------|------|-------------|
| GET | `/admin/api/v1/metrics/summary` | JSON metrics summary |
| GET | `/actuator/prometheus` | Prometheus scrape endpoint |

---

## Request Flow (Filter Chain)

Every request passes through an ordered chain of filters:

```
Client Request
     |
     v
[ProxyMetricsFilter]        order=0    Starts timer, increments request counter
     |
     v
[RateLimitFilter]           order=100  Checks token bucket for client key
     |                                  REJECT --> 429 Too Many Requests
     v
[RouteResolutionFilter]     order=200  Resolves host + path to Route from DB/cache
     |                                  NO MATCH --> 404 Not Found
     |                                  Picks upstream via LoadBalancer
     v
[FeatureFlagFilter]         order=300  Evaluates flags for this route
     |                                  Can override upstream (canary routing)
     |                                  Sets X-Feature-* headers
     v
[ProxyFilter]               order=400  Terminal: forwards to chosen upstream
     |                                  Returns upstream response or 502
     v
Response flows back through the chain to the client
```

### Key Interfaces

```java
public interface GatewayFilter extends Comparable<GatewayFilter> {
    int getOrder();
    ResponseEntity<byte[]> filter(HttpServletRequest request, byte[] body,
                                   RequestContext context, GatewayFilterChain chain);
}
```

```java
public interface RouteResolver {
    Optional<Route> resolve(String host, String path);
    void invalidateCache();
}
```

```java
public interface LoadBalancer {
    Optional<Upstream> choose(List<Upstream> healthyUpstreams);
}
```

```java
public interface RateLimiter {
    boolean tryAcquire(String key, RateLimitRule rule);
}
```

---

## Cache Architecture

```
Layer 1: Caffeine (in-process)
  Routes:      60s TTL, max 1000 entries
  Flags:       30s TTL, max 500 entries
  Rate rules:  30s TTL

Layer 2: Redis (shared across instances)
  Rate limit token buckets (per-key, Lua script for atomicity)

Layer 3: PostgreSQL (source of truth)
  All configuration data

Invalidation: Kafka events trigger Layer 1 cache invalidation across all instances.
```

---

## Kafka Topics & Events

| Topic | Purpose |
|-------|---------|
| `edgeflow.config.routes` | Route CRUD events |
| `edgeflow.config.flags` | Flag change events |
| `edgeflow.config.rate-limits` | Rate limit rule changes |

### Event Envelope

```json
{
  "eventId": "uuid-v4",
  "eventType": "ROUTE_UPDATED",
  "timestamp": "2026-08-27T10:30:00Z",
  "sourceInstanceId": "gateway-1",
  "payload": {
    "routeId": 5,
    "action": "UPDATED",
    "pathPrefix": "/api/products"
  }
}
```

Each gateway instance uses its own Kafka `groupId` (= its instance ID). This means every instance receives every event — broadcast semantics, not competing consumers.

On event receipt: invalidate the relevant Caffeine cache so the next request fetches fresh data from PostgreSQL.

---

## Prometheus Metrics

| Metric | Type | Labels |
|--------|------|--------|
| `edgeflow_requests_total` | Counter | route, method, status |
| `edgeflow_request_duration_seconds` | Histogram | route, method |
| `edgeflow_rate_limit_rejected_total` | Counter | route, key_type |
| `edgeflow_upstream_errors_total` | Counter | route, upstream, error_type |
| `edgeflow_healthy_upstreams` | Gauge | route |
| `edgeflow_flag_evaluations_total` | Counter | flag_key, result |
| `edgeflow_health_checks_total` | Counter | upstream, result |

### Grafana Dashboard Panels

- **Row 1 — Traffic:** Requests/sec by route, Error rate %, Active routes count
- **Row 2 — Latency:** P50/P95/P99 request latency, Upstream latency by route
- **Row 3 — Health & Rate Limiting:** Healthy upstreams gauge, Rate limit rejections/sec
- **Row 4 — Feature Flags:** Flag evaluation rate, Flag hit rate

---

## Docker Compose Stack

```
docker compose up

Services:
  postgres          :5432    PostgreSQL 16
  redis             :6379    Redis 7
  kafka             :9092    Confluent KRaft (no ZooKeeper)
  prometheus        :9090    Prometheus
  grafana           :3001    Grafana (pre-provisioned dashboards)
  gateway-1         :8080    EdgeFlow instance 1
  gateway-2         :8081    EdgeFlow instance 2
  user-service      :9001    Sample backend
  order-service     :9002    Sample backend
  inventory-service :9003    Sample backend
  dashboard         :3000    React admin UI (Vite)
```

All services on a shared `edgeflow-net` bridge network.

---

## React Dashboard (Vite + TypeScript)

Located in `dashboard/`. Five pages:

| Page | Purpose |
|------|---------|
| Routes | CRUD routes and upstreams, enable/disable toggle |
| Health | Per-route health cards with status indicators, auto-refresh |
| Rate Limits | Create/edit/delete rate limit rules |
| Feature Flags | Flag list, toggle, rollout slider, test evaluation panel |
| Metrics | Embedded Grafana iframe or Recharts panels |

Stack: React, React Router, TanStack Query, Axios, Tailwind CSS, Recharts.

---

## Dependencies (build.gradle)

Added incrementally per phase:

```groovy
// Phase 2: Dynamic Routing
implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
runtimeOnly 'org.postgresql:postgresql'
implementation 'org.flywaydb:flyway-core'
implementation 'org.flywaydb:flyway-database-postgresql'
implementation 'com.github.ben-manes.caffeine:caffeine:3.1.8'

// Phase 5: Rate Limiting
implementation 'org.springframework.boot:spring-boot-starter-data-redis'

// Phase 6: Feature Flags
implementation 'com.google.guava:guava:33.0.0-jre'

// Phase 7: Kafka
implementation 'org.springframework.kafka:spring-kafka'

// Phase 8: Observability
implementation 'org.springframework.boot:spring-boot-starter-actuator'
implementation 'io.micrometer:micrometer-registry-prometheus'
```

---

## Phase Dependency Graph

```
Phase 1: Reverse Proxy (DONE)
    |
    v
Phase 2: Dynamic Routing + PostgreSQL (DONE)
    |
    +----------+-----------+
    |          |           |
    v          v           v
Phase 3     Phase 5     Phase 6
Load        Rate        Feature
Balancing   Limiting    Flags
(DONE)      (DONE)      (DONE)
    |          |           |
    v          |           |
Phase 4        |           |
Health         |           |
Checks         |           |
(DONE)         |           |
    |          |           |
    +----------+-----------+
               |
               v
         Phase 7: Kafka Events (DONE)
               |
               v
         Phase 8: Observability (DONE)
               |
               v
         Phase 9: React Dashboard
```

Phase 2 is the foundation. Phases 3, 5, 6 can be built in parallel after it. Dashboard can start as soon as Phase 2 admin APIs exist and grow incrementally.