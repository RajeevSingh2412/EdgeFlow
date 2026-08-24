package com.edgeflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@ConfigurationProperties(prefix = "edgeflow")
public class RouteConfig {

    private List<Route> routes = new ArrayList<>();

    public List<Route> getRoutes() {
        return routes;
    }

    public void setRoutes(List<Route> routes) {
        this.routes = routes;
    }

    public Optional<Route> findRoute(String requestPath) {
        return routes.stream()
                .filter(route -> requestPath.startsWith(route.getPathPrefix()))
                .findFirst();
    }

    public static class Route {
        private String pathPrefix;
        private String upstream;

        public String getPathPrefix() {
            return pathPrefix;
        }

        public void setPathPrefix(String pathPrefix) {
            this.pathPrefix = pathPrefix;
        }

        public String getUpstream() {
            return upstream;
        }

        public void setUpstream(String upstream) {
            this.upstream = upstream;
        }
    }
}