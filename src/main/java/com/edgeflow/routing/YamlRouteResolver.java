package com.edgeflow.routing;

import com.edgeflow.config.RouteConfig;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class YamlRouteResolver implements RouteResolver {

    private final RouteConfig routeConfig;

    public YamlRouteResolver(RouteConfig routeConfig) {
        this.routeConfig = routeConfig;
    }

    @Override
    public Optional<ResolvedRoute> resolve(String host, String path) {
        return routeConfig.findRoute(path)
                .map(route -> new ResolvedRoute(
                        null,
                        null,
                        route.getPathPrefix(),
                        route.getUpstream(),
                        false,
                        30000
                ));
    }

    @Override
    public void invalidateCache() {
        // YAML routes are static, nothing to invalidate
    }
}
