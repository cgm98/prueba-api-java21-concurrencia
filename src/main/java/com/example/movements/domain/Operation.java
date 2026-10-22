package com.example.movements.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Operation(
        UUID id,
        String clientId,
        String idempotencyKey,
        String requestHash,
        OperationType type,
        UUID sourceId,
        UUID destinationId,
        BigDecimal amount,
        String currency,
        Instant createdAt,
        OperationStatus status,
        String resultCode,
        int httpStatus,
        String responsePayload
) {
}
