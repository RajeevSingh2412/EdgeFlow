package com.edgeflow.admin.dto;

import com.edgeflow.domain.route.Route;
import com.edgeflow.domain.route.Upstream;

import java.time.LocalDateTime;
import java.util.List;

public class RouteResponse {

    private Long id;
    private String host;
    private String pathPrefix;
    private String description;
    private boolean enabled;
    private boolean stripPrefix;
    private int timeoutMs;
    private int retryCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<UpstreamResponse> upstreams;

    public static RouteResponse from(Route route) {
        RouteResponse response = new RouteResponse();
        response.id = route.getId();
        response.host = route.getHost();
        response.pathPrefix = route.getPathPrefix();
        response.description = route.getDescription();
        response.enabled = route.isEnabled();
        response.stripPrefix = route.isStripPrefix();
        response.timeoutMs = route.getTimeoutMs();
        response.retryCount = route.getRetryCount();
        response.createdAt = route.getCreatedAt();
        response.updatedAt = route.getUpdatedAt();
        response.upstreams = route.getUpstreams().stream()
                .map(UpstreamResponse::from)
                .toList();
        return response;
    }

    public Long getId() { return id; }
    public String getHost() { return host; }
    public String getPathPrefix() { return pathPrefix; }
    public String getDescription() { return description; }
    public boolean isEnabled() { return enabled; }
    public boolean isStripPrefix() { return stripPrefix; }
    public int getTimeoutMs() { return timeoutMs; }
    public int getRetryCount() { return retryCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public List<UpstreamResponse> getUpstreams() { return upstreams; }

    public static class UpstreamResponse {
        private Long id;
        private String url;
        private int weight;
        private boolean enabled;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        public static UpstreamResponse from(Upstream upstream) {
            UpstreamResponse response = new UpstreamResponse();
            response.id = upstream.getId();
            response.url = upstream.getUrl();
            response.weight = upstream.getWeight();
            response.enabled = upstream.isEnabled();
            response.createdAt = upstream.getCreatedAt();
            response.updatedAt = upstream.getUpdatedAt();
            return response;
        }

        public Long getId() { return id; }
        public String getUrl() { return url; }
        public int getWeight() { return weight; }
        public boolean isEnabled() { return enabled; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public LocalDateTime getUpdatedAt() { return updatedAt; }
    }
}
