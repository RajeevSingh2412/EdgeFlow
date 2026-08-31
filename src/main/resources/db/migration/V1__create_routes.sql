CREATE TABLE routes (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    host            VARCHAR(255),
    path_prefix     VARCHAR(255) NOT NULL,
    description     VARCHAR(500),
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    strip_prefix    BOOLEAN NOT NULL DEFAULT FALSE,
    timeout_ms      INT NOT NULL DEFAULT 30000,
    retry_count     INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(host, path_prefix)
);
