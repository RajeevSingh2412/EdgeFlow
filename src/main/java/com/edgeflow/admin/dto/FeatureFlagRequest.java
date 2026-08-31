package com.edgeflow.admin.dto;

public class FeatureFlagRequest {

    private String flagKey;
    private String description;
    private boolean enabled = false;
    private int rolloutPct = 0;
    private Long targetRouteId;
    private String strategy = "PERCENTAGE";

    public String getFlagKey() { return flagKey; }
    public void setFlagKey(String flagKey) { this.flagKey = flagKey; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getRolloutPct() { return rolloutPct; }
    public void setRolloutPct(int rolloutPct) { this.rolloutPct = rolloutPct; }
    public Long getTargetRouteId() { return targetRouteId; }
    public void setTargetRouteId(Long targetRouteId) { this.targetRouteId = targetRouteId; }
    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }
}
