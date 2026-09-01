CREATE TABLE upstreams (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    route_id        BIGINT NOT NULL,
    url             VARCHAR(500) NOT NULL,
    weight          INT NOT NULL DEFAULT 1,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_upstreams_route FOREIGN KEY (route_id) REFERENCES routes(id) ON DELETE CASCADE
);
