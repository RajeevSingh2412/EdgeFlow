CREATE TABLE feature_flags (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    flag_key        VARCHAR(255) NOT NULL UNIQUE,
    description     VARCHAR(500),
    enabled         BOOLEAN NOT NULL DEFAULT FALSE,
    rollout_pct     INT NOT NULL DEFAULT 0,
    target_route_id BIGINT REFERENCES routes(id) ON DELETE SET NULL,
    strategy        VARCHAR(50) NOT NULL DEFAULT 'PERCENTAGE',
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
