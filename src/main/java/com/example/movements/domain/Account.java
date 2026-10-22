package com.example.movements.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class Account {
    private final UUID id;
    private final String currency;
    private final BigDecimal openingBalance;
    private BigDecimal balance;
    private final Instant createdAt;

    public Account(UUID id, String currency, BigDecimal openingBalance, BigDecimal balance, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.currency = Objects.requireNonNull(currency, "currency must not be null");
        this.openingBalance = Objects.requireNonNull(openingBalance, "openingBalance must not be null");
        this.balance = Objects.requireNonNull(balance, "balance must not be null");
        this.createdAt = createdAt != null ? createdAt : Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getOpeningBalance() {
        return openingBalance;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean hasSufficientBalance(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount must not be null");
        return this.balance.compareTo(amount) >= 0;
    }

    public void debit(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount must not be null");
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Debit amount must be strictly positive");
        }
        if (!hasSufficientBalance(amount)) {
            throw new IllegalStateException("Insufficient funds for debit");
        }
        this.balance = this.balance.subtract(amount);
    }

    public void credit(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount must not be null");
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Credit amount must be strictly positive");
        }
        this.balance = this.balance.add(amount);
    }
}
