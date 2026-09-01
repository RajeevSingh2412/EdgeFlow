package com.edgeflow.admin;

import com.edgeflow.admin.dto.HealthStatusResponse;
import com.edgeflow.domain.health.HealthStatusRepository;
import com.edgeflow.healthcheck.HealthChecker;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/api/v1/health")
public class HealthAdminController {

    private final HealthStatusRepository healthStatusRepository;
    private final HealthChecker healthChecker;

    public HealthAdminController(HealthStatusRepository healthStatusRepository,
                                 HealthChecker healthChecker) {
        this.healthStatusRepository = healthStatusRepository;
        this.healthChecker = healthChecker;
    }

    @GetMapping
    public List<HealthStatusResponse> getAllHealth() {
        return healthStatusRepository.findAll().stream()
                .map(HealthStatusResponse::from)
                .toList();
    }

    @GetMapping("/route/{routeId}")
    public List<HealthStatusResponse> getHealthByRoute(@PathVariable Long routeId) {
        return healthStatusRepository.findAllByUpstreamRouteId(routeId).stream()
                .map(HealthStatusResponse::from)
                .toList();
    }

    @PostMapping("/check")
    public ResponseEntity<Map<String, String>> triggerCheck() {
        healthChecker.checkAllImmediate();
        return ResponseEntity.ok(Map.of("status", "health check completed"));
    }
}
