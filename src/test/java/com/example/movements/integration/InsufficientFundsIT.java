package com.example.movements.integration;

import com.example.movements.application.MovementCommand;
import com.example.movements.application.OperationExecutor;
import com.example.movements.domain.Account;
import com.example.movements.domain.MovementResult;
import com.example.movements.domain.OperationStatus;
import com.example.movements.support.AbstractMySQLIntegrationTest;
import com.example.movements.support.AccountFixtures;
import com.example.movements.support.ConcurrentRunner;
import com.example.movements.support.DatabaseAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InsufficientFundsIT extends AbstractMySQLIntegrationTest {

    @Autowired
    private OperationExecutor operationExecutor;

    @Autowired
    private AccountFixtures accountFixtures;

    @Autowired
    private DatabaseAssertions databaseAssertions;

    @Test
    @DisplayName("Sobredemanda: 3,000 solicitudes de S/ 5 sobre saldo S/ 10,000 -> 2,000 aplicadas, 1,000 rechazadas, saldo cero, NUNCA negativo")
    void shouldHandleOverdraftWithoutNegativeBalance() throws InterruptedException {
        BigDecimal initialBalance = new BigDecimal("10000.00");
        Account account = accountFixtures.createAccount(initialBalance);
        UUID accountId = account.getId();

        int totalRequests = 3000;
        BigDecimal withdrawalAmount = new BigDecimal("5.00");

        ConcurrentRunner.ExecutionSummary<MovementResult> summary = ConcurrentRunner.run(totalRequests, () -> {
            String idempotencyKey = UUID.randomUUID().toString();
            MovementCommand command = MovementCommand.withdrawal(accountId, withdrawalAmount, "PEN");
            return operationExecutor.execute("client-overdraft", idempotencyKey, command);
        });

        assertThat(summary.errors()).isEmpty();
        assertThat(summary.successes()).hasSize(totalRequests);

        long appliedCount = summary.successes().stream()
                .filter(res -> res.status() == OperationStatus.APPLIED)
                .count();

        long rejectedCount = summary.successes().stream()
                .filter(res -> res.status() == OperationStatus.REJECTED && "INSUFFICIENT_FUNDS".equals(res.resultCode()))
                .count();

        assertThat(appliedCount).isEqualTo(2000);
        assertThat(rejectedCount).isEqualTo(1000);

        // El saldo final debe ser exactamente 0.00 (nunca negativo)
        databaseAssertions.assertAccountBalance(accountId, BigDecimal.ZERO.setScale(2));

        // Verificación de invariantes
        databaseAssertions.assertAccountInvariant(accountId);
    }
}
