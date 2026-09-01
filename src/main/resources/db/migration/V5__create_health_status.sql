-- Add health_check_path to upstreams for self-contained health checking
ALTER TABLE upstreams ADD COLUMN health_check_path VARCHAR(255) DEFAULT '/health';

-- Create health_status table for tracking upstream health check results
CREATE TABLE health_status (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    upstream_id       BIGINT NOT NULL UNIQUE,
    healthy           BOOLEAN NOT NULL DEFAULT TRUE,
    last_check_at     TIMESTAMP,
    last_status_code  INT,
    last_response_ms  INT,
    consecutive_fails INT NOT NULL DEFAULT 0,
    consecutive_ok    INT NOT NULL DEFAULT 0,
    created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_health_status_upstream FOREIGN KEY (upstream_id)
        REFERENCES upstreams(id) ON DELETE CASCADE
);
