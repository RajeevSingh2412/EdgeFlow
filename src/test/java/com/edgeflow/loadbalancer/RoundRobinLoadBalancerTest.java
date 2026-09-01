package com.edgeflow.loadbalancer;

import com.edgeflow.domain.route.Upstream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class RoundRobinLoadBalancerTest {

    private RoundRobinLoadBalancer loadBalancer;

    @BeforeEach
    void setUp() {
        loadBalancer = new RoundRobinLoadBalancer();
    }

    @Test
    void emptyList_returnsEmpty() {
        Optional<Upstream> result = loadBalancer.choose(1L, List.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void nullList_returnsEmpty() {
        Optional<Upstream> result = loadBalancer.choose(1L, null);
        assertTrue(result.isEmpty());
    }

    @Test
    void singleUpstream_alwaysReturnsSame() {
        Upstream upstream = createUpstream(1L, "http://a:8080", 1);

        for (int i = 0; i < 10; i++) {
            Optional<Upstream> result = loadBalancer.choose(1L, List.of(upstream));
            assertTrue(result.isPresent());
            assertEquals("http://a:8080", result.get().getUrl());
        }
    }

    @Test
    void equalWeights_distributesEvenly() {
        Upstream a = createUpstream(1L, "http://a:8080", 1);
        Upstream b = createUpstream(2L, "http://b:8080", 1);
        List<Upstream> upstreams = List.of(a, b);

        Map<String, Integer> counts = new HashMap<>();
        int totalRequests = 100;

        for (int i = 0; i < totalRequests; i++) {
            Upstream chosen = loadBalancer.choose(1L, upstreams).orElseThrow();
            counts.merge(chosen.getUrl(), 1, Integer::sum);
        }

        assertEquals(50, counts.get("http://a:8080"));
        assertEquals(50, counts.get("http://b:8080"));
    }

    @Test
    void threeUpstreams_roundRobinOrder() {
        Upstream a = createUpstream(1L, "http://a:8080", 1);
        Upstream b = createUpstream(2L, "http://b:8080", 1);
        Upstream c = createUpstream(3L, "http://c:8080", 1);
        List<Upstream> upstreams = List.of(a, b, c);

        assertEquals("http://a:8080", loadBalancer.choose(1L, upstreams).get().getUrl());
        assertEquals("http://b:8080", loadBalancer.choose(1L, upstreams).get().getUrl());
        assertEquals("http://c:8080", loadBalancer.choose(1L, upstreams).get().getUrl());
        assertEquals("http://a:8080", loadBalancer.choose(1L, upstreams).get().getUrl());
    }

    @Test
    void weightedDistribution_respectsWeights() {
        Upstream a = createUpstream(1L, "http://a:8080", 3);
        Upstream b = createUpstream(2L, "http://b:8080", 1);
        List<Upstream> upstreams = List.of(a, b);

        Map<String, Integer> counts = new HashMap<>();

        // Total weight = 4, so 4 requests = one full cycle
        for (int i = 0; i < 8; i++) {
            Upstream chosen = loadBalancer.choose(1L, upstreams).orElseThrow();
            counts.merge(chosen.getUrl(), 1, Integer::sum);
        }

        assertEquals(6, counts.get("http://a:8080")); // weight 3 → 3/4 of 8
        assertEquals(2, counts.get("http://b:8080")); // weight 1 → 1/4 of 8
    }

    @Test
    void perRouteIsolation_separateCounters() {
        Upstream a = createUpstream(1L, "http://a:8080", 1);
        Upstream b = createUpstream(2L, "http://b:8080", 1);
        List<Upstream> upstreams = List.of(a, b);

        // Route 1: advance counter
        assertEquals("http://a:8080", loadBalancer.choose(1L, upstreams).get().getUrl());

        // Route 2: should start fresh, not continue from route 1's counter
        assertEquals("http://a:8080", loadBalancer.choose(2L, upstreams).get().getUrl());

        // Route 1: should continue where it left off
        assertEquals("http://b:8080", loadBalancer.choose(1L, upstreams).get().getUrl());
    }

    @Test
    void zeroWeight_treatedAsOne() {
        Upstream a = createUpstream(1L, "http://a:8080", 0);
        Upstream b = createUpstream(2L, "http://b:8080", 1);
        List<Upstream> upstreams = List.of(a, b);

        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 4; i++) {
            Upstream chosen = loadBalancer.choose(1L, upstreams).orElseThrow();
            counts.merge(chosen.getUrl(), 1, Integer::sum);
        }

        assertEquals(2, counts.get("http://a:8080"));
        assertEquals(2, counts.get("http://b:8080"));
    }

    @Test
    void resetCounter_startsFromBeginning() {
        Upstream a = createUpstream(1L, "http://a:8080", 1);
        Upstream b = createUpstream(2L, "http://b:8080", 1);
        List<Upstream> upstreams = List.of(a, b);

        loadBalancer.choose(1L, upstreams); // a
        loadBalancer.choose(1L, upstreams); // b

        loadBalancer.resetCounter(1L);

        assertEquals("http://a:8080", loadBalancer.choose(1L, upstreams).get().getUrl());
    }

    @Test
    void threadSafety_noErrors() throws InterruptedException {
        Upstream a = createUpstream(1L, "http://a:8080", 1);
        Upstream b = createUpstream(2L, "http://b:8080", 1);
        Upstream c = createUpstream(3L, "http://c:8080", 1);
        List<Upstream> upstreams = List.of(a, b, c);

        int threadCount = 10;
        int requestsPerThread = 1000;
        ConcurrentHashMap<String, Integer> counts = new ConcurrentHashMap<>();
        CountDownLatch latch = new CountDownLatch(threadCount);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                for (int i = 0; i < requestsPerThread; i++) {
                    Upstream chosen = loadBalancer.choose(1L, upstreams).orElseThrow();
                    counts.merge(chosen.getUrl(), 1, Integer::sum);
                }
                latch.countDown();
            });
        }

        latch.await();
        executor.shutdown();

        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(threadCount * requestsPerThread, total);

        // Each upstream should get roughly 1/3 of requests
        for (String url : List.of("http://a:8080", "http://b:8080", "http://c:8080")) {
            int count = counts.getOrDefault(url, 0);
            assertTrue(count > 0, "Upstream " + url + " should have received some requests");
        }
    }

    private Upstream createUpstream(Long id, String url, int weight) {
        Upstream upstream = new Upstream();
        upstream.setId(id);
        upstream.setUrl(url);
        upstream.setWeight(weight);
        upstream.setEnabled(true);
        return upstream;
    }
}
