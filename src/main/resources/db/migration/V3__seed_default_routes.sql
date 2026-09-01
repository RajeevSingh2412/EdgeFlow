-- Seed default routes pointing to mock backends
-- These match the original application.yml routes

INSERT INTO routes (host, path_prefix, description, enabled) VALUES
    (NULL, '/api/users', 'User service (mock)', TRUE),
    (NULL, '/api/orders', 'Order service (mock)', TRUE),
    (NULL, '/api/payments', 'Payment service (mock)', TRUE);

INSERT INTO upstreams (route_id, url, weight, enabled) VALUES
    (1, 'http://localhost:8080/mock/users', 1, TRUE),
    (2, 'http://localhost:8080/mock/orders', 1, TRUE),
    (3, 'http://localhost:8080/mock/payments', 1, TRUE);
