package com.edgeflow.admin;

import com.edgeflow.admin.dto.FeatureFlagRequest;
import com.edgeflow.admin.dto.FeatureFlagResponse;
import com.edgeflow.admin.dto.FlagEvaluateRequest;
import com.edgeflow.domain.flag.FeatureFlag;
import com.edgeflow.domain.flag.FeatureFlagRepository;
import com.edgeflow.domain.route.RouteRepository;
import com.edgeflow.event.KafkaConfigPublisher;
import com.edgeflow.featureflag.FeatureFlagService;
import com.edgeflow.featureflag.FlagContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/api/v1/flags")
public class FeatureFlagAdminController {

    private final FeatureFlagRepository flagRepository;
    private final RouteRepository routeRepository;
    private final FeatureFlagService featureFlagService;
    private final KafkaConfigPublisher configPublisher;

    public FeatureFlagAdminController(FeatureFlagRepository flagRepository,
                                      RouteRepository routeRepository,
                                      FeatureFlagService featureFlagService,
                                      KafkaConfigPublisher configPublisher) {
        this.flagRepository = flagRepository;
        this.routeRepository = routeRepository;
        this.featureFlagService = featureFlagService;
        this.configPublisher = configPublisher;
    }

    @GetMapping
    public List<FeatureFlagResponse> listFlags() {
        return flagRepository.findAll().stream()
                .map(FeatureFlagResponse::from)
                .toList();
    }

    @PostMapping
    public ResponseEntity<FeatureFlagResponse> createFlag(@RequestBody FeatureFlagRequest request) {
        FeatureFlag flag = new FeatureFlag();
        flag.setFlagKey(request.getFlagKey());
        flag.setDescription(request.getDescription());
        flag.setEnabled(request.isEnabled());
        flag.setRolloutPct(request.getRolloutPct());
        flag.setStrategy(request.getStrategy());

        if (request.getTargetRouteId() != null) {
            routeRepository.findById(request.getTargetRouteId())
                    .ifPresent(flag::setTargetRoute);
        }

        FeatureFlag saved = flagRepository.save(flag);
        featureFlagService.invalidateCache();
        configPublisher.publishFlagChange(saved.getId(), "CREATED");

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(FeatureFlagResponse.from(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<FeatureFlagResponse> updateFlag(@PathVariable Long id,
                                                          @RequestBody FeatureFlagRequest request) {
        return flagRepository.findById(id)
                .map(flag -> {
                    flag.setFlagKey(request.getFlagKey());
                    flag.setDescription(request.getDescription());
                    flag.setEnabled(request.isEnabled());
                    flag.setRolloutPct(request.getRolloutPct());
                    flag.setStrategy(request.getStrategy());

                    if (request.getTargetRouteId() != null) {
                        routeRepository.findById(request.getTargetRouteId())
                                .ifPresent(flag::setTargetRoute);
                    } else {
                        flag.setTargetRoute(null);
                    }

                    FeatureFlag saved = flagRepository.save(flag);
                    featureFlagService.invalidateCache();
                    configPublisher.publishFlagChange(saved.getId(), "UPDATED");
                    return ResponseEntity.ok(FeatureFlagResponse.from(saved));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteFlag(@PathVariable Long id) {
        if (!flagRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        flagRepository.deleteById(id);
        featureFlagService.invalidateCache();
        configPublisher.publishFlagChange(id, "DELETED");
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{key}/evaluate")
    public ResponseEntity<Map<String, Object>> evaluate(@PathVariable String key,
                                                        @RequestBody FlagEvaluateRequest request) {
        FlagContext context = new FlagContext(request.getUserId(), request.getAttributes());
        boolean result = featureFlagService.isEnabled(key, context);
        return ResponseEntity.ok(Map.of(
                "flagKey", key,
                "userId", request.getUserId(),
                "enabled", result
        ));
    }
}
