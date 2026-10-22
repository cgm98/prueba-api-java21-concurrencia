package com.example.movements.unit;

import com.example.movements.application.MovementCommand;
import com.example.movements.application.MovementService;
import com.example.movements.application.OperationExecutor;
import com.example.movements.domain.MovementResult;
import com.example.movements.domain.exception.AccountNotFoundException;
import com.example.movements.domain.exception.ConcurrencyRetryExhaustedException;
import com.example.movements.infrastructure.config.RetryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OperationExecutorTest {

    @Mock
    private MovementService movementService;

    private RetryProperties retryProperties;
    private OperationExecutor operationExecutor;

    @BeforeEach
    void setUp() {
        retryProperties = new RetryProperties();
        retryProperties.setMaxAttempts(3);
        retryProperties.setInitialBackoffMs(5);
        retryProperties.setMaxBackoffMs(20);
        retryProperties.setMultiplier(1.5);
        operationExecutor = new OperationExecutor(movementService, retryProperties);
    }

    @Test
    void shouldExecuteSuccessfullyOnFirstAttempt() {
        UUID accountId = UUID.randomUUID();
        MovementCommand command = MovementCommand.withdrawal(accountId, new BigDecimal("10.00"), "PEN");
        MovementResult expected = MovementResult.applied(
                UUID.randomUUID(), command.type(), accountId, null, command.amount(), command.currency(), Instant.now()
        );

        when(movementService.executeAttempt(eq("client-1"), eq("key-1"), eq(command), any()))
                .thenReturn(expected);

        MovementResult actual = operationExecutor.execute("client-1", "key-1", command);

        assertThat(actual).isEqualTo(expected);
        verify(movementService, times(1)).executeAttempt(eq("client-1"), eq("key-1"), eq(command), any());
    }

    @Test
    void shouldRetryOnTransientLockExceptionAndSucceed() {
        UUID accountId = UUID.randomUUID();
        MovementCommand command = MovementCommand.withdrawal(accountId, new BigDecimal("10.00"), "PEN");
        MovementResult expected = MovementResult.applied(
                UUID.randomUUID(), command.type(), accountId, null, command.amount(), command.currency(), Instant.now()
        );

        when(movementService.executeAttempt(eq("client-1"), eq("key-1"), eq(command), any()))
                .thenThrow(new DeadlockLoserDataAccessException("Deadlock detected", null))
                .thenReturn(expected);

        MovementResult actual = operationExecutor.execute("client-1", "key-1", command);

        assertThat(actual).isEqualTo(expected);
        verify(movementService, times(2)).executeAttempt(eq("client-1"), eq("key-1"), eq(command), any());
    }

    @Test
    void shouldThrowExhaustedWhenMaxAttemptsReached() {
        UUID accountId = UUID.randomUUID();
        MovementCommand command = MovementCommand.withdrawal(accountId, new BigDecimal("10.00"), "PEN");

        when(movementService.executeAttempt(eq("client-1"), eq("key-1"), eq(command), any()))
                .thenThrow(new CannotAcquireLockException("Lock wait timeout exceeded", null));

        assertThatThrownBy(() -> operationExecutor.execute("client-1", "key-1", command))
                .isInstanceOf(ConcurrencyRetryExhaustedException.class)
                .hasMessageContaining("Exceeded maximum concurrency retry attempts");

        verify(movementService, times(3)).executeAttempt(eq("client-1"), eq("key-1"), eq(command), any());
    }

    @Test
    void shouldNotRetryOnBusinessExceptions() {
        UUID accountId = UUID.randomUUID();
        MovementCommand command = MovementCommand.withdrawal(accountId, new BigDecimal("10.00"), "PEN");

        when(movementService.executeAttempt(eq("client-1"), eq("key-1"), eq(command), any()))
                .thenThrow(new AccountNotFoundException(accountId));

        assertThatThrownBy(() -> operationExecutor.execute("client-1", "key-1", command))
                .isInstanceOf(AccountNotFoundException.class);

        verify(movementService, times(1)).executeAttempt(eq("client-1"), eq("key-1"), eq(command), any());
    }
}
