package com.edgeflow.admin;

import com.edgeflow.admin.dto.HeartbeatRequest;
import com.edgeflow.admin.dto.RegisterRequest;
import com.edgeflow.admin.dto.ServiceInstanceResponse;
import com.edgeflow.domain.registry.ServiceInstance;
import com.edgeflow.registry.ServiceRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/api/v1/registry")
public class RegistryController {

    private final ServiceRegistry serviceRegistry;

    public RegistryController(ServiceRegistry serviceRegistry) {
        this.serviceRegistry = serviceRegistry;
    }

    @PostMapping("/register")
    public ResponseEntity<ServiceInstanceResponse> register(@RequestBody RegisterRequest request) {
        ServiceInstance instance = serviceRegistry.register(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ServiceInstanceResponse.from(instance));
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<Map<String, String>> heartbeat(@RequestBody HeartbeatRequest request) {
        boolean found = serviceRegistry.heartbeat(request.getInstanceId());
        if (!found) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Instance not found: " + request.getInstanceId()));
        }
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @PostMapping("/deregister")
    public ResponseEntity<Map<String, String>> deregister(@RequestBody HeartbeatRequest request) {
        boolean found = serviceRegistry.deregister(request.getInstanceId());
        if (!found) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Instance not found: " + request.getInstanceId()));
        }
        return ResponseEntity.ok(Map.of("status", "deregistered"));
    }

    @GetMapping("/services")
    public List<ServiceInstanceResponse> listAll() {
        return serviceRegistry.listAll().stream()
                .map(ServiceInstanceResponse::from)
                .toList();
    }

    @GetMapping("/services/{serviceName}")
    public List<ServiceInstanceResponse> listByService(@PathVariable String serviceName) {
        return serviceRegistry.listByService(serviceName).stream()
                .map(ServiceInstanceResponse::from)
                .toList();
    }
}