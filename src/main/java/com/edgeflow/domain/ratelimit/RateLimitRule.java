package com.edgeflow.domain.ratelimit;

import com.edgeflow.domain.route.Route;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "rate_limit_rules")
public class RateLimitRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "route_id")
    private Route route;

    @Column(name = "key_type", nullable = false)
    private String keyType = "IP";

    @Column(name = "max_tokens", nullable = false)
    private int maxTokens = 100;

    @Column(name = "refill_rate", nullable = false)
    private int refillRate = 10;

    @Column(name = "refill_interval_ms", nullable = false)
    private int refillIntervalMs = 1000;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Route getRoute() { return route; }
    public void setRoute(Route route) { this.route = route; }
    public String getKeyType() { return keyType; }
    public void setKeyType(String keyType) { this.keyType = keyType; }
    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
    public int getRefillRate() { return refillRate; }
    public void setRefillRate(int refillRate) { this.refillRate = refillRate; }
    public int getRefillIntervalMs() { return refillIntervalMs; }
    public void setRefillIntervalMs(int refillIntervalMs) { this.refillIntervalMs = refillIntervalMs; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
