package com.edgeflow.admin.dto;

import java.util.Map;

public class FlagEvaluateRequest {

    private String userId;
    private Map<String, String> attributes = Map.of();

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public Map<String, String> getAttributes() { return attributes; }
    public void setAttributes(Map<String, String> attributes) { this.attributes = attributes; }
}
