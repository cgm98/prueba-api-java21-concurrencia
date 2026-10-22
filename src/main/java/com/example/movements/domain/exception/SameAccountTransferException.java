package com.example.movements.domain.exception;

import java.util.UUID;

public class SameAccountTransferException extends RuntimeException {
    private final UUID accountId;

    public SameAccountTransferException(UUID accountId) {
        super("Source and destination accounts must be different: " + accountId);
        this.accountId = accountId;
    }

    public UUID getAccountId() {
        return accountId;
    }
}
