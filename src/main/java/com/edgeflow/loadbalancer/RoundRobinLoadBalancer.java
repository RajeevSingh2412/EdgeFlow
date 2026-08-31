package com.edgeflow.loadbalancer;

import com.edgeflow.domain.route.Upstream;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class RoundRobinLoadBalancer implements LoadBalancer {

    private final ConcurrentHashMap<Long, AtomicInteger> counters = new ConcurrentHashMap<>();

    @Override
    public Optional<Upstream> choose(Long routeId, List<Upstream> upstreams) {
        if (upstreams == null || upstreams.isEmpty()) {
            return Optional.empty();
        }
        if (upstreams.size() == 1) {
            return Optional.of(upstreams.get(0));
        }

        int totalWeight = upstreams.stream()
                .mapToInt(u -> Math.max(u.getWeight(), 1))
                .sum();

        AtomicInteger counter = counters.computeIfAbsent(routeId, k -> new AtomicInteger(0));
        int index = Math.floorMod(counter.getAndIncrement(), totalWeight);

        int cumulative = 0;
        for (Upstream upstream : upstreams) {
            cumulative += Math.max(upstream.getWeight(), 1);
            if (index < cumulative) {
                return Optional.of(upstream);
            }
        }

        return Optional.of(upstreams.get(0));
    }

    public void resetCounter(Long routeId) {
        counters.remove(routeId);
    }
}
