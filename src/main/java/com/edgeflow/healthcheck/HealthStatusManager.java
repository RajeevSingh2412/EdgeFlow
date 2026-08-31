package com.edgeflow.healthcheck;

import com.edgeflow.domain.health.HealthStatus;
import com.edgeflow.domain.health.HealthStatusRepository;
import com.edgeflow.domain.route.Upstream;
import com.edgeflow.domain.route.UpstreamRepository;
import com.edgeflow.routing.RouteResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class HealthStatusManager {

    private static final Logger log = LoggerFactory.getLogger(HealthStatusManager.class);

    private final HealthStatusRepository healthStatusRepository;
    private final UpstreamRepository upstreamRepository;
    private final RouteResolver routeResolver;

    @Value("${edgeflow.health.failure-threshold:3}")
    private int failureThreshold;

    @Value("${edgeflow.health.success-threshold:2}")
    private int successThreshold;

    public HealthStatusManager(HealthStatusRepository healthStatusRepository,
                               UpstreamRepository upstreamRepository,
                               RouteResolver routeResolver) {
        this.healthStatusRepository = healthStatusRepository;
        this.upstreamRepository = upstreamRepository;
        this.routeResolver = routeResolver;
    }

    @Transactional
    public void processResult(HealthCheckResult result) {
        Upstream upstream = upstreamRepository.findById(result.upstreamId()).orElse(null);
        if (upstream == null) {
            return;
        }

        HealthStatus status = healthStatusRepository.findByUpstreamId(result.upstreamId())
                .orElseGet(() -> createInitialStatus(upstream));

        status.setLastCheckAt(LocalDateTime.now());
        status.setLastStatusCode(result.statusCode());
        status.setLastResponseMs(result.responseTimeMs());

        boolean previouslyHealthy = status.isHealthy();

        if (result.success()) {
            status.setConsecutiveOk(status.getConsecutiveOk() + 1);
            status.setConsecutiveFails(0);

            if (!previouslyHealthy && status.getConsecutiveOk() >= successThreshold) {
                status.setHealthy(true);
                upstream.setEnabled(true);
                upstreamRepository.save(upstream);
                routeResolver.invalidateCache();
                log.info("Upstream {} recovered after {} consecutive successes",
                        upstream.getUrl(), successThreshold);
            }
        } else {
            status.setConsecutiveFails(status.getConsecutiveFails() + 1);
            status.setConsecutiveOk(0);

            if (previouslyHealthy && status.getConsecutiveFails() >= failureThreshold) {
                status.setHealthy(false);
                upstream.setEnabled(false);
                upstreamRepository.save(upstream);
                routeResolver.invalidateCache();
                log.warn("Upstream {} marked unhealthy after {} consecutive failures",
                        upstream.getUrl(), failureThreshold);
            }
        }

        healthStatusRepository.save(status);
    }

    private HealthStatus createInitialStatus(Upstream upstream) {
        HealthStatus hs = new HealthStatus();
        hs.setUpstream(upstream);
        hs.setHealthy(true);
        return hs;
    }
}
