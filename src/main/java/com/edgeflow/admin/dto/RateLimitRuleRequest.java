package com.edgeflow.admin.dto;

public class RateLimitRuleRequest {

    private String name;
    private Long routeId;
    private String keyType = "IP";
    private int maxTokens = 100;
    private int refillRate = 10;
    private int refillIntervalMs = 1000;
    private boolean enabled = true;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Long getRouteId() { return routeId; }
    public void setRouteId(Long routeId) { this.routeId = routeId; }
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
}
