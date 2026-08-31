package com.edgeflow.admin;

import com.edgeflow.admin.dto.RateLimitRuleRequest;
import com.edgeflow.admin.dto.RateLimitRuleResponse;
import com.edgeflow.domain.ratelimit.RateLimitRule;
import com.edgeflow.domain.ratelimit.RateLimitRuleRepository;
import com.edgeflow.domain.route.RouteRepository;
import com.edgeflow.event.KafkaConfigPublisher;
import com.edgeflow.ratelimit.RateLimitService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/api/v1/rate-limits")
public class RateLimitAdminController {

    private final RateLimitRuleRepository ruleRepository;
    private final RouteRepository routeRepository;
    private final RateLimitService rateLimitService;
    private final KafkaConfigPublisher configPublisher;

    public RateLimitAdminController(RateLimitRuleRepository ruleRepository,
                                    RouteRepository routeRepository,
                                    RateLimitService rateLimitService,
                                    KafkaConfigPublisher configPublisher) {
        this.ruleRepository = ruleRepository;
        this.routeRepository = routeRepository;
        this.rateLimitService = rateLimitService;
        this.configPublisher = configPublisher;
    }

    @GetMapping
    public List<RateLimitRuleResponse> listRules() {
        return ruleRepository.findAll().stream()
                .map(RateLimitRuleResponse::from)
                .toList();
    }

    @PostMapping
    public ResponseEntity<RateLimitRuleResponse> createRule(@RequestBody RateLimitRuleRequest request) {
        RateLimitRule rule = new RateLimitRule();
        rule.setName(request.getName());
        rule.setKeyType(request.getKeyType());
        rule.setMaxTokens(request.getMaxTokens());
        rule.setRefillRate(request.getRefillRate());
        rule.setRefillIntervalMs(request.getRefillIntervalMs());
        rule.setEnabled(request.isEnabled());

        if (request.getRouteId() != null) {
            routeRepository.findById(request.getRouteId())
                    .ifPresent(rule::setRoute);
        }

        RateLimitRule saved = ruleRepository.save(rule);
        rateLimitService.invalidateCache();
        configPublisher.publishRateLimitChange(saved.getId(), "CREATED");

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(RateLimitRuleResponse.from(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<RateLimitRuleResponse> updateRule(@PathVariable Long id,
                                                            @RequestBody RateLimitRuleRequest request) {
        return ruleRepository.findById(id)
                .map(rule -> {
                    rule.setName(request.getName());
                    rule.setKeyType(request.getKeyType());
                    rule.setMaxTokens(request.getMaxTokens());
                    rule.setRefillRate(request.getRefillRate());
                    rule.setRefillIntervalMs(request.getRefillIntervalMs());
                    rule.setEnabled(request.isEnabled());

                    if (request.getRouteId() != null) {
                        routeRepository.findById(request.getRouteId())
                                .ifPresent(rule::setRoute);
                    } else {
                        rule.setRoute(null);
                    }

                    RateLimitRule saved = ruleRepository.save(rule);
                    rateLimitService.invalidateCache();
                    configPublisher.publishRateLimitChange(saved.getId(), "UPDATED");
                    return ResponseEntity.ok(RateLimitRuleResponse.from(saved));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRule(@PathVariable Long id) {
        if (!ruleRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        ruleRepository.deleteById(id);
        rateLimitService.invalidateCache();
        configPublisher.publishRateLimitChange(id, "DELETED");
        return ResponseEntity.noContent().build();
    }
}
