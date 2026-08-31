# Phase 1: Reverse Proxy — Learning Guide

## What You'll Learn

- What a reverse proxy is and why every production system uses one
- How HTTP request forwarding works at the protocol level
- How to build a catch-all request handler in Spring Boot
- How configuration binding works with `@ConfigurationProperties`
- Path-prefix routing and URL rewriting
- Different routing strategies (path-based, subdomain-based, hybrid)
- Different routing strategies (path-based, subdomain-based, hybrid)

---

## 1. What Is a Reverse Proxy?

A **reverse proxy** is a server that sits between clients and backend services. The client talks to the proxy, and the proxy forwards the request to the actual backend.

```
Without reverse proxy:
  Client --> User Service    (client must know every service)
  Client --> Order Service
  Client --> Payment Service

With reverse proxy:
  Client --> EdgeFlow --> User Service
                      --> Order Service
                      --> Payment Service
```

The client only knows one address. The proxy figures out where to send each request.

### Why Use One?

| Reason | Example |
|--------|---------|
| **Single entry point** | Clients hit `api.myapp.com`, not 15 different service URLs |
| **URL routing** | `/api/users` goes to the user service, `/api/orders` goes to the order service |
| **Security** | Backend services aren't exposed to the internet directly |
| **Cross-cutting concerns** | Add rate limiting, auth, logging in one place instead of every service |
| **SSL termination** | Handle HTTPS at the proxy, backends use plain HTTP internally |

### Real-World Examples

- **NGINX** — the most widely used reverse proxy. Config-file driven. Routes based on `location` blocks.
- **AWS ALB/API Gateway** — managed reverse proxy as a cloud service.
- **Kong** — API gateway built on NGINX with plugin system.
- **Spring Cloud Gateway** — reactive reverse proxy for Java microservices.
- **Cloudflare** — acts as a reverse proxy at the DNS/CDN level.

EdgeFlow is doing what all of these do, but simplified so you can understand the internals.

### Routing Strategies

There are three common ways API gateways decide where to send a request:

**Option 1: Path-Based Routing (most common)**

Single domain, route based on the URL path.

```
https://api.company.com/users/123      --> user-service
https://api.company.com/orders/456     --> order-service
https://api.company.com/payments/789   --> payment-service
```

This is what EdgeFlow does in Phase 1. Benefits: single DNS entry, easy TLS, works with every gateway (Kong, NGINX, Envoy, Traefik).

**Option 2: Subdomain-Based Routing**

Each service gets its own hostname.

```
https://users.company.com/123          --> user-service
https://orders.company.com/456         --> order-service
https://payments.company.com/789       --> payment-service
```

The gateway matches on the `Host` header instead of (or in addition to) the path. Good when teams own services independently or need different security policies.

**Option 3: Hybrid**

Combine host and path matching. Very common in enterprises.

```
https://api.company.com/inventory/*    --> inventory-service
https://admin.company.com/users/*      --> user-admin-service
```

Internally, the backend services use Kubernetes DNS names like `inventory-service.namespace.svc.cluster.local`, but clients only see `api.company.com`.

**How EdgeFlow supports all three:**

The route model has an optional `host` field:

```java
Route {
    host: "api.company.com"     // null = match any host (path-only)
    pathPrefix: "/api/users"
    upstream: "http://user-service:8081"
}
```

Matching logic:

```java
// host is null? match any host. Otherwise, must match exactly.
(route.getHost() == null || route.getHost().equals(requestHost))
    && requestPath.startsWith(route.getPathPrefix())
```

| Strategy | host field | pathPrefix |
|----------|-----------|------------|
| Path-based | `null` | `/api/users` |
| Subdomain | `users.company.com` | `/` |
| Hybrid | `api.company.com` | `/inventory` |

In Phase 1 we only match on path (host is effectively null). Phase 2 adds host-based matching when routes move to PostgreSQL.

---

## 2. How HTTP Forwarding Works

When a reverse proxy forwards a request, it needs to preserve certain things and modify others.

### What Gets Preserved

| Part | Why |
|------|-----|
| **HTTP method** | If the client sends `POST`, the upstream must receive `POST` |
| **Request body** | The payload (JSON, form data, etc.) must arrive intact |
| **Headers** | `Content-Type`, `Authorization`, cookies — the upstream needs these |
| **Query parameters** | `?page=2&sort=name` must be forwarded as-is |

### What Gets Modified

| Part | Why |
|------|-----|
| **URL** | The proxy rewrites the path. `/api/users/123` becomes `/mock/users/123` on the upstream |
| **Host header** | Stripped. The upstream should see its own hostname, not the proxy's |
| **transfer-encoding** | Stripped from the response. The proxy sends the response as a whole, not chunked |

### The Forwarding Sequence

```
1. Client sends:    GET /api/users/123?fields=name HTTP/1.1
                    Host: api.myapp.com
                    Authorization: Bearer xyz

2. Proxy receives the request

3. Proxy looks up route:
   /api/users --> upstream http://localhost:8080/mock/users

4. Proxy strips the matched prefix:
   /api/users/123 minus /api/users = /123

5. Proxy builds target URL:
   http://localhost:8080/mock/users + /123 + ?fields=name

6. Proxy forwards:  GET /mock/users/123?fields=name HTTP/1.1
                    Authorization: Bearer xyz
                    (Host header removed)

7. Upstream responds: 200 OK, {"id": 123, "name": "User 123"}

8. Proxy returns upstream's response to the client as-is
```

---

## 3. Code Walkthrough

### 3.1 Entry Point — `EdgeFlowApplication.java`

```java
@SpringBootApplication
@EnableConfigurationProperties(RouteConfig.class)
public class EdgeFlowApplication {
    public static void main(String[] args) {
        SpringApplication.run(EdgeFlowApplication.class, args);
    }
}
```

**What's happening:**

- `@SpringBootApplication` — combines `@Configuration`, `@EnableAutoConfiguration`, and `@ComponentScan`. Spring Boot scans the `com.edgeflow` package for components.
- `@EnableConfigurationProperties(RouteConfig.class)` — tells Spring to bind the `edgeflow.*` section of `application.yml` into a `RouteConfig` bean. Without this, Spring doesn't know `RouteConfig` should be a managed bean.
- `SpringApplication.run(...)` — starts the embedded Tomcat server, loads all beans, and begins listening on port 8080.

**Why not just `@Component` on RouteConfig?** You could, but `@ConfigurationProperties` classes are specifically designed for binding YAML/properties. Using `@EnableConfigurationProperties` is the idiomatic Spring Boot approach — it makes it clear that this class represents configuration, not business logic.

---

### 3.2 Route Configuration — `RouteConfig.java`

```java
@ConfigurationProperties(prefix = "edgeflow")
public class RouteConfig {

    private List<Route> routes = new ArrayList<>();

    // getters and setters...

    public Optional<Route> findRoute(String requestPath) {
        return routes.stream()
                .filter(route -> requestPath.startsWith(route.getPathPrefix()))
                .findFirst();
    }

    public static class Route {
        private String pathPrefix;   // e.g., "/api/users"
        private String upstream;     // e.g., "http://localhost:8080/mock/users"
        // getters and setters...
    }
}
```

**What's happening:**

- `@ConfigurationProperties(prefix = "edgeflow")` — Spring reads `application.yml` and maps everything under `edgeflow:` into this class. The `routes` field maps to `edgeflow.routes`, which is a list. Each item in the list becomes a `Route` object with `pathPrefix` and `upstream` fields.

- **YAML to Java mapping:**
  ```yaml
  edgeflow:
    routes:
      - path-prefix: /api/users          # becomes route.pathPrefix = "/api/users"
        upstream: http://localhost:8080/mock/users  # becomes route.upstream = "http://..."
  ```
  Note: `path-prefix` in YAML (kebab-case) automatically maps to `pathPrefix` in Java (camelCase). Spring Boot handles this conversion — it's called **relaxed binding**.

- `findRoute(requestPath)` — iterates through routes and returns the first one where the request path starts with the route's prefix. For example, `/api/users/123` starts with `/api/users`, so it matches.

**Design choice — `findFirst()` vs longest prefix match:**

The current implementation uses `findFirst()`, which returns the first matching route in YAML order. This means route order matters:

```yaml
# This works:
- path-prefix: /api/users/admin   # checked first, more specific
- path-prefix: /api/users         # checked second, less specific

# This breaks:
- path-prefix: /api/users         # matches first, catches everything
- path-prefix: /api/users/admin   # never reached
```

A more robust approach (used in Phase 2) would sort by prefix length descending and match the longest prefix. But for Phase 1, the simpler approach is fine.

**What about host matching?**

Currently `findRoute` only checks the path. In Phase 2, routes will also have an optional `host` field, and matching becomes:

```java
public Optional<Route> findRoute(String host, String requestPath) {
    return routes.stream()
            .filter(route -> route.getHost() == null
                    || route.getHost().equals(host))
            .filter(route -> requestPath.startsWith(route.getPathPrefix()))
            .max(Comparator.comparingInt(r -> r.getPathPrefix().length()));
}
```

This enables path-based, subdomain-based, and hybrid routing with the same code. See the [Routing Strategies](#routing-strategies) section above for details.

---

### 3.3 The Reverse Proxy — `ProxyController.java`

This is the core of EdgeFlow. Let's break it down section by section.

#### Constructor and Dependencies

```java
@RestController
public class ProxyController {

    private final RouteConfig routeConfig;
    private final RestClient restClient;

    public ProxyController(RouteConfig routeConfig) {
        this.routeConfig = routeConfig;
        this.restClient = RestClient.create();
    }
```

- `@RestController` — makes this a Spring MVC controller where every method returns a response body (not a view name).
- `RestClient` — Spring Boot 3.2+ HTTP client. Simpler than `WebClient` (reactive) or `RestTemplate` (legacy). Created once and reused for all upstream calls.
- Constructor injection — `routeConfig` is injected by Spring. The `RestClient` is created internally since it doesn't need external configuration yet (in later phases, we'll make this a shared bean with connection pooling).

#### The Catch-All Handler

```java
    @RequestMapping("/**")
    public ResponseEntity<byte[]> proxy(HttpServletRequest request,
                                        @RequestBody(required = false) byte[] body) {
```

- `@RequestMapping("/**")` — matches **every** HTTP request to any path, with any HTTP method (GET, POST, PUT, DELETE, etc.). The `/**` pattern is a Spring path pattern that means "anything and everything."
- `ResponseEntity<byte[]>` — returns raw bytes. We don't want Spring to interpret or serialize the upstream response. Whatever bytes the upstream sends, we forward them as-is.
- `HttpServletRequest request` — the raw servlet request. Gives us access to the URI, headers, method, and query string.
- `@RequestBody(required = false) byte[] body` — the request body as raw bytes. `required = false` because GET requests don't have a body.

**Why `byte[]` and not `String`?** The proxy doesn't care what the body contains — it could be JSON, XML, binary data, a file upload. Using `byte[]` ensures we never accidentally corrupt the data by applying character encoding.

#### Route Matching

```java
        String path = request.getRequestURI();
        String queryString = request.getQueryString();

        var routeOpt = routeConfig.findRoute(path);
        if (routeOpt.isEmpty()) {
            return ResponseEntity.status(404)
                    .body("{\"error\": \"No route matched\"}".getBytes());
        }
```

- `getRequestURI()` — returns the path portion of the URL, e.g., `/api/users/123`. Does NOT include query parameters or the host.
- `getQueryString()` — returns everything after `?`, e.g., `page=2&sort=name`. Returns `null` if there are no query params.
- If no route matches, return 404. The proxy doesn't generate 404s for upstream resources — only for paths that don't map to any backend at all.

#### URL Construction

```java
        RouteConfig.Route route = routeOpt.get();
        String remainingPath = path.substring(route.getPathPrefix().length());
        String targetUrl = route.getUpstream() + remainingPath;
        if (queryString != null) {
            targetUrl += "?" + queryString;
        }
```

This is the URL rewriting logic:

```
Request path:     /api/users/123
Matched prefix:   /api/users
Remaining path:   /123              (everything after the prefix)
Upstream base:    http://localhost:8080/mock/users
Target URL:       http://localhost:8080/mock/users/123
```

The query string is appended if present: `http://localhost:8080/mock/users/123?fields=name`

**Why strip the prefix?** The upstream service doesn't know about EdgeFlow's routing. It expects requests at its own paths (`/mock/users/123`), not at the proxy's paths (`/api/users/123`). The prefix is EdgeFlow's concern, not the backend's.

#### Header Forwarding

```java
        HttpHeaders forwardHeaders = new HttpHeaders();
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            if (headerName.equalsIgnoreCase("host")) {
                continue;  // skip Host header
            }
            Enumeration<String> values = request.getHeaders(headerName);
            while (values.hasMoreElements()) {
                forwardHeaders.add(headerName, values.nextElement());
            }
        }
```

Copies all request headers to the upstream request, with one exception:

- **Host header is skipped.** The client sends `Host: localhost:8080` (the proxy's address). If we forward this to the upstream, the upstream might get confused — it expects its own hostname. By omitting it, the HTTP client sets the correct Host automatically based on the target URL.

**Why use `Enumeration` instead of a Map?** HTTP headers can have multiple values for the same name (e.g., multiple `Set-Cookie` headers). The `Enumeration`-based API from `HttpServletRequest` handles this correctly, iterating through all values for each header name.

#### Executing the Upstream Call

```java
        try {
            ResponseEntity<byte[]> response = restClient
                    .method(HttpMethod.valueOf(request.getMethod()))
                    .uri(targetUrl)
                    .headers(h -> h.addAll(forwardHeaders))
                    .body(body != null ? body : new byte[0])
                    .retrieve()
                    .toEntity(byte[].class);
```

- `.method(HttpMethod.valueOf(request.getMethod()))` — preserves the original HTTP method. If the client sent PUT, the upstream receives PUT.
- `.uri(targetUrl)` — the rewritten URL.
- `.headers(h -> h.addAll(forwardHeaders))` — attaches the copied headers.
- `.body(body != null ? body : new byte[0])` — forwards the request body. Sends empty bytes if there's no body (GET requests).
- `.retrieve().toEntity(byte[].class)` — executes the HTTP call and returns the full response (status + headers + body).

#### Response Forwarding

```java
            HttpHeaders responseHeaders = new HttpHeaders();
            response.getHeaders().forEach((name, values) -> {
                if (!name.equalsIgnoreCase("transfer-encoding")) {
                    responseHeaders.addAll(name, values);
                }
            });

            return ResponseEntity.status(response.getStatusCode())
                    .headers(responseHeaders)
                    .body(response.getBody());
```

Copies the upstream response back to the client:
- Status code forwarded as-is (200, 201, 204, etc.)
- Headers forwarded, except `transfer-encoding`
- Body forwarded as raw bytes

**Why strip `transfer-encoding`?** The upstream might send the response as `Transfer-Encoding: chunked` (streaming the body in pieces). But by the time our proxy receives it, `RestClient` has already assembled the full body into a `byte[]`. If we forward the `chunked` header, the client would expect chunked encoding but receive a complete body — causing parsing errors.

#### Error Handling

```java
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .body(e.getResponseBodyAsByteArray());
        } catch (ResourceAccessException e) {
            return ResponseEntity.status(502)
                    .body(("{\"error\": \"Upstream unreachable: " +
                            route.getUpstream() + "\"}").getBytes());
        }
```

Three scenarios:

1. **`HttpClientErrorException`** (4xx from upstream) — the upstream returned a client error (400 Bad Request, 404 Not Found, etc.). Forward the exact status and body to the client. The proxy doesn't mask upstream errors.

2. **`HttpServerErrorException`** (5xx from upstream) — the upstream had an internal error. Forward it as-is. The proxy is transparent.

3. **`ResourceAccessException`** (connection failed) — the upstream is down or unreachable (connection refused, timeout, DNS failure). Return **502 Bad Gateway**. This is the proxy's own error — it means "I tried to reach the backend but couldn't."

**Why 502 and not 500?** HTTP 502 specifically means "I'm a gateway/proxy and the upstream failed." HTTP 500 means "I (the server) had an internal error." The distinction tells the client whether the problem is the proxy itself or the service behind it.

---

### 3.4 Mock Backend — `MockBackendController.java`

```java
@RestController
@ConditionalOnProperty(name = "edgeflow.mock.enabled", havingValue = "true",
                        matchIfMissing = true)
public class MockBackendController {

    @GetMapping(value = "/mock/users/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getUser(@PathVariable int id) {
        return Map.of("id", id, "name", "User " + id,
                      "email", "user" + id + "@example.com");
    }
    // ... similar for orders and payments
}
```

**What's happening:**

- `@ConditionalOnProperty(...)` — this bean only exists when `edgeflow.mock.enabled=true` in the config. Set it to `false` and these endpoints disappear entirely. `matchIfMissing = true` means the mock is enabled by default if the property isn't set at all.
- These endpoints run inside the same Spring Boot app as the proxy. The proxy forwards requests to `localhost:8080/mock/...`, which hits these endpoints. This is a convenient loop for testing without running separate services.

**Why not test with real services?** You'd need to start multiple applications, manage ports, deal with networking. The mock controller lets you verify the entire proxy flow with zero external dependencies. In later phases, we'll replace these with real standalone services in Docker.

---

### 3.5 Configuration — `application.yml`

```yaml
server:
  port: 8080

edgeflow:
  mock:
    enabled: true
  routes:
    - path-prefix: /api/users
      upstream: http://localhost:8080/mock/users
    - path-prefix: /api/orders
      upstream: http://localhost:8080/mock/orders
    - path-prefix: /api/payments
      upstream: http://localhost:8080/mock/payments
```

- `server.port: 8080` — Tomcat listens on 8080.
- `edgeflow.mock.enabled: true` — activates the mock backend.
- `edgeflow.routes` — the routing table. Each entry maps a path prefix to an upstream URL.

The routes point to `localhost:8080` (the same server) because the mock backend runs in-process. In production, these would point to different hosts/ports.

---

## 4. Design Decisions

### Why `byte[]` for everything?

The proxy is **content-agnostic**. It doesn't parse, validate, or transform the data flowing through it. Using `byte[]` means:
- JSON, XML, Protobuf, file uploads — all work without changes
- No character encoding issues
- No serialization/deserialization overhead

### Why `@RequestMapping("/**")` instead of a Servlet Filter?

Both could work. `@RequestMapping` keeps us in Spring MVC land, which means:
- Easy parameter binding (`@RequestBody`, `HttpServletRequest`)
- Works with Spring's exception handling
- Simpler to understand and test

A Servlet Filter would be lower-level and marginally faster, but the difference is negligible. In Phase 3, we'll introduce our own filter chain *inside* the controller for finer control.

### Why `RestClient` instead of `WebClient`?

- `RestClient` is synchronous and simple — one thread per request.
- `WebClient` is reactive (non-blocking) — handles more concurrent requests with fewer threads, but adds complexity (Mono, Flux, reactive pipelines).
- For a learning project, synchronous code is easier to reason about and debug.
- At scale, `WebClient` or virtual threads (Java 21) would be better choices.

### Why strip the path prefix?

This is a design choice. Some proxies strip the prefix (pass-through routing), others preserve it.

- **Strip prefix** (what EdgeFlow does): `/api/users/123` becomes `/users/123` on the upstream. The upstream doesn't know about the proxy's routing scheme.
- **Preserve prefix**: `/api/users/123` arrives as `/api/users/123` on the upstream. The upstream must handle the full path.

Stripping is more common in API gateways because it decouples the public API path from the internal service path.

---

## 5. How Real API Gateways Do It

### NGINX

```nginx
location /api/users {
    proxy_pass http://user-service:8081/;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
}
```

NGINX does the same thing — matches a location, forwards to an upstream, manages headers. But it's written in C, handles thousands of connections via an event loop, and has been battle-tested for decades.

### Kong

Kong wraps NGINX with a plugin architecture. The proxy logic is the same, but you can add rate limiting, auth, logging via plugins without touching config files.

### Spring Cloud Gateway

```java
@Bean
public RouteLocator routes(RouteLocatorBuilder builder) {
    return builder.routes()
        .route("users", r -> r.path("/api/users/**")
            .uri("http://user-service:8081"))
        .build();
}
```

Spring Cloud Gateway is reactive (uses `WebClient` internally). It has a built-in filter chain, predicates for matching, and integrates with Spring's ecosystem. EdgeFlow is building something similar but simpler.

### What EdgeFlow Has That They All Share

1. Path-based routing
2. Header forwarding
3. URL rewriting
4. Error handling (502 for unreachable upstreams)

### What EdgeFlow Doesn't Have Yet

| Missing | Why It Matters | When It's Added |
|---------|---------------|-----------------|
| Host-based routing | Can't route by subdomain/hostname | Phase 2 |
| Dynamic routing | Can't change routes without restart | Phase 2 |
| Multiple upstreams | Single point of failure per route | Phase 3 |
| Health checks | No way to know if upstream is down before sending traffic | Phase 4 |
| Rate limiting | No protection against abuse | Phase 5 |
| Feature flags | No traffic splitting or rollout control | Phase 6 |
| Connection pooling | Creates a new connection per request | Phase 2 |
| Timeouts | No timeout on upstream calls | Phase 2 |
| Request logging | No visibility into what's flowing through | Phase 8 |

---

## 6. Request Flow Diagram

Here's what happens when you run `curl http://localhost:8080/api/orders/42`:

```
Step 1: curl sends HTTP request
  GET /api/orders/42 HTTP/1.1
  Host: localhost:8080
  User-Agent: curl/8.x
  Accept: */*

Step 2: Tomcat receives it, routes to ProxyController.proxy()

Step 3: Extract path = "/api/orders/42", queryString = null

Step 4: RouteConfig.findRoute("/api/orders/42")
  Checks: does "/api/orders/42" start with "/api/users"? No.
  Checks: does "/api/orders/42" start with "/api/orders"? Yes!
  Returns: Route{pathPrefix="/api/orders", upstream="http://localhost:8080/mock/orders"}

Step 5: Build target URL
  remainingPath = "/api/orders/42".substring("/api/orders".length()) = "/42"
  targetUrl = "http://localhost:8080/mock/orders" + "/42"
            = "http://localhost:8080/mock/orders/42"

Step 6: Copy headers (skip "host")
  Forward: User-Agent: curl/8.x, Accept: */*

Step 7: RestClient sends GET to http://localhost:8080/mock/orders/42

Step 8: MockBackendController.getOrder(42) returns:
  {"orderId": 42, "status": "SHIPPED", "amount": 49.99}

Step 9: ProxyController copies response status (200), headers, body

Step 10: curl receives:
  HTTP/1.1 200 OK
  Content-Type: application/json
  {"orderId":42,"status":"SHIPPED","amount":49.99}
```

---

## 7. Key Concepts to Remember

| Concept | EdgeFlow Example |
|---------|-----------------|
| **Reverse proxy** | Client talks to EdgeFlow, not the backend directly |
| **Path prefix matching** | `/api/users/123` matches the `/api/users` route |
| **Host-based routing** | Match on `Host` header for subdomain routing (Phase 2) |
| **Routing strategies** | Path-based, subdomain-based, and hybrid — same model, different config |
| **URL rewriting** | Strip the prefix, append the remainder to the upstream URL |
| **Header forwarding** | Copy client headers to upstream, skip Host |
| **Transparent proxying** | Forward upstream responses as-is, including errors |
| **502 Bad Gateway** | Returned when the upstream is unreachable — it's a proxy-specific error |
| **Content-agnostic** | Use `byte[]` — the proxy doesn't care what the payload is |
| **Configuration binding** | `@ConfigurationProperties` maps YAML to Java objects |
| **Conditional beans** | `@ConditionalOnProperty` enables/disables components via config |