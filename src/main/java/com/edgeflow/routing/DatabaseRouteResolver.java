package com.edgeflow.routing;

import com.edgeflow.domain.route.Route;
import com.edgeflow.domain.route.RouteRepository;
import com.edgeflow.domain.route.Upstream;
import com.edgeflow.loadbalancer.LoadBalancer;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
@Primary
public class DatabaseRouteResolver implements RouteResolver {

    private final RouteRepository routeRepository;
    private final LoadBalancer loadBalancer;
    private final Cache<String, Optional<Route>> routeCache;

    public DatabaseRouteResolver(RouteRepository routeRepository, LoadBalancer loadBalancer) {
        this.routeRepository = routeRepository;
        this.loadBalancer = loadBalancer;
        this.routeCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(60))
                .maximumSize(1000)
                .build();
    }

    @Override
    public Optional<ResolvedRoute> resolve(String host, String path) {
        String cacheKey = (host != null ? host : "*") + "::" + path;

        Optional<Route> matchedRoute = routeCache.get(cacheKey, key -> findMatchingRoute(host, path));

        return matchedRoute.flatMap(route -> {
            List<Upstream> enabledUpstreams = route.getUpstreams().stream()
                    .filter(Upstream::isEnabled)
                    .toList();

            return loadBalancer.choose(route.getId(), enabledUpstreams)
                    .map(upstream -> new ResolvedRoute(
                            route.getId(),
                            route.getHost(),
                            route.getPathPrefix(),
                            upstream.getUrl(),
                            route.isStripPrefix(),
                            route.getTimeoutMs()
                    ));
        });
    }

    @Override
    public void invalidateCache() {
        routeCache.invalidateAll();
    }

    private Optional<Route> findMatchingRoute(String host, String path) {
        List<Route> enabledRoutes = routeRepository.findAllByEnabledTrue();

        return enabledRoutes.stream()
                .filter(route -> matchesHost(route, host))
                .filter(route -> path.startsWith(route.getPathPrefix()))
                .max(Comparator.comparingInt(route -> route.getPathPrefix().length()));
    }

    private boolean matchesHost(Route route, String host) {
        if (route.getHost() == null || route.getHost().isEmpty()) {
            return true;
        }
        return route.getHost().equalsIgnoreCase(host);
    }
}
