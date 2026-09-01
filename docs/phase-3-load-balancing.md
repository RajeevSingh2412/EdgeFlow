# Phase 3: Load Balancing — Learning Guide

## What You'll Learn

- What load balancing is and why it matters
- The most common load balancing algorithms and their trade-offs
- How round-robin works (plain and weighted)
- Java concurrency primitives: `AtomicInteger`, `ConcurrentHashMap`, CAS operations
- The tension between caching and load balancing
- How real systems (NGINX, HAProxy, Kubernetes) handle load balancing

---

## 1. What Is Load Balancing?

In Phase 2, each route had one upstream — every request to `/api/orders` went to the same server. That's a single point of failure and a scalability bottleneck.

Load balancing distributes incoming requests across multiple instances of the same service:

```
Before (Phase 2):
  /api/orders → http://order-svc:8081  (always)

After (Phase 3):
  /api/orders → http://order-svc-1:8081  (request #1)
  /api/orders → http://order-svc-2:8082  (request #2)
  /api/orders → http://order-svc-3:8083  (request #3)
  /api/orders → http://order-svc-1:8081  (request #4, wraps around)
```

### Why?

| Problem | How Load Balancing Helps |
|---------|-------------------------|
| **Single point of failure** | If one instance dies, others handle traffic |
| **Scalability limit** | One server can only handle so many req/sec |
| **Uneven load** | Without balancing, one server gets hammered while others idle |
| **Deployments** | Roll out new versions one instance at a time |

### Where Does It Happen?

Load balancing can happen at different layers:

```
Layer 4 (TCP/IP):
  AWS NLB, HAProxy TCP mode
  Routes by IP + port, very fast, no request inspection

Layer 7 (HTTP):
  NGINX, HAProxy HTTP mode, Kong, EdgeFlow
  Routes by URL path, headers, cookies — more flexible

Client-side:
  Ribbon (Netflix), gRPC client LB
  Client maintains instance list, picks one per request
  No central load balancer needed

DNS-based:
  Route 53 weighted routing
  Returns different IPs for the same domain
  Coarse-grained, relies on DNS TTL
```

EdgeFlow operates at **Layer 7** — it inspects the HTTP request (path, host) and forwards to a chosen upstream.

---

## 2. Load Balancing Algorithms

### Round-Robin (What We Built)

The simplest algorithm. Cycle through upstreams in order:

```
Upstreams: [A, B, C]

Request 1 → A
Request 2 → B
Request 3 → C
Request 4 → A  (wraps around)
Request 5 → B
...
```

**Pros:** Dead simple, perfectly even distribution, no state beyond a counter.
**Cons:** Doesn't account for server capacity or current load.

**When to use:** Homogeneous infrastructure where all instances have equal capacity. This is the most common default in production.

### Weighted Round-Robin (Also What We Built)

Like round-robin, but instances with higher weight get proportionally more traffic:

```
Upstreams: A (weight=3), B (weight=1)
Total weight: 4

Counter 0 → A  (0 < 3)
Counter 1 → A  (1 < 3)
Counter 2 → A  (2 < 3)
Counter 3 → B  (3 < 4)
Counter 4 → A  (0 < 3, wraps)
...
```

Over 4 requests, A gets 3 (75%) and B gets 1 (25%) — exactly matching the weight ratio.

**When to use:** Mixed infrastructure — a beefy server with 16 cores should get more traffic than a 4-core server.

### Least Connections (Not Built Yet)

Send the request to the upstream with the fewest active connections:

```
A: 12 active connections
B: 3 active connections   ← next request goes here
C: 8 active connections
```

**Pros:** Automatically adapts to slow upstreams (slow server accumulates connections, gets fewer new ones).
**Cons:** Requires tracking active connection count per upstream.

**When to use:** When requests have variable processing time. A mix of fast (10ms) and slow (5s) requests would overwhelm one server with round-robin, but least-connections adapts naturally.

### Consistent Hashing (Not Built Yet)

Hash the request key (e.g., user ID) to always route the same user to the same upstream:

```
hash("user-123") mod 3 = 1 → always goes to upstream B
hash("user-456") mod 3 = 0 → always goes to upstream A
```

**Pros:** Session affinity without server-side session storage. Great for caches (same user hits same cache).
**Cons:** Uneven distribution if hash function isn't good. When an upstream is removed, many users get redistributed.

**When to use:** Stateful services, per-user caches, or when you need "sticky sessions."

### Random

Pick a random upstream. Surprisingly effective at scale:

```
random(0, 2) → could be A, B, or C
```

**Pros:** Zero state, zero coordination, works well in distributed systems.
**Cons:** Not perfectly even for small request counts.

**When to use:** Simple distributed systems where you want to avoid shared state entirely.

### Algorithm Comparison

| Algorithm | State Needed | Even Distribution | Adapts to Load | Session Affinity |
|-----------|-------------|-------------------|----------------|------------------|
| Round-Robin | Counter | Perfect | No | No |
| Weighted RR | Counter + weights | By weight ratio | No | Via weight tuning |
| Least Connections | Connection counts | Adaptive | Yes | No |
| Consistent Hash | Hash ring | Statistical | No | Yes |
| Random | None | Statistical | No | No |

---

## 3. How Our Round-Robin Works (Implementation Deep Dive)

### The Interface

```java
public interface LoadBalancer {
    Optional<Upstream> choose(Long routeId, List<Upstream> upstreams);
}
```

Why `routeId` as a parameter? Because we need **per-route counters**. If route A has 2 upstreams and route B has 3, a global counter would not cycle evenly within each route:

```
Global counter (WRONG):
  Route A (2 upstreams): counter 0→A1, 1→A2, 2→A1, 3→A2, 4→A1
  Route B (3 upstreams): counter 5→B3, 6→B1, 7→B2, 8→B3
  // Route B started at counter 5, distribution is messy

Per-route counter (CORRECT):
  Route A counter: 0→A1, 1→A2, 2→A1, 3→A2  (clean cycle)
  Route B counter: 0→B1, 1→B2, 2→B3, 3→B1  (clean cycle)
```

### The Core Algorithm

```java
@Override
public Optional<Upstream> choose(Long routeId, List<Upstream> upstreams) {
    if (upstreams == null || upstreams.isEmpty()) {
        return Optional.empty();
    }
    if (upstreams.size() == 1) {
        return Optional.of(upstreams.get(0));     // fast path
    }

    int totalWeight = upstreams.stream()
            .mapToInt(u -> Math.max(u.getWeight(), 1))
            .sum();

    AtomicInteger counter = counters.computeIfAbsent(routeId, k -> new AtomicInteger(0));
    int index = Math.floorMod(counter.getAndIncrement(), totalWeight);

    int cumulative = 0;
    for (Upstream upstream : upstreams) {
        cumulative += Math.max(upstream.getWeight(), 1);
        if (index < cumulative) {
            return Optional.of(upstream);
        }
    }

    return Optional.of(upstreams.get(0));          // fallback (unreachable)
}
```

Let's walk through this step by step.

### Step 1: Edge Cases

```java
if (upstreams == null || upstreams.isEmpty()) return Optional.empty();
if (upstreams.size() == 1) return Optional.of(upstreams.get(0));
```

- Empty list? Nothing to balance. Return empty.
- Single upstream? No balancing needed. Skip the atomic operations entirely.

### Step 2: Calculate Total Weight

```java
int totalWeight = upstreams.stream()
        .mapToInt(u -> Math.max(u.getWeight(), 1))
        .sum();
```

For upstreams A(weight=3), B(weight=1): totalWeight = 4.

`Math.max(weight, 1)` guards against zero or negative weights in the database. A weight of 0 is treated as 1.

### Step 3: Get the Counter and Increment

```java
AtomicInteger counter = counters.computeIfAbsent(routeId, k -> new AtomicInteger(0));
int index = Math.floorMod(counter.getAndIncrement(), totalWeight);
```

This is the critical concurrent section. Let's unpack it:

- `computeIfAbsent` — if no counter exists for this route, create one starting at 0. Thread-safe.
- `getAndIncrement()` — atomically returns the current value and increments by 1. Uses CAS (see section below).
- `Math.floorMod` — like `%` but always returns a non-negative result, even for negative dividends.

### Step 4: Map Index to Upstream (Weighted)

```java
int cumulative = 0;
for (Upstream upstream : upstreams) {
    cumulative += Math.max(upstream.getWeight(), 1);
    if (index < cumulative) {
        return Optional.of(upstream);
    }
}
```

Imagine a number line divided by weights:

```
Upstreams: A(weight=3), B(weight=1)
Total weight: 4

Number line: [0  1  2 | 3]
              A  A  A   B

index=0 → cumulative reaches 3 at A → 0 < 3 → return A
index=1 → cumulative reaches 3 at A → 1 < 3 → return A
index=2 → cumulative reaches 3 at A → 2 < 3 → return A
index=3 → cumulative reaches 3 at A → 3 < 3? No → cumulative reaches 4 at B → 3 < 4 → return B
```

Over 4 requests (indices 0-3), A is chosen 3 times and B is chosen 1 time. The ratio exactly matches the weights.

---

## 4. Java Concurrency: AtomicInteger and CAS

### The Problem

A load balancer must be **thread-safe**. An API gateway handles many concurrent requests:

```
Thread 1: resolve /api/orders → needs to pick upstream
Thread 2: resolve /api/orders → needs to pick upstream
Thread 3: resolve /api/orders → needs to pick upstream
(all at the same instant)
```

If they all read the counter as 0 and then all increment to 1, they'd all pick the same upstream. That defeats the purpose.

### Why Not `synchronized`?

```java
// This works but is slow:
private int counter = 0;

public synchronized Upstream choose(List<Upstream> upstreams) {
    int index = counter % upstreams.size();
    counter++;
    return upstreams.get(index);
}
```

`synchronized` locks the entire method. Only one thread can execute it at a time. Under high load (thousands of req/sec), threads queue up waiting for the lock. This is a bottleneck.

### AtomicInteger: Lock-Free Concurrency

```java
private final AtomicInteger counter = new AtomicInteger(0);

public Upstream choose(List<Upstream> upstreams) {
    int index = Math.floorMod(counter.getAndIncrement(), upstreams.size());
    return upstreams.get(index);
}
```

`AtomicInteger.getAndIncrement()` is **lock-free**. It uses a CPU instruction called **Compare-And-Swap (CAS)**:

```
CAS loop (pseudocode):
  1. Read current value: old = 5
  2. Compute new value: new = 6
  3. Atomically: if value is still 5, set it to 6
     - If yes → success, return 5 (the old value)
     - If no (another thread changed it) → go back to step 1

This repeats until it succeeds. In practice, it almost always succeeds on the first try.
```

### CAS vs Locking

```
Locking (synchronized):
  Thread 1: LOCK → read → increment → write → UNLOCK
  Thread 2: [waiting...] → LOCK → read → increment → write → UNLOCK
  Thread 3: [waiting...] [waiting...] → LOCK → ...

CAS (AtomicInteger):
  Thread 1: read(5) → CAS(5→6) → success!
  Thread 2: read(5) → CAS(5→6) → fail! → read(6) → CAS(6→7) → success!
  Thread 3: read(5) → CAS(5→6) → fail! → read(7) → CAS(7→8) → success!
```

With CAS, threads never block. They might retry, but retries are microseconds. Under typical loads, CAS is significantly faster than locking.

### ConcurrentHashMap

```java
private final ConcurrentHashMap<Long, AtomicInteger> counters = new ConcurrentHashMap<>();

AtomicInteger counter = counters.computeIfAbsent(routeId, k -> new AtomicInteger(0));
```

`ConcurrentHashMap` is the thread-safe version of `HashMap`. It uses lock striping (locks on individual hash buckets, not the entire map). `computeIfAbsent` atomically checks if a key exists and creates the value if it doesn't — no race conditions.

### Why `Math.floorMod` Instead of `%`?

Java's `%` operator can return negative values for negative dividends:

```java
-1 % 3  = -1   // Java's % keeps the sign of the dividend
Math.floorMod(-1, 3) = 2   // always non-negative
```

`AtomicInteger` wraps around from `Integer.MAX_VALUE` (2,147,483,647) to `Integer.MIN_VALUE` (-2,147,483,648). After ~2 billion requests, the counter goes negative. With `%`, you'd get a negative index and an `ArrayIndexOutOfBoundsException`. With `Math.floorMod`, it still returns a valid index.

```
counter = Integer.MAX_VALUE = 2,147,483,647
getAndIncrement() returns 2,147,483,647, counter wraps to -2,147,483,648

Math.floorMod(2_147_483_647, 3) = 2  ← valid index
Math.floorMod(-2_147_483_648, 3) = 1 ← also valid!
```

---

## 5. Caching vs Load Balancing — The Tension

### The Problem We Solved

In Phase 2, `DatabaseRouteResolver` cached the entire `ResolvedRoute` (including the upstream URL):

```
Phase 2 cache:
  key: "*::/api/orders"
  value: ResolvedRoute(upstreamUrl = "http://order-svc-1:8081", ...)
  TTL: 60 seconds

Result: Every request to /api/orders for 60 seconds goes to order-svc-1.
        Load balancing is defeated.
```

### The Solution: Split the Cache

We changed what gets cached:

```
Phase 3 cache:
  key: "*::/api/orders"
  value: Route(id=1, upstreams=[svc-1, svc-2, svc-3])    ← Route object, not final URL
  TTL: 60 seconds

On each request:
  1. Get Route from cache (fast, no DB query)
  2. Filter enabled upstreams
  3. Call loadBalancer.choose() ← happens every request, NOT cached
  4. Build ResolvedRoute with the chosen upstream
```

This preserves the performance benefit of caching (avoiding DB queries on every request) while allowing per-request load balancing:

```
Before (Phase 2):
  resolve() → cache miss → DB query → pick first upstream → cache result
  resolve() → cache hit → same upstream (for 60 seconds)

After (Phase 3):
  resolve() → cache miss → DB query → cache Route → LB picks upstream A
  resolve() → cache hit (Route) → LB picks upstream B
  resolve() → cache hit (Route) → LB picks upstream C
```

### The Code Change

```java
// Phase 2 — cached the final answer
private final Cache<String, Optional<ResolvedRoute>> cache;

public Optional<ResolvedRoute> resolve(String host, String path) {
    String cacheKey = (host != null ? host : "*") + "::" + path;
    return cache.get(cacheKey, key -> doResolve(host, path));  // entire resolution cached
}

// Phase 3 — cache the Route, pick upstream per-request
private final Cache<String, Optional<Route>> routeCache;

public Optional<ResolvedRoute> resolve(String host, String path) {
    String cacheKey = (host != null ? host : "*") + "::" + path;
    Optional<Route> matchedRoute = routeCache.get(cacheKey, key -> findMatchingRoute(host, path));

    return matchedRoute.flatMap(route -> {
        List<Upstream> enabledUpstreams = route.getUpstreams().stream()
                .filter(Upstream::isEnabled)
                .toList();
        return loadBalancer.choose(route.getId(), enabledUpstreams)  // per-request!
                .map(upstream -> new ResolvedRoute(..., upstream.getUrl(), ...));
    });
}
```

### A Subtlety: JPA Detached Entities in Cache

The cached `Route` entity becomes **detached** from the JPA session after the Hibernate transaction closes. This is fine because:

1. We only read from it (`getId()`, `getUpstreams()`, `getPathPrefix()`, etc.)
2. Upstreams are loaded eagerly (`FetchType.EAGER`), so they're already in memory
3. We never try to modify the cached entity or merge it back into a session

If the upstreams were lazy-loaded, accessing `route.getUpstreams()` on a cached (detached) entity would throw a `LazyInitializationException`. EAGER loading avoids this entirely.

---

## 6. How Real Systems Do Load Balancing

### NGINX

```nginx
upstream order_service {
    server 10.0.0.1:8081 weight=3;
    server 10.0.0.2:8081 weight=1;
    server 10.0.0.3:8081 backup;      # only used if others are down
}

server {
    location /api/orders/ {
        proxy_pass http://order_service;
    }
}
```

NGINX supports: round-robin (default), `least_conn`, `ip_hash` (sticky sessions), and `random`. The `weight` directive works like our implementation. NGINX also supports `backup` servers and `max_fails` + `fail_timeout` for health-based removal.

### HAProxy

```
backend order_service
    balance roundrobin
    server order-1 10.0.0.1:8081 weight 3 check
    server order-2 10.0.0.2:8081 weight 1 check
    server order-3 10.0.0.3:8081 weight 1 check backup
```

HAProxy has the richest set of algorithms: `roundrobin`, `leastconn`, `source` (consistent hash by client IP), `uri` (hash by URI), `hdr` (hash by header). It's the gold standard for load balancing.

### Kubernetes Services

```yaml
apiVersion: v1
kind: Service
metadata:
  name: order-service
spec:
  selector:
    app: order-service
  ports:
    - port: 8081
```

Kubernetes uses `kube-proxy` which load-balances at Layer 4 using `iptables` or `IPVS` rules. By default, it uses random selection (iptables probability rules) or round-robin (IPVS). For Layer 7 load balancing, you need an Ingress controller (which is essentially an NGINX or Envoy running in the cluster).

### Spring Cloud Gateway

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: order-route
          uri: lb://order-service      # "lb://" triggers LoadBalancer
          predicates:
            - Path=/api/orders/**
```

Spring Cloud Gateway uses Spring Cloud LoadBalancer (the replacement for Netflix Ribbon). The `lb://` prefix triggers client-side load balancing using a `ReactiveLoadBalancer` that supports round-robin and random.

### Comparison

| Feature | NGINX | HAProxy | K8s | EdgeFlow |
|---------|-------|---------|-----|----------|
| Algorithms | 4 | 8+ | 2 | 1 (weighted RR) |
| Health-aware | Yes | Yes | Yes | Not yet (Phase 4) |
| Sticky sessions | ip_hash | cookie | sessionAffinity | Not yet |
| Dynamic config | Reload (NGINX Plus: API) | Runtime API | Automatic | Admin API |
| Layer | 7 (or 4) | 4 and 7 | 4 | 7 |

---

## 7. What Changed in This Phase

### New Files

| File | Purpose |
|------|---------|
| `loadbalancer/LoadBalancer.java` | Interface: `choose(routeId, upstreams)` |
| `loadbalancer/RoundRobinLoadBalancer.java` | Weighted round-robin with per-route AtomicInteger counters |

### Modified Files

| File | Change |
|------|--------|
| `routing/DatabaseRouteResolver.java` | Split cache (cache Route, pick upstream per-request), inject `LoadBalancer`, remove `pickUpstream()` |
| `build.gradle` | Added test dependencies (spring-boot-starter-test, junit-platform-launcher) |

### Unchanged

`ProxyController.java`, `RouteResolver.java` (interface + record), `YamlRouteResolver.java`, all entity classes, all migrations. The load balancer slots in cleanly because `ResolvedRoute` still has a single `upstreamUrl` — the selection just happens differently now.

---

## 8. Testing Load Balancing

### Unit Tests Included

| Test | What It Verifies |
|------|-----------------|
| `emptyList_returnsEmpty` | Empty upstream list returns `Optional.empty()` |
| `nullList_returnsEmpty` | Null upstream list returns `Optional.empty()` |
| `singleUpstream_alwaysReturnsSame` | Single upstream always returned (fast path) |
| `equalWeights_distributesEvenly` | 2 upstreams, 100 requests → exactly 50/50 |
| `threeUpstreams_roundRobinOrder` | Verifies exact A→B→C→A cycle |
| `weightedDistribution_respectsWeights` | weight=3 gets 3x traffic of weight=1 |
| `perRouteIsolation_separateCounters` | Route 1 and Route 2 have independent counters |
| `zeroWeight_treatedAsOne` | weight=0 is treated as weight=1 |
| `resetCounter_startsFromBeginning` | `resetCounter()` restarts from index 0 |
| `threadSafety_noErrors` | 10 threads × 1000 requests, no errors, all upstreams get traffic |

### Manual Testing

```bash
# 1. Start the app
./gradlew bootRun

# 2. Add a second upstream to an existing route
curl -X POST http://localhost:8080/admin/api/v1/routes/2/upstreams \
  -H "Content-Type: application/json" \
  -d '{"url": "http://localhost:8080/mock/orders-v2", "weight": 1}'

# 3. Send multiple requests and observe the upstream rotating
for i in {1..6}; do
  curl -s http://localhost:8080/api/orders/1 | jq .
done
```

---

## 9. Key Concepts to Remember

| Concept | EdgeFlow Example |
|---------|-----------------|
| **Load balancing** | Distributing requests across multiple instances of a service |
| **Round-robin** | Cycle through upstreams in order: A→B→C→A→B→C |
| **Weighted round-robin** | Weight=3 gets 3x traffic vs weight=1 |
| **AtomicInteger** | Lock-free thread-safe counter using CAS instructions |
| **CAS (Compare-And-Swap)** | CPU instruction: "if value is still X, set it to Y" |
| **ConcurrentHashMap** | Thread-safe map with lock striping for per-route counters |
| **Math.floorMod** | Like `%` but always returns non-negative (handles counter overflow) |
| **Cache splitting** | Cache the route match, but pick upstream per-request (not cached) |
| **Detached JPA entity** | Cached Route works because upstreams are EAGER-loaded and we only read |
| **Per-route counters** | Each route has its own AtomicInteger so cycles are independent |

---

## 10. What's Next — Phase 4: Health Checks

Phase 3 distributes traffic evenly, but it doesn't know if an upstream is actually healthy. Phase 4 adds:

```
Every 10s: GET /health on each upstream
  200 OK     → healthy, keep in pool
  500/timeout → increment failure counter
  3 failures  → mark unhealthy, remove from pool
  3 successes → mark healthy, add back to pool
```

The load balancer currently gets **all enabled upstreams** from the route. After Phase 4, it will only get **healthy** upstreams — unhealthy ones are filtered out before load balancing.
