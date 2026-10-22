CREATE TABLE ledger_entries (
    id BIGINT NOT NULL AUTO_INCREMENT,
    operation_id VARCHAR(36) NOT NULL,
    account_id VARCHAR(36) NOT NULL,
    leg VARCHAR(20) NOT NULL,
    signed_amount DECIMAL(19, 2) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_operation_leg (operation_id, leg),
    CONSTRAINT fk_ledger_operation FOREIGN KEY (operation_id) REFERENCES operations (id),
    CONSTRAINT fk_ledger_account FOREIGN KEY (account_id) REFERENCES accounts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
