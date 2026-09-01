package com.edgeflow.healthcheck;

import com.edgeflow.domain.route.Upstream;
import com.edgeflow.domain.route.UpstreamRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

@Service
public class HealthChecker {

    private static final Logger log = LoggerFactory.getLogger(HealthChecker.class);

    private final UpstreamRepository upstreamRepository;
    private final HealthStatusManager healthStatusManager;
    private final RestClient healthRestClient;

    @Value("${edgeflow.health.check-path-default:/health}")
    private String defaultCheckPath;

    @Lazy
    private final HealthChecker self;

    public HealthChecker(UpstreamRepository upstreamRepository,
                         HealthStatusManager healthStatusManager,
                         @Qualifier("healthCheckRestClient") RestClient healthRestClient,
                         @Lazy HealthChecker self) {
        this.upstreamRepository = upstreamRepository;
        this.healthStatusManager = healthStatusManager;
        this.healthRestClient = healthRestClient;
        this.self = self;
    }

    @Scheduled(fixedDelayString = "${edgeflow.health.check-interval-ms:15000}",
               initialDelayString = "${edgeflow.health.initial-delay-ms:5000}")
    public void checkAll() {
        List<Upstream> allUpstreams = upstreamRepository.findAll();
        log.debug("Running health checks on {} upstreams", allUpstreams.size());

        for (Upstream upstream : allUpstreams) {
            self.checkSingleAsync(upstream);
        }
    }

    @Async("healthCheckExecutor")
    public void checkSingleAsync(Upstream upstream) {
        HealthCheckResult result = performCheck(upstream);
        healthStatusManager.processResult(result);
    }

    public void checkAllImmediate() {
        List<Upstream> allUpstreams = upstreamRepository.findAll();
        for (Upstream upstream : allUpstreams) {
            HealthCheckResult result = performCheck(upstream);
            healthStatusManager.processResult(result);
        }
    }

    private HealthCheckResult performCheck(Upstream upstream) {
        String checkPath = upstream.getHealthCheckPath() != null
                ? upstream.getHealthCheckPath() : defaultCheckPath;
        String url = upstream.getUrl() + checkPath;

        long start = System.currentTimeMillis();
        try {
            ResponseEntity<Void> response = healthRestClient.get()
                    .uri(url)
                    .retrieve()
                    .toBodilessEntity();
            int elapsed = (int) (System.currentTimeMillis() - start);
            boolean success = response.getStatusCode().is2xxSuccessful();
            return new HealthCheckResult(upstream.getId(), success,
                    response.getStatusCode().value(), elapsed);
        } catch (Exception e) {
            int elapsed = (int) (System.currentTimeMillis() - start);
            log.debug("Health check failed for {}: {}", url, e.getMessage());
            return new HealthCheckResult(upstream.getId(), false, 0, elapsed);
        }
    }
}
