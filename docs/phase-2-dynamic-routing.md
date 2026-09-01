# Phase 2: Dynamic Routing — Learning Guide

## What You'll Learn

- Why static configuration breaks down and how dynamic routing solves it
- How database migrations work with Flyway
- How JPA and Spring Data turn tables into Java objects
- How in-memory caching with Caffeine prevents hammering the database
- The Strategy pattern — abstracting route resolution behind an interface
- Host-based routing (path, subdomain, hybrid strategies)
- How to design a RESTful admin API with proper DTOs
- Docker Compose for running PostgreSQL alongside your app

---

## 1. Why Dynamic Routing Matters

### The Problem with Static Config

In Phase 1, routes lived in `application.yml`:

```yaml
edgeflow:
  routes:
    - path-prefix: /api/users
      upstream: http://localhost:8080/mock/users
```

To change where `/api/users` points, you must:
1. Edit the YAML file
2. Restart the application
3. All in-flight requests are dropped during restart

This is fine for development. In production, it's a problem:

- **Downtime on every change** — even a single route update requires a restart
- **No audit trail** — who changed what, when?
- **No API access** — you can't build a dashboard to manage routes if they're in a file
- **Multi-instance coordination** — if you run 3 EdgeFlow instances, you need to update and restart all 3

### How Real Systems Handle It

| System | Configuration Storage | Update Mechanism |
|--------|----------------------|------------------|
| **Kong** | PostgreSQL or Cassandra | Admin REST API, no restart |
| **NGINX** | Config files | `nginx -s reload` (graceful, but still file-based) |
| **AWS API Gateway** | AWS internal DB | AWS Console / CLI / API |
| **Envoy** | xDS protocol | Control plane pushes updates |
| **Spring Cloud Gateway** | Java config or DB | Actuator endpoints to refresh routes |

EdgeFlow follows Kong's approach: routes in PostgreSQL, managed via admin API, cached in memory for performance.

### What Changed in Phase 2

```
Phase 1:                          Phase 2:
YAML → RouteConfig → Proxy       DB → DatabaseRouteResolver → Proxy
                                       ↑
                                  Admin API (CRUD)
                                       ↑
                                  Dashboard / curl
```

---

## 2. Flyway — Database Migrations

### What Is Flyway?

Flyway is a database migration tool. It tracks which SQL scripts have been applied and runs new ones automatically on startup.

Think of it like `git` for your database schema:
- Each migration is a versioned SQL file
- Flyway runs them in order
- Once applied, a migration is never changed
- Flyway keeps a `flyway_schema_history` table to track what's been run

### How It Works

```
App starts → Flyway checks flyway_schema_history
  → V1 applied? No → run V1__create_routes.sql
  → V2 applied? No → run V2__create_upstreams.sql
  → V3 applied? No → run V3__seed_default_routes.sql
  → All done, app continues booting
```

### File Naming Convention

```
V1__create_routes.sql
│ │              │
│ │              └── description (underscores for spaces)
│ └── double underscore separator
└── version number (must be unique and sequential)
```

- `V1`, `V2`, `V3` — applied in version order
- Once a migration is applied, Flyway checksums it. If you edit an applied migration, Flyway will refuse to start (data integrity protection)
- To change a table later, create a new migration: `V4__add_column_to_routes.sql`

### Our Migrations

**V1__create_routes.sql** — the routes table:

```sql
CREATE TABLE routes (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    host            VARCHAR(255),            -- nullable: NULL = match any host
    path_prefix     VARCHAR(255) NOT NULL,
    description     VARCHAR(500),
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    strip_prefix    BOOLEAN NOT NULL DEFAULT FALSE,
    timeout_ms      INT NOT NULL DEFAULT 30000,
    retry_count     INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(host, path_prefix)                -- same prefix can exist on different hosts
);
```

Key design decisions:
- `host` is nullable — `NULL` means "match any host" (path-only routing)
- `UNIQUE(host, path_prefix)` — prevents duplicate routes. `/api/users` can exist once for `api.company.com` and once for `admin.company.com`
- `enabled` — soft-delete/disable without removing the route
- `timeout_ms` and `retry_count` — per-route configuration (not used yet, but ready for later phases)

**V2__create_upstreams.sql** — the upstreams table:

```sql
CREATE TABLE upstreams (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    route_id        BIGINT NOT NULL,
    url             VARCHAR(500) NOT NULL,
    weight          INT NOT NULL DEFAULT 1,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_upstreams_route FOREIGN KEY (route_id)
        REFERENCES routes(id) ON DELETE CASCADE
);
```

Why a separate table?
- In Phase 1, each route had one upstream URL
- In Phase 3 (Load Balancing), each route will have multiple upstreams
- `ON DELETE CASCADE` — when a route is deleted, its upstreams are automatically removed
- `weight` — for weighted round-robin in Phase 3 (ignored for now)

**V3__seed_default_routes.sql** — pre-populates the 3 mock routes so the app works identically to Phase 1 out of the box.

### Configuration

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
```

That's it. Flyway auto-detects the database type (H2 or PostgreSQL) and runs the right SQL dialect.

**Why H2 for local dev?** H2 is an in-memory database that comes bundled as a JAR. No installation, no Docker needed. You run `./gradlew bootRun` and it works. For Docker/production, the `application-docker.yml` profile switches to PostgreSQL.

---

## 3. JPA and Spring Data

### What Is JPA?

JPA (Jakarta Persistence API) is a standard for mapping Java objects to database tables. Instead of writing SQL by hand for every operation, you annotate a class and JPA handles the mapping.

```
Java Object          ←→          Database Table
Route.java           ←→          routes
  id: Long           ←→          id BIGINT
  pathPrefix: String ←→          path_prefix VARCHAR
  enabled: boolean   ←→          enabled BOOLEAN
```

### The Route Entity

```java
@Entity
@Table(name = "routes")
public class Route {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "host")
    private String host;

    @Column(name = "path_prefix", nullable = false)
    private String pathPrefix;
```

What each annotation does:

| Annotation | Purpose |
|-----------|---------|
| `@Entity` | Marks this class as a JPA entity — it maps to a database table |
| `@Table(name = "routes")` | Specifies the table name (otherwise JPA would use the class name) |
| `@Id` | Marks the primary key field |
| `@GeneratedValue(strategy = IDENTITY)` | The database auto-generates the ID (AUTO_INCREMENT) |
| `@Column(name = "path_prefix")` | Maps the Java field `pathPrefix` to the column `path_prefix` |
| `@Column(nullable = false)` | Adds a NOT NULL constraint at the JPA level |

### The @OneToMany Relationship

```java
@OneToMany(mappedBy = "route", cascade = CascadeType.ALL,
           orphanRemoval = true, fetch = FetchType.EAGER)
private List<Upstream> upstreams = new ArrayList<>();
```

This says: "A Route has many Upstreams."

| Parameter | Meaning |
|-----------|---------|
| `mappedBy = "route"` | The `route` field in `Upstream.java` owns the relationship (has the FK column) |
| `cascade = CascadeType.ALL` | When you save/delete a Route, JPA automatically saves/deletes its Upstreams too |
| `orphanRemoval = true` | If you remove an Upstream from the list, JPA deletes it from the DB |
| `fetch = FetchType.EAGER` | Load upstreams immediately when loading a route (not lazily) |

The other side — `Upstream.java`:

```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "route_id", nullable = false)
@JsonIgnore
private Route route;
```

| Parameter | Meaning |
|-----------|---------|
| `@ManyToOne` | Many upstreams belong to one route |
| `@JoinColumn(name = "route_id")` | This column in the `upstreams` table holds the foreign key |
| `fetch = FetchType.LAZY` | Don't load the parent Route when loading an Upstream (avoids infinite loops) |
| `@JsonIgnore` | When serializing to JSON, don't include the parent Route (avoids circular reference) |

### The Helper Methods

```java
public void addUpstream(Upstream upstream) {
    upstreams.add(upstream);
    upstream.setRoute(this);   // maintain both sides of the relationship
}
```

In JPA, you must set both sides of a bidirectional relationship. If you only add the upstream to the list but don't set `upstream.setRoute(this)`, JPA may not persist the foreign key correctly.

### @PrePersist and @PreUpdate

```java
@PrePersist
protected void onCreate() {
    createdAt = LocalDateTime.now();
    updatedAt = LocalDateTime.now();
}

@PreUpdate
protected void onUpdate() {
    updatedAt = LocalDateTime.now();
}
```

These are JPA lifecycle callbacks — methods that run automatically:
- `@PrePersist` — before the entity is first saved (INSERT)
- `@PreUpdate` — before the entity is updated (UPDATE)

This ensures `createdAt` and `updatedAt` are always set correctly without manual code in every service method.

### Spring Data Repositories

```java
@Repository
public interface RouteRepository extends JpaRepository<Route, Long> {
    List<Route> findAllByEnabledTrue();
}
```

You write an interface, Spring generates the implementation at runtime. `JpaRepository<Route, Long>` gives you for free:

| Method | SQL Equivalent |
|--------|---------------|
| `findAll()` | `SELECT * FROM routes` |
| `findById(1L)` | `SELECT * FROM routes WHERE id = 1` |
| `save(route)` | `INSERT INTO routes ...` or `UPDATE routes ...` |
| `deleteById(1L)` | `DELETE FROM routes WHERE id = 1` |
| `existsById(1L)` | `SELECT COUNT(*) > 0 FROM routes WHERE id = 1` |

The custom method `findAllByEnabledTrue()` is a **derived query** — Spring parses the method name and generates:
```sql
SELECT * FROM routes WHERE enabled = TRUE
```

No SQL written. The naming convention is `findAllBy` + field name + condition.

---

## 4. Caffeine Caching

### Why Cache Routes?

Without caching, every proxy request triggers a database query:

```
Request 1 → SELECT * FROM routes → match → proxy
Request 2 → SELECT * FROM routes → match → proxy
Request 3 → SELECT * FROM routes → match → proxy
... 10,000 requests/sec = 10,000 DB queries/sec
```

Routes rarely change (maybe a few times per day). Querying the database for every request is wasteful. With caching:

```
Request 1 → cache miss → SELECT * FROM routes → store in cache → match → proxy
Request 2 → cache hit → match → proxy (no DB query)
...
After 60 seconds, cache expires → next request reloads from DB
```

### How Caffeine Works

Caffeine is a high-performance, in-memory cache library for Java. It's the successor to Google Guava's cache.

```java
private final Cache<String, Optional<ResolvedRoute>> cache;

public DatabaseRouteResolver(RouteRepository routeRepository) {
    this.routeRepository = routeRepository;
    this.cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60))    // entries expire 60s after creation
            .maximumSize(1000)                           // max 1000 entries
            .build();
}
```

| Setting | Meaning |
|---------|---------|
| `expireAfterWrite(60s)` | Each entry is valid for 60 seconds after it was written |
| `maximumSize(1000)` | If cache exceeds 1000 entries, least-recently-used entries are evicted |

### Cache Key Design

```java
String cacheKey = (host != null ? host : "*") + "::" + path;
```

Examples:
- `*::/api/users/123` — path-only request
- `api.company.com::/api/users/123` — host + path request

The key includes both host and full path because the same path could resolve differently for different hosts.

### Cache Invalidation

The hardest problem in computer science — "when does the cache become stale?"

We invalidate the cache in two scenarios:
1. **Time-based** — entries expire after 60 seconds automatically
2. **Explicit** — when the admin API creates/updates/deletes a route, it calls `routeResolver.invalidateCache()`

```java
// In RouteAdminController, after every mutation:
routeResolver.invalidateCache();
```

This is a simple "nuke everything" approach. A more sophisticated approach would invalidate only the affected entries, but for our scale it doesn't matter.

**The cache stampede problem:** If 1000 requests arrive right after the cache expires, all 1000 hit the database simultaneously. Caffeine handles this by default — when multiple threads request the same key, only one actually computes the value (calls the DB). The others wait for that result. This is called **write coalescing**.

### Why Not Redis?

For Phase 2, Caffeine (in-process memory) is the right choice:
- Single EdgeFlow instance — no need for shared cache
- Sub-microsecond lookups — faster than any network call
- Zero infrastructure — no Redis server to manage

In Phase 7 (Kafka), we'll use Kafka events to invalidate Caffeine caches across multiple instances. Redis is added in Phase 5 for rate limiting, which actually needs shared state.

---

## 5. The RouteResolver Pattern

### Why an Interface?

In Phase 1, `ProxyController` directly used `RouteConfig`:

```java
// Phase 1: tightly coupled
public ProxyController(RouteConfig routeConfig) {
    this.routeConfig = routeConfig;
}
var routeOpt = routeConfig.findRoute(path);
```

This is fine for one implementation. But now we have two:
1. `DatabaseRouteResolver` — reads from PostgreSQL + Caffeine cache
2. `YamlRouteResolver` — reads from `application.yml` (backward compatibility)

And in the future, there could be more (etcd, Consul, etc.).

The **Strategy pattern** solves this:

```java
// Phase 2: depends on interface, not implementation
public ProxyController(RouteResolver routeResolver) {
    this.routeResolver = routeResolver;
}
var routeOpt = routeResolver.resolve(host, path);
```

`ProxyController` doesn't know or care which implementation it's using. Spring injects the right one.

### The Interface

```java
public interface RouteResolver {
    Optional<ResolvedRoute> resolve(String host, String path);
    void invalidateCache();

    record ResolvedRoute(
            Long routeId,
            String host,
            String pathPrefix,
            String upstreamUrl,
            boolean stripPrefix,
            int timeoutMs
    ) {}
}
```

Why `ResolvedRoute` as a record?
- It's a simple data carrier — immutable, no behavior
- Java records auto-generate `equals()`, `hashCode()`, and `toString()`
- The proxy only needs these fields to forward a request
- It decouples the proxy from the JPA entity (`Route.java`)

### @Primary — How Spring Chooses

When Spring sees two beans implementing the same interface, it needs to know which one to inject. `@Primary` marks the default:

```java
@Service
@Primary                            // ← this one gets injected by default
public class DatabaseRouteResolver implements RouteResolver { ... }

@Service
public class YamlRouteResolver implements RouteResolver { ... }
```

When `ProxyController` asks for a `RouteResolver`, Spring gives it `DatabaseRouteResolver`. The `YamlRouteResolver` still exists as a bean — you could inject it explicitly with `@Qualifier("yamlRouteResolver")` if needed.

### Longest Prefix Match

Phase 1 used `findFirst()` — the first matching route wins. Phase 2 uses longest prefix match:

```java
.max(Comparator.comparingInt(route -> route.getPathPrefix().length()))
```

Why it matters:

```
Routes in DB:
  /api/users          → user-service
  /api/users/admin    → user-admin-service

Request: GET /api/users/admin/settings

findFirst() → matches /api/users (wrong!)
longest match → matches /api/users/admin (correct!)
```

The most specific route should always win. This is how NGINX, Kong, and every serious router works.

---

## 6. Host-Based Routing

### How the Host Header Works

Every HTTP request includes a `Host` header:

```
GET /api/users/123 HTTP/1.1
Host: api.company.com         ← this is the host
```

The browser sets this automatically based on the URL. When you visit `https://api.company.com/users/123`, the browser sends `Host: api.company.com`.

In the proxy, we extract it:

```java
String host = request.getServerName();   // returns "api.company.com"
```

### The Matching Logic

```java
private boolean matchesHost(Route route, String host) {
    // If route has no host set, it matches any host (path-only routing)
    if (route.getHost() == null || route.getHost().isEmpty()) {
        return true;
    }
    // Otherwise, exact host match required
    return route.getHost().equalsIgnoreCase(host);
}
```

This single method enables all three routing strategies:

| Strategy | Route host | Route pathPrefix | Request | Match? |
|----------|-----------|-----------------|---------|--------|
| Path-based | `NULL` | `/api/users` | `GET /api/users/123` from any host | Yes |
| Subdomain | `users.company.com` | `/` | `GET /123` with Host: users.company.com | Yes |
| Subdomain | `users.company.com` | `/` | `GET /123` with Host: orders.company.com | No |
| Hybrid | `api.company.com` | `/api/users` | `GET /api/users/1` with Host: api.company.com | Yes |

### Testing Host-Based Routing

You can test subdomain routing locally with curl's `-H` flag:

```bash
# Create a subdomain-based route
curl -X POST http://localhost:8080/admin/api/v1/routes \
  -H "Content-Type: application/json" \
  -d '{
    "host": "users.myapp.com",
    "pathPrefix": "/",
    "description": "Subdomain routing for users",
    "upstreams": [{"url": "http://localhost:8080/mock/users"}]
  }'

# Test with the matching host header
curl -H "Host: users.myapp.com" http://localhost:8080/42
# → {"id":42,"name":"User 42","email":"user42@example.com"}

# Test with a non-matching host — falls through to path-based routes
curl -H "Host: other.myapp.com" http://localhost:8080/42
# → 404 (no route matched)
```

---

## 7. Admin API Design

### RESTful Conventions

The admin API follows REST conventions:

| Operation | Method | Path | Status Code |
|-----------|--------|------|-------------|
| List all | `GET` | `/admin/api/v1/routes` | 200 OK |
| Get one | `GET` | `/admin/api/v1/routes/{id}` | 200 OK / 404 |
| Create | `POST` | `/admin/api/v1/routes` | 201 Created |
| Update | `PUT` | `/admin/api/v1/routes/{id}` | 200 OK / 404 |
| Delete | `DELETE` | `/admin/api/v1/routes/{id}` | 204 No Content / 404 |

**Why 201 for create?** HTTP 201 means "a new resource was created." It's more informative than 200.

**Why 204 for delete?** HTTP 204 means "success, but no content to return." The resource is gone — there's nothing to send back.

### Why DTOs Instead of Entities?

The admin controller uses `RouteRequest` (input) and `RouteResponse` (output) instead of the `Route` entity directly.

**Bad — exposing entities:**

```java
@PostMapping
public Route createRoute(@RequestBody Route route) {
    return routeRepository.save(route);
}
```

Problems:
- Client can set `id`, `createdAt`, `updatedAt` — fields they shouldn't control
- JPA lazy-loading proxies leak into JSON (causes serialization errors)
- Internal schema changes break the API contract
- No control over what's exposed

**Good — using DTOs:**

```java
@PostMapping
public ResponseEntity<RouteResponse> createRoute(@RequestBody RouteRequest request) {
    Route route = new Route();
    route.setPathPrefix(request.getPathPrefix());
    // ... map fields explicitly
    Route saved = routeRepository.save(route);
    return ResponseEntity.status(HttpStatus.CREATED)
            .body(RouteResponse.from(saved));
}
```

Benefits:
- Client only sees/controls what you allow
- API contract is decoupled from database schema
- `RouteResponse.from(entity)` is a clean factory method

### How Admin Paths Avoid the Proxy

`ProxyController` catches `/**` (everything). So how does `/admin/api/v1/routes` not get proxied?

Spring MVC resolves the most specific `@RequestMapping` first. `@RequestMapping("/admin/api/v1/routes")` is more specific than `@RequestMapping("/**")`, so Spring routes admin requests to `RouteAdminController`, not `ProxyController`.

This works because:
1. Spring collects all `@RequestMapping` patterns at startup
2. For each request, it finds the best match by specificity
3. `/**` is the least specific — it only catches what nothing else matches

---

## 8. Code Walkthrough — DatabaseRouteResolver

This is the core of Phase 2. Let's walk through it:

```java
@Service
@Primary
public class DatabaseRouteResolver implements RouteResolver {
```

- `@Service` — Spring component, auto-detected by component scan
- `@Primary` — when multiple `RouteResolver` beans exist, this one is injected by default

```java
    private final RouteRepository routeRepository;
    private final Cache<String, Optional<ResolvedRoute>> cache;

    public DatabaseRouteResolver(RouteRepository routeRepository) {
        this.routeRepository = routeRepository;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(60))
                .maximumSize(1000)
                .build();
    }
```

- Constructor injection for the repository
- Cache is created inline — 60 second TTL, max 1000 entries
- Cache value is `Optional<ResolvedRoute>` — we cache "no match" results too, so repeated requests to unmatchable paths don't hit the DB

```java
    @Override
    public Optional<ResolvedRoute> resolve(String host, String path) {
        String cacheKey = (host != null ? host : "*") + "::" + path;
        return cache.get(cacheKey, key -> doResolve(host, path));
    }
```

- `cache.get(key, loadFunction)` — if the key exists in cache, return it. Otherwise, call `doResolve()`, store the result, and return it
- This is atomic — if 100 threads request the same key simultaneously, only one calls `doResolve()`. The others wait (Caffeine's write coalescing)

```java
    private Optional<ResolvedRoute> doResolve(String host, String path) {
        List<Route> enabledRoutes = routeRepository.findAllByEnabledTrue();

        return enabledRoutes.stream()
                .filter(route -> matchesHost(route, host))
                .filter(route -> path.startsWith(route.getPathPrefix()))
                .max(Comparator.comparingInt(route -> route.getPathPrefix().length()))
                .flatMap(route -> pickUpstream(route));
    }
```

The resolution pipeline:
1. Load all enabled routes from DB
2. Filter by host (null host = match any)
3. Filter by path prefix
4. Pick the longest matching prefix (most specific route wins)
5. Pick an enabled upstream from the matched route

```java
    private Optional<ResolvedRoute> pickUpstream(Route route) {
        return route.getUpstreams().stream()
                .filter(Upstream::isEnabled)
                .findFirst()
                .map(upstream -> new ResolvedRoute(
                        route.getId(),
                        route.getHost(),
                        route.getPathPrefix(),
                        upstream.getUrl(),
                        route.isStripPrefix(),
                        route.getTimeoutMs()
                ));
    }
```

Currently picks the first enabled upstream. In Phase 3 (Load Balancing), this will use a round-robin algorithm to distribute across multiple upstreams.

---

## 9. How Real Systems Do It

### Kong's Admin API

Kong's route management is very similar to what we built:

```bash
# Kong: create a route
curl -X POST http://localhost:8001/services/user-service/routes \
  -d "paths[]=/api/users" \
  -d "hosts[]=api.company.com"

# EdgeFlow: create a route
curl -X POST http://localhost:8080/admin/api/v1/routes \
  -H "Content-Type: application/json" \
  -d '{"host": "api.company.com", "pathPrefix": "/api/users", ...}'
```

Kong separates "services" (the backend) from "routes" (the matching rules). EdgeFlow combines them — a route has upstreams directly. Both approaches are valid; Kong's is more flexible for complex setups.

### NGINX Dynamic Reconfiguration

NGINX traditionally requires config files + reload:

```bash
# Edit /etc/nginx/conf.d/api.conf
# Then: nginx -s reload
```

NGINX Plus (commercial) has an API for dynamic upstreams, but the route configuration is still file-based. EdgeFlow's approach (DB + API) is more dynamic.

### Spring Cloud Gateway

```java
// Spring Cloud Gateway: programmatic routes
@Bean
public RouteLocator routes(RouteLocatorBuilder builder) {
    return builder.routes()
        .route("users", r -> r
            .host("users.company.com")
            .and()
            .path("/api/users/**")
            .uri("http://user-service:8081"))
        .build();
}
```

Spring Cloud Gateway can also load routes from a database via a `RouteDefinitionRepository`. EdgeFlow's `DatabaseRouteResolver` is doing essentially the same thing.

---

## 10. Docker Compose

### What Is Docker Compose?

Docker Compose lets you define and run multi-container applications. Instead of starting PostgreSQL and EdgeFlow separately, you run:

```bash
docker compose up
```

And both services start together with networking already configured.

### The docker-compose.yml

```yaml
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: edgeflow
      POSTGRES_USER: edgeflow
      POSTGRES_PASSWORD: edgeflow
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data    # data survives container restarts
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U edgeflow"]
      interval: 5s
      retries: 5
```

- `image: postgres:16-alpine` — lightweight PostgreSQL 16 image
- `volumes: pgdata` — persists data even if the container is recreated
- `healthcheck` — Docker checks if PostgreSQL is ready before starting dependent services

```yaml
  gateway:
    build: .
    environment:
      SPRING_PROFILES_ACTIVE: docker    # activates application-docker.yml
    depends_on:
      postgres:
        condition: service_healthy      # wait for PostgreSQL to be ready
```

- `SPRING_PROFILES_ACTIVE: docker` — tells Spring Boot to load `application-docker.yml`, which has the PostgreSQL connection string
- `depends_on` with `service_healthy` — the gateway only starts after PostgreSQL passes its health check

### Spring Profiles

Spring profiles let you have different configurations for different environments:

- `application.yml` — default config (H2 in-memory database)
- `application-docker.yml` — overrides for Docker (PostgreSQL connection)

When `SPRING_PROFILES_ACTIVE=docker`, Spring merges both files, with `application-docker.yml` overriding any conflicting values.

---

## 11. What's Still Missing

| Feature | Why It Matters | Phase |
|---------|---------------|-------|
| Load balancing | Only one upstream is used per route (first enabled) | Phase 3 |
| Health checks | No way to know if an upstream is down | Phase 4 |
| Rate limiting | No protection against abuse | Phase 5 |
| Feature flags | No traffic splitting | Phase 6 |
| Cross-instance cache invalidation | If you run 2 EdgeFlow instances, changing a route on one doesn't invalidate the cache on the other | Phase 7 (Kafka) |
| Request validation | No validation on admin API inputs (missing pathPrefix, invalid URL) | Could be added now |
| Pagination | `GET /admin/api/v1/routes` returns all routes — won't scale to thousands | Could be added now |

---

## 12. Key Concepts to Remember

| Concept | EdgeFlow Example |
|---------|-----------------|
| **Database migrations** | Flyway runs V1, V2, V3 SQL files on startup — schema is version-controlled |
| **ORM (Object-Relational Mapping)** | `@Entity Route` maps to `routes` table — JPA handles SQL generation |
| **@OneToMany** | A Route has many Upstreams — cascaded saves and deletes |
| **Spring Data repositories** | Write an interface, Spring generates `findAll()`, `save()`, `deleteById()` for free |
| **In-memory caching** | Caffeine caches route lookups for 60s — DB only queried on cache miss |
| **Cache invalidation** | Admin API calls `invalidateCache()` after every mutation |
| **Strategy pattern** | `RouteResolver` interface decouples proxy from route source (DB vs YAML) |
| **@Primary** | When multiple implementations exist, marks the default one |
| **Longest prefix match** | `/api/users/admin` beats `/api/users` for path `/api/users/admin/settings` |
| **Host-based routing** | Match on `Host` header — enables subdomain and hybrid routing strategies |
| **DTOs** | `RouteRequest` / `RouteResponse` decouple API contract from database schema |
| **Spring profiles** | `application.yml` for local dev (H2), `application-docker.yml` for Docker (PostgreSQL) |
| **Docker Compose** | `docker compose up` starts PostgreSQL + EdgeFlow together with networking |
