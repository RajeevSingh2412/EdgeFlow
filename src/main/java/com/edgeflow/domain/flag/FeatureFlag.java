package com.edgeflow.domain.flag;

import com.edgeflow.domain.route.Route;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "feature_flags")
public class FeatureFlag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "flag_key", nullable = false, unique = true)
    private String flagKey;

    @Column(name = "description")
    private String description;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = false;

    @Column(name = "rollout_pct", nullable = false)
    private int rolloutPct = 0;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_route_id")
    private Route targetRoute;

    @Column(name = "strategy", nullable = false)
    private String strategy = "PERCENTAGE";

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
    public String getFlagKey() { return flagKey; }
    public void setFlagKey(String flagKey) { this.flagKey = flagKey; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getRolloutPct() { return rolloutPct; }
    public void setRolloutPct(int rolloutPct) { this.rolloutPct = rolloutPct; }
    public Route getTargetRoute() { return targetRoute; }
    public void setTargetRoute(Route targetRoute) { this.targetRoute = targetRoute; }
    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
