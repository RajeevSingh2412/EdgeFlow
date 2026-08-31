package com.edgeflow.admin.dto;

import java.util.ArrayList;
import java.util.List;

public class RouteRequest {

    private String host;
    private String pathPrefix;
    private String description;
    private boolean enabled = true;
    private boolean stripPrefix = false;
    private int timeoutMs = 30000;
    private int retryCount = 0;
    private List<UpstreamRequest> upstreams = new ArrayList<>();

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getPathPrefix() {
        return pathPrefix;
    }

    public void setPathPrefix(String pathPrefix) {
        this.pathPrefix = pathPrefix;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isStripPrefix() {
        return stripPrefix;
    }

    public void setStripPrefix(boolean stripPrefix) {
        this.stripPrefix = stripPrefix;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public List<UpstreamRequest> getUpstreams() {
        return upstreams;
    }

    public void setUpstreams(List<UpstreamRequest> upstreams) {
        this.upstreams = upstreams;
    }
}
