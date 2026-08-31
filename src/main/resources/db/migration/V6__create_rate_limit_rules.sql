CREATE TABLE rate_limit_rules (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    name                VARCHAR(255) NOT NULL,
    route_id            BIGINT REFERENCES routes(id) ON DELETE CASCADE,
    key_type            VARCHAR(50) NOT NULL DEFAULT 'IP',
    max_tokens          INT NOT NULL DEFAULT 100,
    refill_rate         INT NOT NULL DEFAULT 10,
    refill_interval_ms  INT NOT NULL DEFAULT 1000,
    enabled             BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
