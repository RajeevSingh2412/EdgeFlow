package com.edgeflow.healthcheck;

import com.edgeflow.domain.health.HealthStatus;
import com.edgeflow.domain.health.HealthStatusRepository;
import com.edgeflow.domain.route.Upstream;
import com.edgeflow.domain.route.UpstreamRepository;
import com.edgeflow.routing.RouteResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HealthStatusManagerTest {

    @Mock
    private HealthStatusRepository healthStatusRepository;

    @Mock
    private UpstreamRepository upstreamRepository;

    @Mock
    private RouteResolver routeResolver;

    private HealthStatusManager manager;

    @BeforeEach
    void setUp() {
        manager = new HealthStatusManager(healthStatusRepository, upstreamRepository, routeResolver);
        ReflectionTestUtils.setField(manager, "failureThreshold", 3);
        ReflectionTestUtils.setField(manager, "successThreshold", 2);
    }

    @Test
    void successfulCheck_incrementsConsecutiveOk() {
        Upstream upstream = createUpstream(1L, true);
        HealthStatus status = createHealthStatus(upstream, true, 0, 0);

        when(upstreamRepository.findById(1L)).thenReturn(Optional.of(upstream));
        when(healthStatusRepository.findByUpstreamId(1L)).thenReturn(Optional.of(status));
        when(healthStatusRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        manager.processResult(new HealthCheckResult(1L, true, 200, 5));

        assertEquals(1, status.getConsecutiveOk());
        assertEquals(0, status.getConsecutiveFails());
        assertTrue(status.isHealthy());
    }

    @Test
    void failedCheck_incrementsConsecutiveFails() {
        Upstream upstream = createUpstream(1L, true);
        HealthStatus status = createHealthStatus(upstream, true, 0, 0);

        when(upstreamRepository.findById(1L)).thenReturn(Optional.of(upstream));
        when(healthStatusRepository.findByUpstreamId(1L)).thenReturn(Optional.of(status));
        when(healthStatusRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        manager.processResult(new HealthCheckResult(1L, false, 500, 10));

        assertEquals(0, status.getConsecutiveOk());
        assertEquals(1, status.getConsecutiveFails());
        assertTrue(status.isHealthy()); // not yet at threshold
    }

    @Test
    void threeConsecutiveFailures_marksUnhealthy() {
        Upstream upstream = createUpstream(1L, true);
        HealthStatus status = createHealthStatus(upstream, true, 2, 0);

        when(upstreamRepository.findById(1L)).thenReturn(Optional.of(upstream));
        when(healthStatusRepository.findByUpstreamId(1L)).thenReturn(Optional.of(status));
        when(healthStatusRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        manager.processResult(new HealthCheckResult(1L, false, 0, 5000));

        assertFalse(status.isHealthy());
        assertFalse(upstream.isEnabled());
        verify(upstreamRepository).save(upstream);
        verify(routeResolver).invalidateCache();
    }

    @Test
    void twoConsecutiveSuccesses_recoversFromUnhealthy() {
        Upstream upstream = createUpstream(1L, false);
        HealthStatus status = createHealthStatus(upstream, false, 0, 1);

        when(upstreamRepository.findById(1L)).thenReturn(Optional.of(upstream));
        when(healthStatusRepository.findByUpstreamId(1L)).thenReturn(Optional.of(status));
        when(healthStatusRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        manager.processResult(new HealthCheckResult(1L, true, 200, 3));

        assertTrue(status.isHealthy());
        assertTrue(upstream.isEnabled());
        verify(upstreamRepository).save(upstream);
        verify(routeResolver).invalidateCache();
    }

    @Test
    void failureResetsConsecutiveOk() {
        Upstream upstream = createUpstream(1L, false);
        HealthStatus status = createHealthStatus(upstream, false, 0, 1);

        when(upstreamRepository.findById(1L)).thenReturn(Optional.of(upstream));
        when(healthStatusRepository.findByUpstreamId(1L)).thenReturn(Optional.of(status));
        when(healthStatusRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        manager.processResult(new HealthCheckResult(1L, false, 500, 10));

        assertEquals(0, status.getConsecutiveOk());
        assertEquals(1, status.getConsecutiveFails());
        assertFalse(status.isHealthy()); // still unhealthy
    }

    @Test
    void successResetsConsecutiveFails() {
        Upstream upstream = createUpstream(1L, true);
        HealthStatus status = createHealthStatus(upstream, true, 2, 0);

        when(upstreamRepository.findById(1L)).thenReturn(Optional.of(upstream));
        when(healthStatusRepository.findByUpstreamId(1L)).thenReturn(Optional.of(status));
        when(healthStatusRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        manager.processResult(new HealthCheckResult(1L, true, 200, 5));

        assertEquals(0, status.getConsecutiveFails());
        assertEquals(1, status.getConsecutiveOk());
        assertTrue(status.isHealthy()); // remains healthy
    }

    @Test
    void belowThreshold_noStateChange() {
        Upstream upstream = createUpstream(1L, true);
        HealthStatus status = createHealthStatus(upstream, true, 1, 0);

        when(upstreamRepository.findById(1L)).thenReturn(Optional.of(upstream));
        when(healthStatusRepository.findByUpstreamId(1L)).thenReturn(Optional.of(status));
        when(healthStatusRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        manager.processResult(new HealthCheckResult(1L, false, 500, 10));

        assertTrue(status.isHealthy()); // 2 fails, threshold is 3
        assertTrue(upstream.isEnabled());
        verify(routeResolver, never()).invalidateCache();
    }

    @Test
    void unknownUpstream_ignored() {
        when(upstreamRepository.findById(999L)).thenReturn(Optional.empty());

        manager.processResult(new HealthCheckResult(999L, false, 0, 0));

        verify(healthStatusRepository, never()).save(any());
    }

    @Test
    void noExistingStatus_createsNew() {
        Upstream upstream = createUpstream(1L, true);

        when(upstreamRepository.findById(1L)).thenReturn(Optional.of(upstream));
        when(healthStatusRepository.findByUpstreamId(1L)).thenReturn(Optional.empty());
        when(healthStatusRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        manager.processResult(new HealthCheckResult(1L, true, 200, 5));

        ArgumentCaptor<HealthStatus> captor = ArgumentCaptor.forClass(HealthStatus.class);
        verify(healthStatusRepository).save(captor.capture());

        HealthStatus saved = captor.getValue();
        assertEquals(upstream, saved.getUpstream());
        assertTrue(saved.isHealthy());
        assertEquals(1, saved.getConsecutiveOk());
    }

    @Test
    void alreadyHealthy_successDoesNotInvalidateCache() {
        Upstream upstream = createUpstream(1L, true);
        HealthStatus status = createHealthStatus(upstream, true, 0, 0);

        when(upstreamRepository.findById(1L)).thenReturn(Optional.of(upstream));
        when(healthStatusRepository.findByUpstreamId(1L)).thenReturn(Optional.of(status));
        when(healthStatusRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        manager.processResult(new HealthCheckResult(1L, true, 200, 5));

        verify(routeResolver, never()).invalidateCache();
    }

    @Test
    void statusCodeAndResponseTimeRecorded() {
        Upstream upstream = createUpstream(1L, true);
        HealthStatus status = createHealthStatus(upstream, true, 0, 0);

        when(upstreamRepository.findById(1L)).thenReturn(Optional.of(upstream));
        when(healthStatusRepository.findByUpstreamId(1L)).thenReturn(Optional.of(status));
        when(healthStatusRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        manager.processResult(new HealthCheckResult(1L, true, 200, 42));

        assertEquals(200, status.getLastStatusCode());
        assertEquals(42, status.getLastResponseMs());
        assertNotNull(status.getLastCheckAt());
    }

    private Upstream createUpstream(Long id, boolean enabled) {
        Upstream upstream = new Upstream();
        upstream.setId(id);
        upstream.setUrl("http://test:8080");
        upstream.setEnabled(enabled);
        upstream.setWeight(1);
        return upstream;
    }

    private HealthStatus createHealthStatus(Upstream upstream, boolean healthy,
                                             int consecutiveFails, int consecutiveOk) {
        HealthStatus hs = new HealthStatus();
        hs.setUpstream(upstream);
        hs.setHealthy(healthy);
        hs.setConsecutiveFails(consecutiveFails);
        hs.setConsecutiveOk(consecutiveOk);
        return hs;
    }
}
