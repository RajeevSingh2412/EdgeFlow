# Phase 7: Kafka Events — Learning Guide

## What You'll Learn

- Event-driven architecture: why cache invalidation needs events
- The stale cache problem with multiple gateway instances
- Kafka fundamentals: topics, partitions, consumer groups, producers
- Broadcast semantics: each instance gets every event via unique group IDs
- `ConfigEvent` as an event envelope
- `KafkaConfigPublisher`: serialize to JSON, async send, error handling
- `KafkaConfigConsumer`: `@KafkaListener`, deserialize, invalidate the right cache
- `@ConditionalOnProperty`: making Kafka optional
- Graceful degradation: if Kafka is down, caches expire naturally via TTL
- How real systems propagate config: Consul watches, etcd watches, ZooKeeper
- Kafka vs RabbitMQ vs Redis Pub/Sub for config events

---

## 1. The Problem: Stale Caches Across Instances

Phases 5 and 6 added Caffeine caches with 30-second TTL for rate limit rules and feature flags. This works perfectly with a single gateway instance. With multiple instances, it breaks:

```
Setup: 3 gateway instances behind a load balancer

t=0   Admin hits Instance A: PUT /admin/api/v1/flags/1 {rolloutPct: 50}
      Instance A: updates DB, invalidates its own cache  ✅
      Instance B: still has old cache (rolloutPct: 25)   ❌
      Instance C: still has old cache (rolloutPct: 25)   ❌

t=15s Instance B serves user → uses stale flag (25%)    ❌
t=29s Instance C serves user → uses stale flag (25%)    ❌
t=30s Caffeine TTL expires on B and C → reload from DB  ✅ (finally)

For 30 seconds, 2 out of 3 instances served stale config.
```

This is a classic **distributed cache invalidation** problem. The caches on each instance are independent. When one instance modifies the database, the others have no way to know.

### Why Not Just Remove the Cache?

```
Without cache:
  Every request → DB query to load rules
  At 1000 req/sec × 3 instances = 3000 DB queries/sec
  Database becomes the bottleneck

With cache + events:
  DB queried once per 30 seconds per instance (cache miss)
  On config change → event → all instances invalidate → one DB query each
  Database handles ~3 queries per config change instead of 3000/sec
```

---

## 2. Event-Driven Architecture

Instead of each instance polling for changes, we use **events**: when something changes, the changer announces it, and all interested parties react.

```
Before (polling / TTL-based):
  Instance A ──┐
  Instance B ──┤── poll DB every 30s ── DB
  Instance C ──┘

After (event-driven):
  Admin change → Instance A → publish event ──→ Kafka
                                                  │
  Instance A ←── consume event ←──────────────────┤
  Instance B ←── consume event ←──────────────────┤
  Instance C ←── consume event ←──────────────────┘
  All instances invalidate cache immediately
```

### The Event Flow

```
1. Admin creates/updates/deletes a config entity
2. Admin controller saves to database
3. Admin controller calls invalidateCache() locally
4. Admin controller calls KafkaConfigPublisher.publish*(id, action)
5. Publisher serializes event to JSON, sends to Kafka topic
6. Kafka delivers event to all consumer instances
7. Each KafkaConfigConsumer receives the event
8. Consumer calls invalidateCache() on the relevant service
9. Next request triggers a fresh DB load into cache
```

---

## 3. Kafka Fundamentals

### What Is Kafka?

Apache Kafka is a distributed event streaming platform. Think of it as a highly durable, ordered, append-only log:

```
Topic: edgeflow.config.routes
  Partition 0: [event1] [event3] [event5] [event7]
  Partition 1: [event2] [event4] [event6]
  Partition 2: [event8] [event9]

Events are appended, never modified. Consumers read at their own pace.
```

### Key Concepts

```
┌─────────────────────────────────────────────────┐
│                    Kafka Cluster                 │
│                                                  │
│  Topic: edgeflow.config.routes                   │
│  ┌─────────────────────────────────────────────┐ │
│  │ Partition 0: [msg1] [msg3] [msg5]           │ │
│  │ Partition 1: [msg2] [msg4]                  │ │
│  │ Partition 2: [msg6] [msg7]                  │ │
│  └─────────────────────────────────────────────┘ │
│                                                  │
│  Producer ──→ writes to partition (by key hash)  │
│  Consumer ←── reads from partition               │
└─────────────────────────────────────────────────┘
```

| Concept | Explanation |
|---------|-------------|
| **Topic** | A named stream of events. Like a database table but append-only. |
| **Partition** | A topic is split into partitions for parallelism. Each partition is an ordered log. |
| **Producer** | Writes events to a topic. Chooses partition by message key hash. |
| **Consumer** | Reads events from a topic. Tracks its position (offset) in each partition. |
| **Consumer Group** | A group of consumers that divide partitions among themselves. Each partition is read by exactly one consumer in the group. |
| **Offset** | The position of a consumer in a partition. Like a bookmark. |

### Consumer Groups and Broadcast

This is critical for EdgeFlow. There are two patterns:

```
Pattern 1: Work Queue (one consumer per message)
  Consumer Group "workers": Instance A, Instance B, Instance C
  Message arrives → Kafka delivers to ONE of them
  Use case: Processing jobs where each job should run once

Pattern 2: Broadcast (every consumer gets every message)
  Consumer Group "edgeflow-abc123": Instance A
  Consumer Group "edgeflow-def456": Instance B
  Consumer Group "edgeflow-ghi789": Instance C
  Message arrives → Kafka delivers to ALL of them
  Use case: Cache invalidation (every instance needs to know)
```

EdgeFlow needs broadcast. Every instance must receive every config change event. This is achieved by giving each instance a **unique consumer group ID**:

```java
@KafkaListener(
    topics = "edgeflow.config.routes",
    groupId = "${edgeflow.kafka.group-id:edgeflow-${random.uuid}}"
)
//                                            ^^^^^^^^^^^^^^
//                                    Each instance gets a unique UUID
//                                    → each is its own consumer group
//                                    → each gets every message
```

`${random.uuid}` is a Spring property that generates a random UUID at startup. Each instance gets a different group ID, so Kafka treats each as an independent consumer.

---

## 4. Code Walkthrough — ConfigEvent

The event envelope that all config changes use:

```java
public record ConfigEvent(
        String eventId,            // unique event ID (UUID)
        String eventType,          // "ROUTE_CHANGED", "FLAG_CHANGED", "RATE_LIMIT_CHANGED"
        Instant timestamp,         // when the event was created
        String sourceInstanceId,   // which instance published it
        Long entityId,             // the ID of the changed entity
        String action,             // "CREATED", "UPDATED", "DELETED"
        String description         // human-readable description
) {
    // Factory methods for each event type
    public static ConfigEvent routeChanged(Long routeId, String action) {
        return of("ROUTE_CHANGED", routeId, action, "Route " + action.toLowerCase());
    }

    public static ConfigEvent flagChanged(Long flagId, String action) {
        return of("FLAG_CHANGED", flagId, action, "Feature flag " + action.toLowerCase());
    }

    public static ConfigEvent rateLimitChanged(Long ruleId, String action) {
        return of("RATE_LIMIT_CHANGED", ruleId, action, "Rate limit rule " + action.toLowerCase());
    }
}
```

### Why a Record?

Java records are ideal for event data:
- Immutable (events should never be modified after creation)
- Auto-generated `equals()`, `hashCode()`, `toString()`
- Concise syntax for what is essentially a data carrier

### The `sourceInstanceId` Field

```java
String sourceInstanceId = System.getenv().getOrDefault("HOSTNAME", "gateway-local");
```

This identifies which instance published the event. Useful for:
- Debugging: "Which instance triggered this change?"
- Potential optimization: an instance could skip events it published itself (not implemented, but possible)

In Docker/Kubernetes, `HOSTNAME` is the container ID. On a developer machine, it falls back to `"gateway-local"`.

### The `eventId` Field

```java
String eventId = UUID.randomUUID().toString();
```

Unique event ID enables:
- Idempotency: if an event is delivered twice, the consumer can detect the duplicate
- Tracing: follow an event through logs across services
- Audit: track every config change

---

## 5. Code Walkthrough — KafkaConfigPublisher

```java
@Component
public class KafkaConfigPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${edgeflow.kafka.enabled:false}")
    private boolean kafkaEnabled;

    public KafkaConfigPublisher(@Nullable KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }
```

### Why `@Nullable KafkaTemplate`?

When Kafka is disabled (`edgeflow.kafka.enabled=false`), the `KafkaAutoConfiguration` is excluded:

```yaml
spring:
  autoconfigure:
    exclude:
      - org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration
```

This means no `KafkaTemplate` bean exists. The `@Nullable` annotation tells Spring that it is okay if this dependency cannot be injected — inject `null` instead of throwing an error.

### The publish() Method

```java
private void publish(String topic, ConfigEvent event) {
    // Guard 1: Kafka disabled in config
    if (!kafkaEnabled || kafkaTemplate == null) {
        log.debug("Kafka disabled, skipping event: {}", event.eventType());
        return;
    }

    try {
        // Serialize event to JSON
        String json = objectMapper.writeValueAsString(event);

        // Send asynchronously, handle result via callback
        kafkaTemplate.send(topic, event.entityId().toString(), json)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish event to {}: {}", topic, ex.getMessage());
                    } else {
                        log.debug("Published {} to {}", event.eventType(), topic);
                    }
                });
    } catch (JsonProcessingException e) {
        log.error("Failed to serialize event: {}", e.getMessage());
    }
}
```

Key decisions:

**Why `entityId` as the Kafka message key?**

```java
kafkaTemplate.send(topic, event.entityId().toString(), json)
//                        ^^^^^^^^^^^^^^^^^^^^^^^^^^
//                        message key = entity ID
```

Kafka uses the message key to determine which partition receives the message. Events for the same entity (same route ID, same flag ID) always go to the same partition. This guarantees ordering per entity:

```
Route 1: UPDATE → DELETE    (always in this order, same partition)
Route 2: CREATE → UPDATE    (always in this order, same partition)
Route 1 and Route 2 events might interleave (different partitions, that is fine)
```

**Why async send with `whenComplete()`?**

The `send()` method returns a `CompletableFuture`. The admin API call returns immediately without waiting for Kafka acknowledgment. If the send fails, we log the error but do not fail the admin operation. The local cache was already invalidated; the worst case is that other instances have a 30-second stale cache (the TTL fallback).

**Why `JavaTimeModule`?**

The `ConfigEvent` record contains an `Instant` field. Jackson does not know how to serialize `java.time.Instant` by default — it would throw an error. `JavaTimeModule` adds serializers/deserializers for all `java.time` types.

---

## 6. Code Walkthrough — KafkaConfigConsumer

```java
@Component
@ConditionalOnProperty(name = "edgeflow.kafka.enabled", havingValue = "true")
public class KafkaConfigConsumer {

    private final RouteResolver routeResolver;
    private final FeatureFlagService featureFlagService;
    private final RateLimitService rateLimitService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "edgeflow.config.routes",
                   groupId = "${edgeflow.kafka.group-id:edgeflow-${random.uuid}}")
    public void onRouteChange(String message) {
        try {
            ConfigEvent event = objectMapper.readValue(message, ConfigEvent.class);
            log.info("Received route change event: {} ({})",
                     event.eventType(), event.action());
            routeResolver.invalidateCache();
        } catch (Exception e) {
            log.error("Failed to process route change event: {}", e.getMessage());
        }
    }

    @KafkaListener(topics = "edgeflow.config.flags",
                   groupId = "${edgeflow.kafka.group-id:edgeflow-${random.uuid}}")
    public void onFlagChange(String message) {
        try {
            ConfigEvent event = objectMapper.readValue(message, ConfigEvent.class);
            log.info("Received flag change event: {} ({})",
                     event.eventType(), event.action());
            featureFlagService.invalidateCache();
        } catch (Exception e) {
            log.error("Failed to process flag change event: {}", e.getMessage());
        }
    }

    @KafkaListener(topics = "edgeflow.config.rate-limits",
                   groupId = "${edgeflow.kafka.group-id:edgeflow-${random.uuid}}")
    public void onRateLimitChange(String message) {
        try {
            ConfigEvent event = objectMapper.readValue(message, ConfigEvent.class);
            log.info("Received rate limit change event: {} ({})",
                     event.eventType(), event.action());
            rateLimitService.invalidateCache();
        } catch (Exception e) {
            log.error("Failed to process rate limit change event: {}", e.getMessage());
        }
    }
}
```

### @ConditionalOnProperty

```java
@ConditionalOnProperty(name = "edgeflow.kafka.enabled", havingValue = "true")
```

This annotation means: only create this bean if `edgeflow.kafka.enabled=true` in the config. When Kafka is disabled, this class is not instantiated. No `@KafkaListener` is registered. No Kafka connections are attempted.

This is how EdgeFlow makes Kafka optional. You can run without Kafka (caches expire via TTL) and add Kafka later without code changes — just flip the config flag.

### Why `invalidateAll()` Instead of Targeted Invalidation?

The consumer calls `invalidateCache()` which does `cache.invalidateAll()`. We could be smarter:

```
Simple (what we do):
  Event: "route 5 updated" → invalidate ALL cached routes
  Next request for any route triggers a fresh DB load

Targeted (more complex):
  Event: "route 5 updated" → invalidate only the cache entry for route 5
  Other routes keep their cached values

Why simple is fine:
  Config changes are rare (maybe 10 per day)
  A cache invalidation causes one DB query on the next request
  The extra complexity of targeted invalidation is not worth it
```

### Error Handling

```java
try {
    ConfigEvent event = objectMapper.readValue(message, ConfigEvent.class);
    ...
} catch (Exception e) {
    log.error("Failed to process route change event: {}", e.getMessage());
}
```

The catch-all ensures a malformed event does not crash the consumer. Kafka consumers that throw unhandled exceptions can get stuck in retry loops or stop consuming altogether. By catching and logging, we skip the bad event and continue processing the next one.

---

## 7. KafkaConfig — Topic Creation

```java
@Configuration
@ConditionalOnProperty(name = "edgeflow.kafka.enabled", havingValue = "true")
public class KafkaConfig {

    @Bean
    public NewTopic routesTopic() {
        return TopicBuilder.name("edgeflow.config.routes")
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic flagsTopic() {
        return TopicBuilder.name("edgeflow.config.flags")
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic rateLimitsTopic() {
        return TopicBuilder.name("edgeflow.config.rate-limits")
                .partitions(3)
                .replicas(1)
                .build();
    }
}
```

### Why 3 Partitions?

Three partitions allow three consumers to read in parallel. For config events this is overkill (low volume), but it demonstrates the concept. In production, partition count depends on throughput needs.

### Why 1 Replica?

Replicas provide durability. `replicas(1)` means no redundancy — if the Kafka broker dies, the data is lost. For config events this is acceptable because:
- The source of truth is the database, not Kafka
- Lost events just mean a 30-second cache delay (TTL fallback)
- In production, you would set `replicas(3)` for durability

### Topic Naming Convention

```
edgeflow.config.routes
edgeflow.config.flags
edgeflow.config.rate-limits
^^^^^^^  ^^^^^^  ^^^^^^^^^^
 app     domain   entity
```

Dotted naming is a Kafka convention. It makes topics discoverable and organized.

---

## 8. Graceful Degradation

What happens when Kafka is down?

```
Kafka healthy:
  Config change → event published → all instances invalidate → ~100ms propagation

Kafka down:
  Config change → publish fails (logged as error) → local cache invalidated
  Other instances: no event received
  Fallback: Caffeine TTL expires after 30 seconds → fresh DB load
  Worst case: 30 seconds of stale config on other instances

Kafka comes back:
  Events resume normally. No manual intervention needed.
  Consumer reconnects automatically (Kafka client handles this).
```

This is a key design principle: **Kafka enhances but is not required.** The system works without Kafka (with degraded freshness). This is much better than a system that crashes or stalls when Kafka is unavailable.

```
                    ┌──────────────────────────┐
                    │   Config Change           │
                    └────────┬─────────────────┘
                             │
                    ┌────────▼─────────────────┐
                    │  Save to Database         │
                    │  Invalidate local cache   │
                    └────────┬─────────────────┘
                             │
                    ┌────────▼─────────────────┐
              ┌─────│  Publish to Kafka         │
              │     └────────┬─────────────────┘
              │              │
        Kafka down      Kafka up
              │              │
              ▼              ▼
    Log error,          Event delivered to
    move on.            all instances.
    TTL fallback        Caches invalidated
    (30 seconds).       immediately.
```

---

## 9. application.yml — Kafka Configuration

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092          # Kafka broker address
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
    consumer:
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      auto-offset-reset: latest               # only read NEW events, not historical
  autoconfigure:
    exclude:
      - org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration

edgeflow:
  kafka:
    enabled: false                            # flip to true to enable Kafka
```

### Why `auto-offset-reset: latest`?

When a new consumer starts and has no saved offset, it can either:
- `earliest`: Read all events from the beginning of the topic (replay history)
- `latest`: Only read events published after the consumer started

For cache invalidation, `latest` is correct. Historical events are irrelevant — the cache starts empty on startup and loads from the database on the first request. Replaying old events would just trigger unnecessary cache invalidations.

### Why Exclude KafkaAutoConfiguration?

```yaml
spring:
  autoconfigure:
    exclude:
      - org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration
```

When `edgeflow.kafka.enabled=false`, we do not want Spring to try connecting to a Kafka broker. Without this exclusion, Spring Boot would auto-configure Kafka beans and attempt to connect on startup, failing if no broker is available. The exclusion ensures Kafka is completely opt-in.

When `edgeflow.kafka.enabled=true`, the `KafkaConfig` class explicitly creates the necessary beans, and Spring Kafka's listener infrastructure is activated by the `@KafkaListener` annotations.

---

## 10. How Real Systems Propagate Config

### Consul Watches

HashiCorp Consul stores config as key-value pairs. Services use **watches** to get notified of changes:

```
Consul KV:
  /config/routes/1 → {"pathPrefix": "/api/orders", ...}

Watch (long-polling):
  GET /v1/kv/config/routes?index=42&wait=5m
  → Blocks until value changes or 5 minutes elapse
  → Returns new value + new index
  → Client re-issues watch with new index
```

### etcd Watches

Kubernetes uses etcd as its configuration store. Watches are built-in:

```
etcdctl watch /config/routes --prefix
# Streams changes in real-time as they happen
```

### ZooKeeper Watches

Older systems (like pre-Kafka Kafka itself) use ZooKeeper:

```
ZooKeeper:
  Create a watch on /config/routes
  When any child node changes → callback fires
  Caveat: watches are one-shot (must re-register after each notification)
```

### Kafka vs RabbitMQ vs Redis Pub/Sub

| Feature | Kafka | RabbitMQ | Redis Pub/Sub |
|---------|-------|----------|---------------|
| **Durability** | Events persisted to disk | Optional persistence | No persistence |
| **Replay** | Can replay from any offset | No replay after consumption | No replay |
| **Ordering** | Per-partition ordering | Per-queue ordering | No ordering guarantee |
| **Scalability** | Millions of msg/sec | Thousands of msg/sec | Thousands of msg/sec |
| **Complexity** | High (cluster, ZooKeeper/KRaft) | Medium | Low |
| **Best for config events** | Overkill but works well | Good fit | Simplest option |

For config events (low volume, broadcast), Redis Pub/Sub would be the simplest choice. Kafka is chosen here because:
1. It demonstrates a production-grade event system
2. It provides durability (events survive broker restarts)
3. It scales to other use cases (request logging, analytics events)
4. It is ubiquitous in microservice architectures

---

## 11. What Changed in This Phase

### New Files

| File | Purpose |
|------|---------|
| `event/ConfigEvent.java` | Record: event envelope with factory methods per entity type |
| `event/KafkaConfigPublisher.java` | Serializes events to JSON, sends to Kafka topics asynchronously |
| `event/KafkaConfigConsumer.java` | Listens on 3 topics, deserializes events, invalidates caches |
| `config/KafkaConfig.java` | Creates Kafka topics (3 partitions, 1 replica each) |

### Modified Files

| File | Change |
|------|--------|
| `admin/RouteAdminController.java` | Injects `KafkaConfigPublisher`, publishes events on CRUD |
| `admin/RateLimitAdminController.java` | Injects `KafkaConfigPublisher`, publishes events on CRUD |
| `admin/FeatureFlagAdminController.java` | Injects `KafkaConfigPublisher`, publishes events on CRUD |
| `build.gradle` | Added `spring-kafka` dependency |
| `application.yml` | Added `spring.kafka.*` config, `edgeflow.kafka.enabled`, Kafka auto-config exclusion |

### Unchanged

`TokenBucketRateLimiter`, `FeatureFlagEvaluator`, `DatabaseRouteResolver`, `ProxyController` — none of these know about Kafka. Events flow through the admin controllers and services, not the hot proxy path.

---

## 12. Key Concepts to Remember

| Concept | EdgeFlow Example |
|---------|-----------------|
| **Distributed cache invalidation** | The problem: multiple instances have independent caches that go stale |
| **Event-driven architecture** | Publish events on change, consumers react asynchronously |
| **Kafka topic** | `edgeflow.config.routes` — a named stream of config change events |
| **Broadcast via unique group IDs** | `edgeflow-${random.uuid}` — each instance is its own consumer group |
| **Message key** | Entity ID as key ensures per-entity ordering within a partition |
| **@ConditionalOnProperty** | Consumer bean only created when `edgeflow.kafka.enabled=true` |
| **@Nullable injection** | `KafkaTemplate` can be null when Kafka is disabled |
| **Graceful degradation** | Kafka down? Local cache invalidated, others fall back to TTL (30s) |
| **auto-offset-reset: latest** | New consumers skip historical events, only process new ones |
| **JavaTimeModule** | Required for Jackson to serialize `java.time.Instant` |
| **Async send** | `whenComplete()` callback handles success/failure without blocking |

---

## 13. What's Next — Phase 8: Observability

Phases 5-7 added significant functionality, but we have no visibility into how the system is performing. Phase 8 adds metrics:

```
Questions we can now answer:
  How many requests per second is EdgeFlow handling?
  What percentage are being rate-limited (429)?
  What is the p99 latency?
  How many upstream errors are occurring?
  Which feature flags are being evaluated most?

Implementation:
  Micrometer (metrics facade) + Prometheus (scraping) + Actuator (endpoints)
  Counter: edgeflow_requests_total
  Timer:   edgeflow_request_duration_seconds
  Counter: edgeflow_rate_limit_rejected_total
```

Observability is the foundation of operating a production system. You cannot improve what you cannot measure.
