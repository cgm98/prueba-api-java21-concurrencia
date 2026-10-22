CREATE TABLE accounts (
    id VARCHAR(36) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    opening_balance DECIMAL(19, 2) NOT NULL,
    balance DECIMAL(19, 2) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT chk_accounts_balance_positive CHECK (balance >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
