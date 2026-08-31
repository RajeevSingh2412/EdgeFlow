package com.edgeflow.registry;

import com.edgeflow.admin.dto.RegisterRequest;
import com.edgeflow.domain.registry.ServiceInstance;
import com.edgeflow.domain.registry.ServiceInstanceRepository;
import com.edgeflow.domain.route.Route;
import com.edgeflow.domain.route.RouteRepository;
import com.edgeflow.domain.route.Upstream;
import com.edgeflow.domain.route.UpstreamRepository;
import com.edgeflow.routing.RouteResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class ServiceRegistry {

    private static final Logger log = LoggerFactory.getLogger(ServiceRegistry.class);

    private final ServiceInstanceRepository instanceRepository;
    private final RouteRepository routeRepository;
    private final UpstreamRepository upstreamRepository;
    private final RouteResolver routeResolver;

    @Value("${edgeflow.registry.expiry-ms:30000}")
    private long expiryMs;

    public ServiceRegistry(ServiceInstanceRepository instanceRepository,
                           RouteRepository routeRepository,
                           UpstreamRepository upstreamRepository,
                           RouteResolver routeResolver) {
        this.instanceRepository = instanceRepository;
        this.routeRepository = routeRepository;
        this.upstreamRepository = upstreamRepository;
        this.routeResolver = routeResolver;
    }

    @Transactional
    public ServiceInstance register(RegisterRequest request) {
        // Check if this instance already exists (re-registration)
        Optional<ServiceInstance> existing = instanceRepository.findByInstanceId(request.getInstanceId());
        if (existing.isPresent()) {
            return reRegister(existing.get(), request);
        }

        // Find the route for this service
        Route route = findOrCreateRoute(request);

        // Create an upstream for this instance
        Upstream upstream = new Upstream();
        upstream.setUrl(request.getUrl());
        upstream.setWeight(1);
        upstream.setEnabled(true);
        upstream.setRoute(route);
        upstream = upstreamRepository.saveAndFlush(upstream);

        // Create the service instance record
        ServiceInstance instance = new ServiceInstance();
        instance.setServiceName(request.getServiceName());
        instance.setInstanceId(request.getInstanceId());
        instance.setUrl(request.getUrl());
        instance.setStatus("UP");
        instance.setHealthCheckPath(
                request.getHealthCheckPath() != null ? request.getHealthCheckPath() : "/health");
        instance.setRoute(route);
        instance.setUpstream(upstream);
        instance.setMetadata(request.getMetadata());

        ServiceInstance saved = instanceRepository.save(instance);
        routeResolver.invalidateCache();

        log.info("Registered service instance: {} ({}) -> route {}",
                instance.getInstanceId(), instance.getUrl(), route.getPathPrefix());

        return saved;
    }

    private ServiceInstance reRegister(ServiceInstance instance, RegisterRequest request) {
        instance.setUrl(request.getUrl());
        instance.setStatus("UP");
        instance.setLastHeartbeatAt(LocalDateTime.now());

        if (request.getHealthCheckPath() != null) {
            instance.setHealthCheckPath(request.getHealthCheckPath());
        }
        if (request.getMetadata() != null) {
            instance.setMetadata(request.getMetadata());
        }

        // Re-enable the linked upstream
        Upstream upstream = instance.getUpstream();
        if (upstream != null) {
            upstream.setUrl(request.getUrl());
            upstream.setEnabled(true);
        }

        ServiceInstance saved = instanceRepository.save(instance);
        routeResolver.invalidateCache();

        log.info("Re-registered service instance: {} ({})", instance.getInstanceId(), instance.getUrl());

        return saved;
    }

    @Transactional
    public boolean heartbeat(String instanceId) {
        Optional<ServiceInstance> opt = instanceRepository.findByInstanceId(instanceId);
        if (opt.isEmpty()) {
            return false;
        }

        ServiceInstance instance = opt.get();
        instance.setLastHeartbeatAt(LocalDateTime.now());
        instance.setStatus("UP");

        // Re-enable upstream if it was disabled
        Upstream upstream = instance.getUpstream();
        if (upstream != null && !upstream.isEnabled()) {
            upstream.setEnabled(true);
            routeResolver.invalidateCache();
        }

        instanceRepository.save(instance);
        return true;
    }

    @Transactional
    public boolean deregister(String instanceId) {
        Optional<ServiceInstance> opt = instanceRepository.findByInstanceId(instanceId);
        if (opt.isEmpty()) {
            return false;
        }

        ServiceInstance instance = opt.get();

        // Disable the upstream (don't delete — keep the route structure)
        Upstream upstream = instance.getUpstream();
        if (upstream != null) {
            upstream.setEnabled(false);
        }

        instance.setStatus("DOWN");
        instanceRepository.save(instance);
        routeResolver.invalidateCache();

        log.info("Deregistered service instance: {}", instanceId);
        return true;
    }

    @Scheduled(fixedDelayString = "${edgeflow.registry.heartbeat-interval-ms:10000}")
    @Transactional
    public void cleanupStaleInstances() {
        LocalDateTime threshold = LocalDateTime.now().minusNanos(expiryMs * 1_000_000);

        List<ServiceInstance> staleInstances =
                instanceRepository.findAllByStatusAndLastHeartbeatAtBefore("UP", threshold);

        if (staleInstances.isEmpty()) {
            return;
        }

        for (ServiceInstance instance : staleInstances) {
            instance.setStatus("DOWN");

            Upstream upstream = instance.getUpstream();
            if (upstream != null) {
                upstream.setEnabled(false);
            }

            instanceRepository.save(instance);
            log.warn("Instance {} expired (no heartbeat for {}ms), disabled upstream",
                    instance.getInstanceId(), expiryMs);
        }

        routeResolver.invalidateCache();
    }

    public List<ServiceInstance> listAll() {
        return instanceRepository.findAll();
    }

    public List<ServiceInstance> listByService(String serviceName) {
        return instanceRepository.findAllByServiceName(serviceName);
    }

    private Route findOrCreateRoute(RegisterRequest request) {
        String pathPrefix = request.getRoutePathPrefix();

        // Try to find an existing route with this path prefix
        List<Route> routes = routeRepository.findAllByEnabledTrue();
        Optional<Route> existing = routes.stream()
                .filter(r -> r.getPathPrefix().equals(pathPrefix))
                .findFirst();

        if (existing.isPresent()) {
            return existing.get();
        }

        // Auto-create a route for this service
        Route route = new Route();
        route.setPathPrefix(pathPrefix);
        route.setDescription("Auto-created for service: " + request.getServiceName());
        route.setEnabled(true);
        return routeRepository.save(route);
    }
}