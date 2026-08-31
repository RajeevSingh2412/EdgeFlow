package com.edgeflow.domain.ratelimit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RateLimitRuleRepository extends JpaRepository<RateLimitRule, Long> {

    List<RateLimitRule> findAllByEnabledTrue();

    List<RateLimitRule> findAllByRouteIdAndEnabledTrue(Long routeId);

    List<RateLimitRule> findAllByRouteIdIsNullAndEnabledTrue();
}
