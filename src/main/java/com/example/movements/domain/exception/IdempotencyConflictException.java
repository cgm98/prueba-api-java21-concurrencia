package com.example.movements.domain.exception;

import com.example.movements.domain.OperationStatus;

import java.util.UUID;

public class IdempotencyConflictException extends RuntimeException {
    private final String idempotencyKey;
    private final UUID existingOperationId;
    private final OperationStatus existingStatus;
    private final String originalHash;
    private final String incomingHash;

    public IdempotencyConflictException(String idempotencyKey) {
        this(idempotencyKey, null, null, null, null);
    }

    public IdempotencyConflictException(
            String idempotencyKey,
            UUID existingOperationId,
            OperationStatus existingStatus,
            String originalHash,
            String incomingHash
    ) {
        super(String.format(
                "Idempotency key reused with a different request payload: '%s'. Already processed (operationId: %s, status: %s). Original hash: %s, Incoming hash: %s",
                idempotencyKey, existingOperationId, existingStatus, originalHash, incomingHash
        ));
        this.idempotencyKey = idempotencyKey;
        this.existingOperationId = existingOperationId;
        this.existingStatus = existingStatus;
        this.originalHash = originalHash;
        this.incomingHash = incomingHash;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public UUID getExistingOperationId() {
        return existingOperationId;
    }

    public OperationStatus getExistingStatus() {
        return existingStatus;
    }

    public String getOriginalHash() {
        return originalHash;
    }

    public String getIncomingHash() {
        return incomingHash;
    }
}
