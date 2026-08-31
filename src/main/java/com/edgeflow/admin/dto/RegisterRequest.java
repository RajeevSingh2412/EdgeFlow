package com.edgeflow.admin.dto;

public class RegisterRequest {

    private String serviceName;
    private String instanceId;
    private String url;
    private String routePathPrefix;
    private String healthCheckPath;
    private String metadata;

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getRoutePathPrefix() {
        return routePathPrefix;
    }

    public void setRoutePathPrefix(String routePathPrefix) {
        this.routePathPrefix = routePathPrefix;
    }

    public String getHealthCheckPath() {
        return healthCheckPath;
    }

    public void setHealthCheckPath(String healthCheckPath) {
        this.healthCheckPath = healthCheckPath;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }
}