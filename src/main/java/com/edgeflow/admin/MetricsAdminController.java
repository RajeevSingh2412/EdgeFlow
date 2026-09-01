package com.edgeflow.admin;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/admin/api/v1/metrics")
public class MetricsAdminController {

    private final MeterRegistry registry;

    public MetricsAdminController(MeterRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        Map<String, Object> summary = new LinkedHashMap<>();

        // Total requests
        double totalRequests = registry.find("edgeflow_requests_total")
                .counters().stream()
                .mapToDouble(Counter::count)
                .sum();
        summary.put("totalRequests", (long) totalRequests);

        // Rate limit rejections
        double rateLimitRejections = registry.find("edgeflow_rate_limit_rejected_total")
                .counters().stream()
                .mapToDouble(Counter::count)
                .sum();
        summary.put("rateLimitRejections", (long) rateLimitRejections);

        // Upstream errors
        double upstreamErrors = registry.find("edgeflow_upstream_errors_total")
                .counters().stream()
                .mapToDouble(Counter::count)
                .sum();
        summary.put("upstreamErrors", (long) upstreamErrors);

        // Latency
        Timer requestTimer = registry.find("edgeflow_request_duration_seconds")
                .timer();
        if (requestTimer != null) {
            Map<String, Object> latency = new LinkedHashMap<>();
            latency.put("count", requestTimer.count());
            latency.put("total_ms", requestTimer.totalTime(TimeUnit.MILLISECONDS));
            latency.put("mean_ms", requestTimer.mean(TimeUnit.MILLISECONDS));
            latency.put("max_ms", requestTimer.max(TimeUnit.MILLISECONDS));
            summary.put("latency", latency);
        }

        return summary;
    }
}
