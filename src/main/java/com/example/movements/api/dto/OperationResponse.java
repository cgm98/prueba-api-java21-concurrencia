package com.example.movements.api.dto;

import com.example.movements.domain.MovementResult;
import com.example.movements.domain.Operation;
import com.example.movements.domain.OperationStatus;
import com.example.movements.domain.OperationType;

import java.time.Instant;
import java.util.UUID;

public record OperationResponse(
        UUID operationId,
        OperationType type,
        OperationStatus status,
        UUID sourceAccountId,
        UUID destinationAccountId,
        String amount,
        String currency,
        String resultCode,
        Instant createdAt
) {
    public static OperationResponse from(MovementResult result) {
        return new OperationResponse(
                result.operationId(),
                result.type(),
                result.status(),
                result.sourceAccountId(),
                result.destinationAccountId(),
                result.amount().setScale(2).toPlainString(),
                result.currency(),
                result.resultCode(),
                result.createdAt()
        );
    }

    public static OperationResponse from(Operation operation) {
        return new OperationResponse(
                operation.id(),
                operation.type(),
                operation.status(),
                operation.sourceId(),
                operation.destinationId(),
                operation.amount().setScale(2).toPlainString(),
                operation.currency(),
                operation.resultCode(),
                operation.createdAt()
        );
    }
}
