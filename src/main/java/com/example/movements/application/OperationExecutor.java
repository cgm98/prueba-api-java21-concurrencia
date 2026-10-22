package com.example.movements.application;

import com.example.movements.domain.MovementResult;
import com.example.movements.domain.exception.ConcurrencyRetryExhaustedException;
import com.example.movements.infrastructure.config.RetryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class OperationExecutor {

    private static final Logger log = LoggerFactory.getLogger(OperationExecutor.class);

    private final MovementService movementService;
    private final RetryProperties retryProperties;

    public OperationExecutor(MovementService movementService, RetryProperties retryProperties) {
        this.movementService = movementService;
        this.retryProperties = retryProperties;
    }

    public MovementResult execute(String clientId, String idempotencyKey, MovementCommand command) {
        String requestHash = RequestFingerprint.computeHash(command);
        int maxAttempts = Math.max(1, retryProperties.getMaxAttempts());
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return movementService.executeAttempt(clientId, idempotencyKey, command, requestHash);
            } catch (Exception ex) {
                lastException = ex;
                if (!isTransientConcurrencyException(ex)) {
                    throw ex;
                }

                if (attempt == maxAttempts) {
                    log.warn("Exhausted {} attempts for idempotencyKey={} due to transient lock contention: {}",
                            maxAttempts, idempotencyKey, ex.getMessage());
                    break;
                }

                long backoff = calculateBackoff(attempt);
                log.info("Transient lock conflict on attempt {}/{} for idempotencyKey={}, retrying in {}ms: {}",
                        attempt, maxAttempts, idempotencyKey, backoff, ex.getMessage());

                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new ConcurrencyRetryExhaustedException("Interrupted during lock retry backoff", ie);
                }
            }
        }

        throw new ConcurrencyRetryExhaustedException("Exceeded maximum concurrency retry attempts (" + maxAttempts + ")", lastException);
    }

    private boolean isTransientConcurrencyException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof CannotAcquireLockException ||
                current instanceof DeadlockLoserDataAccessException ||
                current instanceof PessimisticLockingFailureException ||
                current instanceof QueryTimeoutException) {
                return true;
            }

            if (current instanceof SQLException sqlEx) {
                String sqlState = sqlEx.getSQLState();
                int vendorCode = sqlEx.getErrorCode();

                // MySQL: 1213 = ER_LOCK_DEADLOCK, 1205 = ER_LOCK_WAIT_TIMEOUT
                if (vendorCode == 1213 || vendorCode == 1205) {
                    return true;
                }
                // Standard SQL states for serialization failure/deadlock
                if ("40001".equals(sqlState) || "40P01".equals(sqlState)) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private long calculateBackoff(int attempt) {
        double multiplier = Math.pow(retryProperties.getMultiplier(), attempt - 1);
        long baseBackoff = (long) (retryProperties.getInitialBackoffMs() * multiplier);
        long cappedBackoff = Math.min(baseBackoff, retryProperties.getMaxBackoffMs());
        // Add full jitter (0 to cappedBackoff)
        return ThreadLocalRandom.current().nextLong(cappedBackoff / 2, cappedBackoff + 1);
    }
}
