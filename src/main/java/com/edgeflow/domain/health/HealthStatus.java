package com.edgeflow.domain.health;

import com.edgeflow.domain.route.Upstream;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "health_status")
public class HealthStatus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "upstream_id", nullable = false, unique = true)
    private Upstream upstream;

    @Column(name = "healthy", nullable = false)
    private boolean healthy = true;

    @Column(name = "last_check_at")
    private LocalDateTime lastCheckAt;

    @Column(name = "last_status_code")
    private Integer lastStatusCode;

    @Column(name = "last_response_ms")
    private Integer lastResponseMs;

    @Column(name = "consecutive_fails", nullable = false)
    private int consecutiveFails = 0;

    @Column(name = "consecutive_ok", nullable = false)
    private int consecutiveOk = 0;

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

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Upstream getUpstream() {
        return upstream;
    }

    public void setUpstream(Upstream upstream) {
        this.upstream = upstream;
    }

    public boolean isHealthy() {
        return healthy;
    }

    public void setHealthy(boolean healthy) {
        this.healthy = healthy;
    }

    public LocalDateTime getLastCheckAt() {
        return lastCheckAt;
    }

    public void setLastCheckAt(LocalDateTime lastCheckAt) {
        this.lastCheckAt = lastCheckAt;
    }

    public Integer getLastStatusCode() {
        return lastStatusCode;
    }

    public void setLastStatusCode(Integer lastStatusCode) {
        this.lastStatusCode = lastStatusCode;
    }

    public Integer getLastResponseMs() {
        return lastResponseMs;
    }

    public void setLastResponseMs(Integer lastResponseMs) {
        this.lastResponseMs = lastResponseMs;
    }

    public int getConsecutiveFails() {
        return consecutiveFails;
    }

    public void setConsecutiveFails(int consecutiveFails) {
        this.consecutiveFails = consecutiveFails;
    }

    public int getConsecutiveOk() {
        return consecutiveOk;
    }

    public void setConsecutiveOk(int consecutiveOk) {
        this.consecutiveOk = consecutiveOk;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
