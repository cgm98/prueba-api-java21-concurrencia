package com.example.movements.infrastructure.jdbc;

import com.example.movements.domain.Operation;
import java.util.UUID;

public sealed interface ReservationResult permits ReservationResult.Owned, ReservationResult.Existing {
    record Owned(UUID operationId) implements ReservationResult {}
    record Existing(Operation operation) implements ReservationResult {}

    static ReservationResult owned(UUID operationId) {
        return new Owned(operationId);
    }

    static ReservationResult existing(Operation operation) {
        return new Existing(operation);
    }
}
