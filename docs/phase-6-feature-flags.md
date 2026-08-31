# Phase 6: Feature Flags — Learning Guide

## What You'll Learn

- What feature flags are and why modern systems use them
- Progressive rollout: 0% to 10% to 50% to 100%
- Instant rollback without deployments
- Deterministic hashing: why the same user always gets the same result
- How `hashCode()` works in Java and modular arithmetic for bucketing
- `FlagContext`, `FeatureFlagEvaluator`, and `FeatureFlagService` walkthrough
- The Admin API: CRUD plus the evaluate endpoint
- How real systems work: LaunchDarkly, Unleash, Flagsmith
- Strategy types: PERCENTAGE (built), future: USER_LIST, GROUP

---

## 1. What Are Feature Flags?

A feature flag (also called a feature toggle or feature switch) lets you change application behavior without deploying new code:

```
Traditional deployment:
  Code change → Build → Test → Deploy → 100% of users see it
  Problem found → Code fix → Build → Test → Deploy again
  Time to rollback: 20-60 minutes

Feature flag:
  Code deployed with flag check → flag OFF → nobody sees it
  Turn flag ON for 10% → test with real traffic
  Problem found → turn flag OFF → instant rollback (seconds)
  No deployment needed.
```

### Real-World Use Cases

| Use Case | Example |
|----------|---------|
| **Gradual rollout** | New checkout flow: 5% → 25% → 50% → 100% |
| **Kill switch** | Disable a feature instantly if it causes errors |
| **A/B testing** | Show variant A to 50%, variant B to 50%, measure conversion |
| **Beta program** | Enable features only for beta users |
| **Ops toggle** | Disable expensive features during peak load |
| **Trunk-based dev** | Merge incomplete features behind a flag, deploy continuously |

### Where EdgeFlow Uses Flags

At the gateway level, feature flags can control:
- Which upstream version receives traffic (canary deployments)
- Whether a new API endpoint is exposed
- Setting `X-Feature-*` headers so downstream services know which variant to serve

---

## 2. Progressive Rollout

Instead of deploying to 100% of users at once, you increase the percentage gradually:

```
Day 1:  rollout_pct = 0%    Flag created, nobody sees the feature
Day 2:  rollout_pct = 5%    Internal team + 5% of real users
Day 3:  rollout_pct = 25%   Quarter of users, monitoring metrics
Day 5:  rollout_pct = 50%   Half of users, error rates look good
Day 7:  rollout_pct = 100%  Full rollout, feature is GA

At any point:
  Problem detected → set rollout_pct = 0% → instant rollback
```

The key requirement: **consistency**. When rollout is at 25%, user Alice should either always be IN or always be OUT. She should not flip-flop between seeing and not seeing the feature on every request.

This is where deterministic hashing comes in.

---

## 3. Deterministic Hashing

### The Problem with Randomness

A naive approach to 25% rollout:

```java
// WRONG: Random rollout
boolean enabled = Math.random() < 0.25;
```

This means every request has a 25% chance. The same user might see the feature on one page load and not on the next. That is a terrible user experience:

```
User Alice loads dashboard → new checkout button appears
User Alice refreshes → button disappears
User Alice refreshes → button appears again
User Alice: "Is this site broken?"
```

### The Solution: Hash the User

Instead of random, we hash the user's identity. A hash function always produces the same output for the same input:

```java
String hashInput = context.userId() + ":" + flag.getFlagKey();
int bucket = Math.abs(hashInput.hashCode() % 100);
return bucket < flag.getRolloutPct();
```

Step by step:

```
hashInput = "alice:new-checkout-flow"
hashCode  = "alice:new-checkout-flow".hashCode()  → some integer, e.g., -1284502934
bucket    = Math.abs(-1284502934 % 100)            → 34
rolloutPct = 25

34 < 25?  → false → Alice does NOT get the feature

Every time Alice hits this flag:
  Same input "alice:new-checkout-flow"
  Same hash → same bucket (34)
  Same result: false
  Consistent experience.
```

### Why Include the Flag Key?

```java
String hashInput = context.userId() + ":" + flag.getFlagKey();
//                                          ^^^^^^^^^^^^^^^^
```

Without the flag key, a user's bucket would be the same for ALL flags:

```
Without flag key:
  hash("alice") % 100 = 34
  Flag A (rollout 25%): alice is OUT (34 >= 25)
  Flag B (rollout 25%): alice is OUT (34 >= 25)  ← always same result!

  Users 0-24 get ALL features. Users 25-99 get NONE.
  No independent rollout per feature.

With flag key:
  hash("alice:new-checkout") % 100 = 34     → OUT for this flag
  hash("alice:dark-mode")    % 100 = 72     → OUT for this flag
  hash("alice:search-v2")    % 100 = 11     → IN for this flag

  Each flag distributes users independently.
```

---

## 4. How hashCode() Works in Java

### String.hashCode()

Java's `String.hashCode()` is a polynomial hash:

```
s[0]*31^(n-1) + s[1]*31^(n-2) + ... + s[n-1]

"abc".hashCode():
  'a' * 31^2 + 'b' * 31^1 + 'c' * 31^0
  = 97 * 961 + 98 * 31 + 99
  = 93217 + 3038 + 99
  = 96354
```

Properties that matter for us:
- **Deterministic:** Same string always produces the same hash (within the same JVM version)
- **Well-distributed:** Different strings produce different hashes (mostly)
- **Fast:** O(n) where n is string length

### Modular Arithmetic: % 100

`hashCode() % 100` maps any integer to a bucket 0-99:

```
hashCode = 96354  → 96354 % 100 = 54  → bucket 54
hashCode = -12345 → -12345 % 100 = -45 → Math.abs(-45) = 45 → bucket 45
```

`Math.abs()` handles negative hash codes. Java's `%` can return negative values for negative dividends.

### Distribution Across 100 Buckets

With many users, the buckets fill roughly evenly:

```
1000 users, rollout 25%:
  ~250 users hash to buckets 0-24  → feature ON
  ~750 users hash to buckets 25-99 → feature OFF

As you increase rollout from 25% to 50%:
  Users in buckets 0-24 still have the feature (no change)
  Users in buckets 25-49 NOW get the feature (added)
  Users in buckets 50-99 still don't (no change)

Key property: increasing rollout never removes users who already had it.
```

### A Subtlety: Math.abs(Integer.MIN_VALUE)

```java
Math.abs(Integer.MIN_VALUE) = Integer.MIN_VALUE  // still negative!
```

`Integer.MIN_VALUE` (-2,147,483,648) has no positive counterpart in 32-bit integers. `Math.abs()` returns the same negative value. Then `% 100` could return a negative bucket. In practice, this affects exactly one hash value out of 4 billion, so the risk is negligible.

A more robust implementation would use:

```java
int bucket = (hashInput.hashCode() & 0x7FFFFFFF) % 100;  // mask sign bit
```

---

## 5. Code Walkthrough — FeatureFlagEvaluator

```java
@Component
public class FeatureFlagEvaluator {

    public boolean evaluate(FeatureFlag flag, FlagContext context) {
        // Gate 1: Flag globally disabled → always false
        if (!flag.isEnabled()) {
            return false;
        }

        // Gate 2: 100% rollout → always true (skip hashing)
        if (flag.getRolloutPct() >= 100) {
            return true;
        }

        // Gate 3: 0% rollout → always false (skip hashing)
        if (flag.getRolloutPct() <= 0) {
            return false;
        }

        // Gate 4: Percentage rollout — deterministic hash
        String hashInput = context.userId() + ":" + flag.getFlagKey();
        int bucket = Math.abs(hashInput.hashCode() % 100);
        return bucket < flag.getRolloutPct();
    }
}
```

The early returns for 0% and 100% are not just optimizations. They prevent unnecessary hashing and make the boundary cases explicit and correct.

### FlagContext Record

```java
public record FlagContext(
        String userId,
        Map<String, String> attributes    // extensible: region, plan, etc.
) {
    public FlagContext(String userId) {
        this(userId, Map.of());           // convenience: just user ID
    }
}
```

`FlagContext` is a Java record (immutable, auto-generates `equals`/`hashCode`/`toString`). The `attributes` map is a future extension point for targeting by region, subscription plan, device type, etc.

---

## 6. Code Walkthrough — FeatureFlagService

```java
@Service
public class FeatureFlagService {

    private final FeatureFlagRepository flagRepository;
    private final FeatureFlagEvaluator evaluator;

    // Cache flags for 30 seconds to avoid DB queries per request
    private final Cache<String, Optional<FeatureFlag>> flagCache;

    public FeatureFlagService(FeatureFlagRepository flagRepository,
                              FeatureFlagEvaluator evaluator) {
        this.flagRepository = flagRepository;
        this.evaluator = evaluator;
        this.flagCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(30))
                .maximumSize(500)
                .build();
    }

    public boolean isEnabled(String flagKey, FlagContext context) {
        Optional<FeatureFlag> flag = getFlag(flagKey);
        return flag.map(f -> evaluator.evaluate(f, context))
                   .orElse(false);     // unknown flag = disabled (safe default)
    }

    public Optional<FeatureFlag> getFlag(String flagKey) {
        return flagCache.get(flagKey, k -> flagRepository.findByFlagKey(k));
    }

    public void invalidateCache() {
        flagCache.invalidateAll();
    }
}
```

### Why `orElse(false)`?

If someone checks a flag that does not exist in the database, the result is `false`. This is the safe default — unknown flags are disabled. You never accidentally enable a feature by referencing a misspelled flag key.

### Cache Behavior

```
Request 1: isEnabled("new-checkout", context)
  → flagCache miss → DB query → cache flag → evaluate → true

Request 2 (1 second later): isEnabled("new-checkout", context)
  → flagCache hit → evaluate → true (no DB query)

Admin updates rollout_pct from 25% to 50%
  → calls invalidateCache()
  → next request triggers fresh DB load with new percentage
```

The 30-second TTL means that even without explicit invalidation (e.g., if Kafka is down), flag changes take effect within 30 seconds.

---

## 7. Admin API: CRUD + Evaluate

### Creating a Flag

```bash
curl -X POST http://localhost:8080/admin/api/v1/flags \
  -H "Content-Type: application/json" \
  -d '{
    "flagKey": "new-checkout-flow",
    "description": "Redesigned checkout experience",
    "enabled": true,
    "rolloutPct": 25,
    "strategy": "PERCENTAGE",
    "targetRouteId": 1
  }'
```

### Evaluating a Flag (Testing Endpoint)

```bash
curl -X POST http://localhost:8080/admin/api/v1/flags/new-checkout-flow/evaluate \
  -H "Content-Type: application/json" \
  -d '{"userId": "alice", "attributes": {}}'

# Response:
{
  "flagKey": "new-checkout-flow",
  "userId": "alice",
  "enabled": false
}
```

This evaluate endpoint is for testing and debugging. It lets you check what result a specific user would get without making a real proxied request.

```java
@PostMapping("/{key}/evaluate")
public ResponseEntity<Map<String, Object>> evaluate(@PathVariable String key,
                                                    @RequestBody FlagEvaluateRequest request) {
    FlagContext context = new FlagContext(request.getUserId(), request.getAttributes());
    boolean result = featureFlagService.isEnabled(key, context);
    return ResponseEntity.ok(Map.of(
            "flagKey", key,
            "userId", request.getUserId(),
            "enabled", result
    ));
}
```

---

## 8. Strategy Types

### PERCENTAGE (Built)

The only strategy implemented. Uses deterministic hashing to bucket users:

```
strategy = "PERCENTAGE"
rolloutPct = 25
→ hash(userId + flagKey) % 100 < 25
```

### Future Strategies

| Strategy | How It Works | Use Case |
|----------|-------------|----------|
| **USER_LIST** | Check if userId is in an explicit list | Beta testers, internal employees |
| **GROUP** | Check if user's group/org matches | Enterprise customers, specific regions |
| **GRADUAL** | Automatically increase % over time | Automated progressive rollout |
| **SCHEDULE** | Enable between specific dates/times | Holiday features, time-limited promotions |

The `strategy` field on the `FeatureFlag` entity is stored as a string, making it easy to add new strategies by extending the `FeatureFlagEvaluator` with additional switch cases.

---

## 9. The Database Schema

```sql
CREATE TABLE feature_flags (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    flag_key        VARCHAR(255) NOT NULL UNIQUE,   -- lookup key
    description     VARCHAR(500),                    -- human-readable purpose
    enabled         BOOLEAN NOT NULL DEFAULT FALSE,  -- global on/off switch
    rollout_pct     INT NOT NULL DEFAULT 0,          -- 0-100 percentage
    target_route_id BIGINT REFERENCES routes(id)     -- optional route scope
                    ON DELETE SET NULL,
    strategy        VARCHAR(50) NOT NULL DEFAULT 'PERCENTAGE',
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

Key design decisions:

- **`flag_key` is UNIQUE:** You cannot have two flags with the same key. This prevents ambiguity.
- **`enabled` vs `rollout_pct`:** Two-level control. `enabled=false` overrides any percentage. Think of `enabled` as a circuit breaker and `rollout_pct` as fine-grained control.
- **`ON DELETE SET NULL`:** If the target route is deleted, the flag becomes global rather than being deleted.
- **`strategy`:** Stored as a string, not an enum, for forward compatibility.

---

## 10. How Real Systems Work

### LaunchDarkly

LaunchDarkly is the industry leader in feature flags.

```
Architecture:
  Dashboard → LaunchDarkly Cloud → SDKs (server-side evaluation)

Key difference from EdgeFlow:
  LaunchDarkly SDKs download ALL flag rules on startup
  Evaluation happens locally (no network call per evaluation)
  Flag changes are pushed via streaming (Server-Sent Events)
  → Sub-second propagation, zero-latency evaluation

EdgeFlow:
  Flag rules stored in DB, cached 30 seconds via Caffeine
  Evaluation is local (after cache load)
  Changes propagated via cache TTL or Kafka events
```

LaunchDarkly also supports complex targeting: user attributes, segments, custom rules, and multivariate flags (not just boolean).

### Unleash

Open-source alternative to LaunchDarkly:

```
Architecture:
  Unleash Server (self-hosted) → SDKs poll every 15 seconds
  Strategies: gradualRollout, userWithId, applicationHostname
  Evaluation: server-side (like EdgeFlow)
```

### Flagsmith

Another open-source option with a hosted offering:

```
Features beyond EdgeFlow:
  Remote config (key-value, not just boolean)
  Segments (groups of users by attributes)
  Change history and audit log
  A/B testing with analytics integration
```

### Comparison

| Feature | LaunchDarkly | Unleash | Flagsmith | EdgeFlow |
|---------|-------------|---------|-----------|----------|
| Evaluation | Client-side (SDK) | Server-side | Server/Client | Server-side |
| Propagation | Streaming (instant) | Polling (15s) | Polling/Streaming | Cache TTL (30s) / Kafka |
| Targeting | Complex rules | Strategies | Segments | Percentage only |
| Flag types | Boolean, String, Number, JSON | Boolean | Boolean, String, Number | Boolean |
| Audit log | Yes | Yes | Yes | No |
| Self-hosted | No (SaaS only) | Yes | Yes | Yes |
| Pricing | Expensive | Free (OSS) | Free (OSS) | Free |

---

## 11. What Changed in This Phase

### New Files

| File | Purpose |
|------|---------|
| `db/migration/V7__create_feature_flags.sql` | Schema: `feature_flags` table |
| `domain/flag/FeatureFlag.java` | JPA entity: flag config (key, rolloutPct, strategy) |
| `domain/flag/FeatureFlagRepository.java` | Queries: by key, by route, enabled |
| `featureflag/FlagContext.java` | Record: user identity + attributes for evaluation |
| `featureflag/FeatureFlagEvaluator.java` | Deterministic percentage rollout logic |
| `featureflag/FeatureFlagService.java` | Cache + evaluate orchestration |
| `admin/FeatureFlagAdminController.java` | REST API: CRUD + evaluate endpoint |
| `admin/dto/FeatureFlagRequest.java` | Request DTO for creating/updating flags |
| `admin/dto/FeatureFlagResponse.java` | Response DTO with `from(FeatureFlag)` factory |
| `admin/dto/FlagEvaluateRequest.java` | Request DTO for the evaluate endpoint |

### Modified Files

| File | Change |
|------|--------|
| `build.gradle` | No new dependencies (Caffeine already present) |

---

## 12. Key Concepts to Remember

| Concept | EdgeFlow Example |
|---------|-----------------|
| **Feature flag** | Boolean switch to enable/disable features without deployment |
| **Progressive rollout** | Gradually increase `rolloutPct` from 0 to 100 |
| **Instant rollback** | Set `rolloutPct = 0` or `enabled = false` — no deploy needed |
| **Deterministic hashing** | `hash(userId + flagKey) % 100` — same user always gets same result |
| **Why include flagKey** | Without it, all flags would bucket users identically |
| **Bucket range** | Increasing rollout from 25% to 50% adds users 25-49, never removes 0-24 |
| **Safe default** | Unknown flag key returns `false` (disabled) |
| **Two-level control** | `enabled` is the circuit breaker, `rolloutPct` is fine-grained |
| **Caffeine cache** | Flags cached 30s, invalidated on admin changes |
| **Strategy field** | Stored as string for forward compatibility with future strategies |
| **FlagContext** | Record with userId + extensible attributes map |

---

## 13. What's Next — Phase 7: Kafka Events

Phase 6 has a problem when running multiple gateway instances:

```
Instance A: Admin updates flag rollout to 50%
  → Instance A invalidates its cache immediately
  → Instance B still has old cache (30s TTL) → stale for up to 30 seconds

Phase 7 adds Kafka:
  Admin updates flag → publishes event to Kafka topic
  ALL instances consume the event → ALL instances invalidate their caches
  Propagation time: ~100ms instead of up to 30 seconds
```

This applies to routes, rate limit rules, and feature flags — any cached configuration.
