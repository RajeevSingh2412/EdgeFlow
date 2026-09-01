package com.edgeflow.admin;

import com.edgeflow.admin.dto.RouteRequest;
import com.edgeflow.admin.dto.RouteResponse;
import com.edgeflow.admin.dto.UpstreamRequest;
import com.edgeflow.domain.route.Route;
import com.edgeflow.domain.route.RouteRepository;
import com.edgeflow.domain.route.Upstream;
import com.edgeflow.domain.route.UpstreamRepository;
import com.edgeflow.event.KafkaConfigPublisher;
import com.edgeflow.routing.RouteResolver;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/api/v1/routes")
public class RouteAdminController {

    private final RouteRepository routeRepository;
    private final UpstreamRepository upstreamRepository;
    private final RouteResolver routeResolver;
    private final KafkaConfigPublisher configPublisher;

    public RouteAdminController(RouteRepository routeRepository,
                                UpstreamRepository upstreamRepository,
                                RouteResolver routeResolver,
                                KafkaConfigPublisher configPublisher) {
        this.routeRepository = routeRepository;
        this.upstreamRepository = upstreamRepository;
        this.routeResolver = routeResolver;
        this.configPublisher = configPublisher;
    }

    @GetMapping
    public List<RouteResponse> listRoutes() {
        return routeRepository.findAll().stream()
                .map(RouteResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<RouteResponse> getRoute(@PathVariable Long id) {
        return routeRepository.findById(id)
                .map(RouteResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<RouteResponse> createRoute(@RequestBody RouteRequest request) {
        Route route = new Route();
        route.setHost(request.getHost());
        route.setPathPrefix(request.getPathPrefix());
        route.setDescription(request.getDescription());
        route.setEnabled(request.isEnabled());
        route.setStripPrefix(request.isStripPrefix());
        route.setTimeoutMs(request.getTimeoutMs());
        route.setRetryCount(request.getRetryCount());

        for (UpstreamRequest ur : request.getUpstreams()) {
            Upstream upstream = new Upstream();
            upstream.setUrl(ur.getUrl());
            upstream.setWeight(ur.getWeight());
            upstream.setEnabled(ur.isEnabled());
            upstream.setHealthCheckPath(ur.getHealthCheckPath());
            route.addUpstream(upstream);
        }

        Route saved = routeRepository.save(route);
        routeResolver.invalidateCache();
        configPublisher.publishRouteChange(saved.getId(), "CREATED");

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(RouteResponse.from(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<RouteResponse> updateRoute(@PathVariable Long id,
                                                     @RequestBody RouteRequest request) {
        return routeRepository.findById(id)
                .map(route -> {
                    route.setHost(request.getHost());
                    route.setPathPrefix(request.getPathPrefix());
                    route.setDescription(request.getDescription());
                    route.setEnabled(request.isEnabled());
                    route.setStripPrefix(request.isStripPrefix());
                    route.setTimeoutMs(request.getTimeoutMs());
                    route.setRetryCount(request.getRetryCount());

                    Route saved = routeRepository.save(route);
                    routeResolver.invalidateCache();
                    configPublisher.publishRouteChange(saved.getId(), "UPDATED");
                    return ResponseEntity.ok(RouteResponse.from(saved));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRoute(@PathVariable Long id) {
        if (!routeRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        routeRepository.deleteById(id);
        routeResolver.invalidateCache();
        configPublisher.publishRouteChange(id, "DELETED");
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/upstreams")
    public ResponseEntity<RouteResponse> addUpstream(@PathVariable Long id,
                                                     @RequestBody UpstreamRequest request) {
        return routeRepository.findById(id)
                .map(route -> {
                    Upstream upstream = new Upstream();
                    upstream.setUrl(request.getUrl());
                    upstream.setWeight(request.getWeight());
                    upstream.setEnabled(request.isEnabled());
                    upstream.setHealthCheckPath(request.getHealthCheckPath());
                    route.addUpstream(upstream);

                    Route saved = routeRepository.save(route);
                    routeResolver.invalidateCache();
                    return ResponseEntity.status(HttpStatus.CREATED)
                            .body(RouteResponse.from(saved));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{routeId}/upstreams/{upstreamId}")
    public ResponseEntity<Void> removeUpstream(@PathVariable Long routeId,
                                               @PathVariable Long upstreamId) {
        return routeRepository.findById(routeId)
                .map(route -> {
                    route.getUpstreams().removeIf(u -> u.getId().equals(upstreamId));
                    routeRepository.save(route);
                    routeResolver.invalidateCache();
                    return ResponseEntity.noContent().<Void>build();
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/reload")
    public ResponseEntity<Map<String, String>> reloadCache() {
        routeResolver.invalidateCache();
        return ResponseEntity.ok(Map.of("status", "cache invalidated"));
    }
}
