package com.edgeflow.domain.health;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface HealthStatusRepository extends JpaRepository<HealthStatus, Long> {

    Optional<HealthStatus> findByUpstreamId(Long upstreamId);

    List<HealthStatus> findAllByUpstreamRouteId(Long routeId);

    List<HealthStatus> findAllByHealthyFalse();
}
