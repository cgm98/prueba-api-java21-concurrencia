package com.example.movements.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LedgerEntry(
        Long id,
        UUID operationId,
        UUID accountId,
        String leg,
        BigDecimal signedAmount,
        Instant createdAt
) {
}
