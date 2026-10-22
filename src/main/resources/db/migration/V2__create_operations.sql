CREATE TABLE operations (
    id VARCHAR(36) NOT NULL,
    client_id VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    type VARCHAR(20) NOT NULL,
    source_id VARCHAR(36) NULL,
    destination_id VARCHAR(36) NULL,
    amount DECIMAL(19, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    status VARCHAR(20) NOT NULL,
    result_code VARCHAR(50) NOT NULL,
    http_status INT NOT NULL,
    response_payload TEXT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_client_idempotency (client_id, idempotency_key),
    KEY idx_operations_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
