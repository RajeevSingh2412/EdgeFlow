package com.edgeflow.domain.route;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UpstreamRepository extends JpaRepository<Upstream, Long> {

    List<Upstream> findAllByRouteId(Long routeId);
}
