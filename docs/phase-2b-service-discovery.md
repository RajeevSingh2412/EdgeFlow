# Phase 2B: Self-Registration Service Discovery — Learning Guide

## What You'll Learn

- What service discovery is and why microservices need it
- How Eureka, Consul, and Kubernetes solve it
- The heartbeat pattern (register, heartbeat, expire)
- How `@Scheduled` background tasks work in Spring
- How self-registration integrates with EdgeFlow's existing Route/Upstream model

---

## 1. What Is Service Discovery?

In a microservices architecture, services come and go constantly:
- New instances spin up during high traffic (auto-scaling)
- Instances crash and get replaced
- Deployments roll out new versions, old instances die

The question: **how does the API gateway know which instances are alive right now?**

### Without Service Discovery

Someone must manually update the gateway every time an instance starts or stops:

```
DevOps engineer:
  "order-service-3 just started on 10.0.0.7:9002"
  → manually calls admin API to add upstream

  "order-service-1 crashed"
  → manually calls admin API to remove upstream
```

This doesn't scale. With dozens of services and hundreds of instances, manual management is impossible.

### With Service Discovery

Services announce themselves when they start, and the gateway automatically removes them when they disappear:

```
order-service-3 starts:
  → calls EdgeFlow: "I'm alive at 10.0.0.7:9002"
  → EdgeFlow adds it to the routing table

order-service-3 sends heartbeat every 10s:
  → "still alive"

order-service-3 crashes:
  → no more heartbeats
  → after 30s, EdgeFlow removes it from routing
```

No human intervention needed.

---

## 2. How Real Systems Do It

### Eureka (Netflix / Spring Cloud)

```
Service starts → registers with Eureka Server
  → sends heartbeat every 30s
  → Eureka marks it DOWN after 90s without heartbeat

Other services → query Eureka: "where is order-service?"
  → Eureka returns list of healthy instances
```

Eureka is a dedicated registry server. Services must include the Eureka client library. It's the most common approach in Spring Cloud microservices.

### Consul (HashiCorp)

```
Service starts → registers with local Consul agent
  → Consul agent performs health checks (HTTP, TCP, script)
  → Consul syncs state across all agents (gossip protocol)

Other services → DNS query or HTTP API
  → order-service.service.consul resolves to healthy IPs
```

Consul is more sophisticated — it uses a gossip protocol so there's no single point of failure. It also doubles as a key-value store.

### Kubernetes

```
Pod starts → Kubernetes knows about it automatically
  → kube-proxy updates iptables/IPVS rules
  → Service DNS resolves to all healthy pod IPs

Other pods → call order-service.namespace.svc.cluster.local
  → Kubernetes load-balances across pods
```

Kubernetes doesn't need a separate registry — it IS the registry. The container orchestrator tracks all running pods.

### EdgeFlow's Approach

We built the simplest version that teaches the core concept:

```
Service starts → POST /admin/api/v1/registry/register
  → EdgeFlow creates an upstream, links it to a route
  → service sends heartbeat every 10s

Service dies → no heartbeat for 30s
  → @Scheduled cleanup disables the upstream
```

No external infrastructure needed. The gateway IS the registry.

| Feature | Eureka | Consul | K8s | EdgeFlow |
|---------|--------|--------|-----|----------|
| Registry | Separate server | Agent per node | Built-in | Built into gateway |
| Health check | Client heartbeat | Agent-side checks | Kubelet probes | Client heartbeat |
| Discovery | Client library | DNS + API | DNS + API | Routing table |
| Complexity | Medium | High | High | Low |

---

## 3. The Heartbeat Pattern

The heartbeat pattern is the simplest form of failure detection:

```
Timeline:
  t=0s    Instance registers          status: UP
  t=10s   Heartbeat received          status: UP ✓
  t=20s   Heartbeat received          status: UP ✓
  t=30s   Heartbeat received          status: UP ✓
  t=35s   Instance crashes
  t=40s   No heartbeat                status: UP (still within grace period)
  t=50s   No heartbeat                status: UP (still within grace period)
  t=65s   30s since last heartbeat    status: DOWN ✗ (upstream disabled)
```

### Why Heartbeats Instead of Pinging?

Two approaches to detecting failures:

**Push (heartbeat):** Service tells the gateway "I'm alive"
- Simple: service makes an HTTP call on a timer
- No firewall issues: outbound calls from service to gateway
- Service controls when to register/deregister

**Pull (health check):** Gateway pings the service to check if it's alive
- Gateway must know where to ping (chicken-and-egg problem)
- Must handle firewalls, network segmentation
- Gateway controls the check schedule

EdgeFlow uses push (heartbeats) for service discovery and will use pull (health checks) in Phase 4 as a secondary check. Real systems often combine both.

### Tuning the Parameters

```yaml
edgeflow:
  registry:
    heartbeat-interval-ms: 10000    # cleanup runs every 10s
    expiry-ms: 30000                # 30s without heartbeat = expired
```

Trade-offs:
- **Short expiry (10s):** Fast detection of failures, but network hiccups cause false positives
- **Long expiry (60s):** Tolerant of temporary issues, but dead instances receive traffic longer
- **Rule of thumb:** Expiry should be 2-3x the heartbeat interval

---

## 4. How It Integrates with Routes and Upstreams

The key insight: service discovery doesn't replace routes and upstreams — it automates upstream management.

```
Before (manual):
  Admin creates Route → Admin adds Upstreams manually

After (self-registration):
  Admin creates Route → Services register themselves → Upstreams created automatically
  OR
  Service registers with a routePathPrefix → Route auto-created → Upstream auto-created
```

### The Data Model

```
Route (path_prefix: "/api/orders")
  ├── Upstream 1 (url: "http://10.0.0.5:9002") ← linked to ServiceInstance "order-svc-1"
  ├── Upstream 2 (url: "http://10.0.0.6:9002") ← linked to ServiceInstance "order-svc-2"
  └── Upstream 3 (url: "http://10.0.0.7:9002") ← linked to ServiceInstance "order-svc-3"

ServiceInstance "order-svc-1"
  ├── serviceName: "order-service"
  ├── url: "http://10.0.0.5:9002"
  ├── status: UP
  ├── upstream_id: → Upstream 1
  └── lastHeartbeatAt: 2 seconds ago

ServiceInstance "order-svc-2"
  ├── status: DOWN (no heartbeat for 45s)
  └── upstream_id: → Upstream 2 (disabled)
```

The proxy code (`ProxyController`, `DatabaseRouteResolver`) doesn't know about `ServiceInstance` at all. It just sees routes with upstreams. Disabled upstreams are filtered out by `DatabaseRouteResolver.pickUpstream()`.

---

## 5. @Scheduled — Background Tasks in Spring

### How It Works

```java
@EnableScheduling   // on the application class — enables the scheduler

@Scheduled(fixedDelayString = "${edgeflow.registry.heartbeat-interval-ms:10000}")
@Transactional
public void cleanupStaleInstances() {
    // runs every 10 seconds
}
```

| Parameter | Meaning |
|-----------|---------|
| `@EnableScheduling` | Activates Spring's task scheduler |
| `@Scheduled` | Marks a method to run on a schedule |
| `fixedDelay` | Wait N ms after the previous execution finishes, then run again |
| `fixedRate` | Run every N ms regardless of how long the previous execution took |
| `fixedDelayString` | Same as fixedDelay but reads from config (supports `${}` placeholders) |

**fixedDelay vs fixedRate:**

```
fixedDelay = 10000 (10s):
  Run 1 takes 2s → wait 10s → Run 2 takes 3s → wait 10s → ...
  Total cycle: 12s, 13s, ...

fixedRate = 10000 (10s):
  Run 1 at t=0 → Run 2 at t=10s → Run 3 at t=20s → ...
  If a run takes longer than 10s, the next one starts immediately
```

We use `fixedDelay` to avoid overlap — if cleanup takes a while, we don't want another cleanup starting on top of it.

### The Cleanup Logic

```java
public void cleanupStaleInstances() {
    LocalDateTime threshold = LocalDateTime.now().minusNanos(expiryMs * 1_000_000);

    List<ServiceInstance> staleInstances =
            instanceRepository.findAllByStatusAndLastHeartbeatAtBefore("UP", threshold);

    for (ServiceInstance instance : staleInstances) {
        instance.setStatus("DOWN");
        instance.getUpstream().setEnabled(false);   // stop routing traffic
        instanceRepository.save(instance);
    }

    routeResolver.invalidateCache();   // force route cache refresh
}
```

1. Calculate the threshold: current time minus expiry duration
2. Find all instances that are still marked "UP" but haven't sent a heartbeat since before the threshold
3. Mark them DOWN and disable their upstream
4. Invalidate the route cache so the proxy picks up the change

---

## 6. Code Walkthrough — ServiceRegistry

### The Register Flow

```java
@Transactional
public ServiceInstance register(RegisterRequest request) {
    // 1. Check for re-registration
    Optional<ServiceInstance> existing = instanceRepository.findByInstanceId(request.getInstanceId());
    if (existing.isPresent()) {
        return reRegister(existing.get(), request);
    }

    // 2. Find or create a route
    Route route = findOrCreateRoute(request);

    // 3. Create and save an upstream
    Upstream upstream = new Upstream();
    upstream.setUrl(request.getUrl());
    upstream.setRoute(route);
    upstream = upstreamRepository.saveAndFlush(upstream);

    // 4. Create the service instance, linking to route and upstream
    ServiceInstance instance = new ServiceInstance();
    instance.setInstanceId(request.getInstanceId());
    instance.setRoute(route);
    instance.setUpstream(upstream);
    // ...

    return instanceRepository.save(instance);
}
```

**Why `saveAndFlush` for the upstream?**

The `ServiceInstance` entity has a `@OneToOne` reference to `Upstream`. JPA needs the upstream to have a database ID before it can save the service instance with the foreign key. `saveAndFlush()` forces an immediate INSERT and returns the entity with its generated ID.

Without flush:
```
save(upstream) → INSERT queued (no ID yet)
save(instance) → tries to set upstream_id = null → ERROR
```

With flush:
```
saveAndFlush(upstream) → INSERT executed → upstream.id = 4
save(instance) → sets upstream_id = 4 → OK
```

### Re-Registration

```java
private ServiceInstance reRegister(ServiceInstance instance, RegisterRequest request) {
    instance.setUrl(request.getUrl());
    instance.setStatus("UP");
    instance.setLastHeartbeatAt(LocalDateTime.now());

    Upstream upstream = instance.getUpstream();
    if (upstream != null) {
        upstream.setUrl(request.getUrl());
        upstream.setEnabled(true);   // re-enable if it was disabled
    }

    return instanceRepository.save(instance);
}
```

If a service crashes and comes back, it sends the same `instanceId`. Instead of creating a duplicate, we update the existing record and re-enable its upstream. This is idempotent — calling register twice with the same instanceId is safe.

### findOrCreateRoute

```java
private Route findOrCreateRoute(RegisterRequest request) {
    String pathPrefix = request.getRoutePathPrefix();

    List<Route> routes = routeRepository.findAllByEnabledTrue();
    Optional<Route> existing = routes.stream()
            .filter(r -> r.getPathPrefix().equals(pathPrefix))
            .findFirst();

    if (existing.isPresent()) {
        return existing.get();
    }

    // Auto-create a route
    Route route = new Route();
    route.setPathPrefix(pathPrefix);
    route.setDescription("Auto-created for service: " + request.getServiceName());
    return routeRepository.save(route);
}
```

If a route with the matching path prefix already exists, the upstream is added to it. If not, a new route is auto-created. This means a service can register even before an admin configures a route for it.

---

## 7. The Registry API

### Register

```bash
POST /admin/api/v1/registry/register
{
  "serviceName": "order-service",
  "instanceId": "order-svc-10.0.0.5",
  "url": "http://10.0.0.5:9002",
  "routePathPrefix": "/api/orders",
  "healthCheckPath": "/health"
}

Response: 201 Created
{
  "id": 1,
  "serviceName": "order-service",
  "instanceId": "order-svc-10.0.0.5",
  "status": "UP",
  "routeId": 2,
  "routePathPrefix": "/api/orders",
  "upstreamId": 5,
  "lastHeartbeatAt": "2026-08-28T10:00:00"
}
```

### Heartbeat

```bash
POST /admin/api/v1/registry/heartbeat
{
  "instanceId": "order-svc-10.0.0.5"
}

Response: 200 OK
{"status": "ok"}
```

### Deregister (graceful shutdown)

```bash
POST /admin/api/v1/registry/deregister
{
  "instanceId": "order-svc-10.0.0.5"
}

Response: 200 OK
{"status": "deregistered"}
```

### List Services

```bash
GET /admin/api/v1/registry/services

Response: 200 OK
[
  {"instanceId": "order-svc-1", "status": "UP", ...},
  {"instanceId": "order-svc-2", "status": "DOWN", ...}
]
```

---

## 8. What a Backend Service Would Do

In a real setup, the backend service would include startup/shutdown hooks:

```java
// In the backend service (not EdgeFlow)
@Component
public class ServiceRegistration {

    @Value("${spring.application.name}")
    private String serviceName;

    @Value("${server.port}")
    private int port;

    private final RestClient restClient = RestClient.create();
    private String instanceId;

    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        instanceId = serviceName + "-" + InetAddress.getLocalHost().getHostAddress() + ":" + port;

        restClient.post()
            .uri("http://edgeflow:8080/admin/api/v1/registry/register")
            .body(Map.of(
                "serviceName", serviceName,
                "instanceId", instanceId,
                "url", "http://" + InetAddress.getLocalHost().getHostAddress() + ":" + port,
                "routePathPrefix", "/api/" + serviceName
            ))
            .retrieve()
            .toBodilessEntity();
    }

    @Scheduled(fixedDelay = 10000)
    public void heartbeat() {
        restClient.post()
            .uri("http://edgeflow:8080/admin/api/v1/registry/heartbeat")
            .body(Map.of("instanceId", instanceId))
            .retrieve()
            .toBodilessEntity();
    }

    @PreDestroy
    public void deregister() {
        restClient.post()
            .uri("http://edgeflow:8080/admin/api/v1/registry/deregister")
            .body(Map.of("instanceId", instanceId))
            .retrieve()
            .toBodilessEntity();
    }
}
```

- `@EventListener(ApplicationReadyEvent.class)` — runs after Spring Boot fully starts
- `@Scheduled(fixedDelay = 10000)` — sends heartbeat every 10 seconds
- `@PreDestroy` — runs during graceful shutdown, deregisters before dying

---

## 9. What's Missing vs Production Systems

| Feature | Production Systems | EdgeFlow |
|---------|--------------------|----------|
| **High availability** | Registry is clustered (Eureka has peer-to-peer replication) | Single instance |
| **Gossip protocol** | Consul uses Serf gossip for failure detection across nodes | Simple heartbeat timeout |
| **Zone awareness** | Route to instances in the same datacenter/AZ first | No zone concept |
| **Metadata** | Rich service metadata (version, environment, tags) | Basic string field |
| **Client-side caching** | Services cache the registry locally, reducing load | No client library |
| **Self-preservation** | Eureka stops expiring instances if too many fail at once (network partition protection) | No protection |
| **Weighted registration** | Register with weight based on instance capacity | Weight always 1 |

The most important missing piece is **self-preservation**: if the network between EdgeFlow and all services goes down, EdgeFlow would mark everything as DOWN and stop routing entirely. Eureka handles this by entering "self-preservation mode" — if more than 85% of instances miss heartbeats simultaneously, it assumes a network issue and stops expiring instances.

---

## 10. Key Concepts to Remember

| Concept | EdgeFlow Example |
|---------|-----------------|
| **Service discovery** | Services tell the gateway where they are, instead of manual config |
| **Heartbeat pattern** | Register → send heartbeat every 10s → expire after 30s silence |
| **Self-registration** | Service calls `POST /register` on startup |
| **Graceful deregister** | Service calls `POST /deregister` on shutdown |
| **Stale cleanup** | `@Scheduled` task finds and disables expired instances |
| **Idempotent register** | Same instanceId → update existing record, don't create duplicate |
| **Route auto-creation** | If no route exists for the path prefix, create one automatically |
| **Upstream linkage** | ServiceInstance → Upstream → Route (proxy code unchanged) |
| **@Scheduled** | Spring runs a method on a fixed interval in a background thread |
| **saveAndFlush** | Force immediate DB write so the generated ID is available |