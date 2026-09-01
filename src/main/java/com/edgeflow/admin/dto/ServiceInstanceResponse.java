package com.edgeflow.admin.dto;

import com.edgeflow.domain.registry.ServiceInstance;

import java.time.LocalDateTime;

public class ServiceInstanceResponse {

    private Long id;
    private String serviceName;
    private String instanceId;
    private String url;
    private String status;
    private String healthCheckPath;
    private Long routeId;
    private String routePathPrefix;
    private Long upstreamId;
    private LocalDateTime lastHeartbeatAt;
    private LocalDateTime registeredAt;
    private String metadata;

    public static ServiceInstanceResponse from(ServiceInstance instance) {
        ServiceInstanceResponse response = new ServiceInstanceResponse();
        response.id = instance.getId();
        response.serviceName = instance.getServiceName();
        response.instanceId = instance.getInstanceId();
        response.url = instance.getUrl();
        response.status = instance.getStatus();
        response.healthCheckPath = instance.getHealthCheckPath();
        if (instance.getRoute() != null) {
            response.routeId = instance.getRoute().getId();
            response.routePathPrefix = instance.getRoute().getPathPrefix();
        }
        if (instance.getUpstream() != null) {
            response.upstreamId = instance.getUpstream().getId();
        }
        response.lastHeartbeatAt = instance.getLastHeartbeatAt();
        response.registeredAt = instance.getRegisteredAt();
        response.metadata = instance.getMetadata();
        return response;
    }

    public Long getId() { return id; }
    public String getServiceName() { return serviceName; }
    public String getInstanceId() { return instanceId; }
    public String getUrl() { return url; }
    public String getStatus() { return status; }
    public String getHealthCheckPath() { return healthCheckPath; }
    public Long getRouteId() { return routeId; }
    public String getRoutePathPrefix() { return routePathPrefix; }
    public Long getUpstreamId() { return upstreamId; }
    public LocalDateTime getLastHeartbeatAt() { return lastHeartbeatAt; }
    public LocalDateTime getRegisteredAt() { return registeredAt; }
    public String getMetadata() { return metadata; }
}