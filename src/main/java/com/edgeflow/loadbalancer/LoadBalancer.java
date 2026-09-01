package com.edgeflow.loadbalancer;

import com.edgeflow.domain.route.Upstream;

import java.util.List;
import java.util.Optional;

public interface LoadBalancer {

    /**
     * Choose one upstream from the given list of enabled upstreams.
     *
     * @param routeId   the route ID, used for per-route state tracking
     * @param upstreams list of enabled upstreams to choose from
     * @return the chosen upstream, or empty if the list is empty
     */
    Optional<Upstream> choose(Long routeId, List<Upstream> upstreams);
}
