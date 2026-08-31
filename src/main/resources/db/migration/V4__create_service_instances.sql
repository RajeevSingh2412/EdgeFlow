CREATE TABLE service_instances (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    service_name      VARCHAR(255) NOT NULL,
    instance_id       VARCHAR(255) NOT NULL UNIQUE,
    url               VARCHAR(500) NOT NULL,
    status            VARCHAR(50) NOT NULL DEFAULT 'UP',
    health_check_path VARCHAR(255) DEFAULT '/health',
    route_id          BIGINT REFERENCES routes(id) ON DELETE SET NULL,
    upstream_id       BIGINT REFERENCES upstreams(id) ON DELETE SET NULL,
    last_heartbeat_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    registered_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    metadata          VARCHAR(2000)
);