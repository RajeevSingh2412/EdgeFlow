package com.edgeflow.domain.registry;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ServiceInstanceRepository extends JpaRepository<ServiceInstance, Long> {

    Optional<ServiceInstance> findByInstanceId(String instanceId);

    List<ServiceInstance> findAllByServiceName(String serviceName);

    List<ServiceInstance> findAllByStatus(String status);

    List<ServiceInstance> findAllByStatusAndLastHeartbeatAtBefore(String status, LocalDateTime threshold);
}