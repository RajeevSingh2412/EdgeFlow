package com.edgeflow.routing;

import java.util.Optional;

public interface RouteResolver {

    /**
     * Resolve a route for the given host and request path.
     *
     * @param host the Host header value (can be null for path-only matching)
     * @param path the request URI path
     * @return the matched route with its upstream URL, or empty if no match
     */
    Optional<ResolvedRoute> resolve(String host, String path);

    /**
     * Invalidate any cached route data, forcing a reload on next resolve.
     */
    void invalidateCache();

    /**
     * A resolved route ready for proxying.
     */
    record ResolvedRoute(
            Long routeId,
            String host,
            String pathPrefix,
            String upstreamUrl,
            boolean stripPrefix,
            int timeoutMs
    ) {}
}
