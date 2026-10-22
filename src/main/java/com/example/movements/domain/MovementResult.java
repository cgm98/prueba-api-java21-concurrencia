package com.example.movements.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record MovementResult(
        UUID operationId,
        OperationType type,
        OperationStatus status,
        UUID sourceAccountId,
        UUID destinationAccountId,
        BigDecimal amount,
        String currency,
        String resultCode,
        int httpStatus,
        Instant createdAt,
        boolean replayed
) {
    public static MovementResult applied(
            UUID operationId,
            OperationType type,
            UUID sourceAccountId,
            UUID destinationAccountId,
            BigDecimal amount,
            String currency,
            Instant createdAt
    ) {
        return new MovementResult(
                operationId,
                type,
                OperationStatus.APPLIED,
                sourceAccountId,
                destinationAccountId,
                amount,
                currency,
                "OK",
                201,
                createdAt,
                false
        );
    }

    public static MovementResult rejected(
            UUID operationId,
            OperationType type,
            UUID sourceAccountId,
            UUID destinationAccountId,
            BigDecimal amount,
            String currency,
            String resultCode,
            int httpStatus,
            Instant createdAt
    ) {
        return new MovementResult(
                operationId,
                type,
                OperationStatus.REJECTED,
                sourceAccountId,
                destinationAccountId,
                amount,
                currency,
                resultCode,
                httpStatus,
                createdAt,
                false
        );
    }

    public MovementResult withReplayed(boolean replayed) {
        return new MovementResult(
                this.operationId,
                this.type,
                this.status,
                this.sourceAccountId,
                this.destinationAccountId,
                this.amount,
                this.currency,
                this.resultCode,
                this.httpStatus,
                this.createdAt,
                replayed
        );
    }
}
