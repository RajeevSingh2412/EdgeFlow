package com.edgeflow.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class ProxyMetrics {

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, Timer> timers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();

    public ProxyMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    public void recordRequest(Timer.Sample sample, String route, String method, int status) {
        String statusGroup = status / 100 + "xx";
        String timerKey = route + ":" + method;

        Timer timer = timers.computeIfAbsent(timerKey, k ->
                Timer.builder("edgeflow_request_duration_seconds")
                        .tag("route", route)
                        .tag("method", method)
                        .register(registry));
        sample.stop(timer);

        Counter.builder("edgeflow_requests_total")
                .tag("route", route)
                .tag("method", method)
                .tag("status", String.valueOf(status))
                .register(registry)
                .increment();
    }

    public void recordRateLimitRejection(String route, String keyType) {
        Counter.builder("edgeflow_rate_limit_rejected_total")
                .tag("route", route)
                .tag("key_type", keyType)
                .register(registry)
                .increment();
    }

    public void recordUpstreamError(String route, String upstream, String errorType) {
        Counter.builder("edgeflow_upstream_errors_total")
                .tag("route", route)
                .tag("upstream", upstream)
                .tag("error_type", errorType)
                .register(registry)
                .increment();
    }

    public void recordHealthCheck(String upstream, boolean healthy) {
        Counter.builder("edgeflow_health_checks_total")
                .tag("upstream", upstream)
                .tag("result", healthy ? "healthy" : "unhealthy")
                .register(registry)
                .increment();
    }

    public void recordFlagEvaluation(String flagKey, boolean enabled) {
        Counter.builder("edgeflow_flag_evaluations_total")
                .tag("flag_key", flagKey)
                .tag("result", enabled ? "enabled" : "disabled")
                .register(registry)
                .increment();
    }
}
