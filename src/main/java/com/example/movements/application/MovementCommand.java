package com.example.movements.application;

import com.example.movements.domain.OperationType;
import com.example.movements.domain.exception.InvalidAmountException;
import com.example.movements.domain.exception.SameAccountTransferException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

public record MovementCommand(
        OperationType type,
        UUID sourceAccountId,
        UUID destinationAccountId,
        BigDecimal amount,
        String currency
) {
    public MovementCommand {
        Objects.requireNonNull(type, "Operation type must not be null");
        Objects.requireNonNull(sourceAccountId, "Source account ID must not be null");
        Objects.requireNonNull(amount, "Amount must not be null");
        Objects.requireNonNull(currency, "Currency must not be null");

        if (!"PEN".equalsIgnoreCase(currency)) {
            throw new IllegalArgumentException("Unsupported currency: " + currency + ". Only 'PEN' is supported.");
        }

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidAmountException("Amount must be strictly positive: " + amount);
        }

        if (amount.scale() > 2) {
            throw new InvalidAmountException("Amount cannot have more than 2 decimal places: " + amount);
        }

        if (amount.compareTo(new BigDecimal("99999999999999999.99")) > 0) {
            throw new InvalidAmountException("Amount exceeds maximum limit allowed");
        }

        amount = amount.setScale(2, RoundingMode.UNNECESSARY);
        currency = currency.toUpperCase();

        if (type == OperationType.WITHDRAWAL) {
            if (destinationAccountId != null) {
                throw new IllegalArgumentException("Destination account ID must be null for withdrawal");
            }
        } else if (type == OperationType.TRANSFER) {
            if (destinationAccountId == null) {
                throw new IllegalArgumentException("Destination account ID must not be null for transfer");
            }
            if (sourceAccountId.equals(destinationAccountId)) {
                throw new SameAccountTransferException(sourceAccountId);
            }
        }
    }

    public static MovementCommand withdrawal(UUID sourceAccountId, BigDecimal amount, String currency) {
        return new MovementCommand(OperationType.WITHDRAWAL, sourceAccountId, null, amount, currency);
    }

    public static MovementCommand transfer(UUID sourceAccountId, UUID destinationAccountId, BigDecimal amount, String currency) {
        return new MovementCommand(OperationType.TRANSFER, sourceAccountId, destinationAccountId, amount, currency);
    }
}
