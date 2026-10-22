package com.example.movements.domain.exception;

public class ConcurrencyRetryExhaustedException extends RuntimeException {
    public ConcurrencyRetryExhaustedException(String message, Throwable cause) {
        super(message, cause);
    }
}
