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

class WithdrawalConcurrencyIT extends AbstractMySQLIntegrationTest {

    @Autowired
    private OperationExecutor operationExecutor;

    @Autowired
    private AccountFixtures accountFixtures;

    @Autowired
    private DatabaseAssertions databaseAssertions;

    @Test
    @DisplayName("Escenario A: 1,000 retiros concurrentes de S/ 5.00 sobre saldo inicial S/ 10,000.00 -> saldo final S/ 5,000.00")
    void shouldExecute1000ConcurrentWithdrawalsSuccessfully() throws InterruptedException {
        // Preparación: Saldo inicial S/ 10,000.00
        BigDecimal initialBalance = new BigDecimal("10000.00");
        Account account = accountFixtures.createAccount(initialBalance);
        UUID accountId = account.getId();

        int concurrency = 1000;
        BigDecimal withdrawalAmount = new BigDecimal("5.00");

        // Ejecución concurrente real con Virtual Threads sincronizados con barrera CountDownLatch
        ConcurrentRunner.ExecutionSummary<MovementResult> summary = ConcurrentRunner.run(concurrency, () -> {
            String idempotencyKey = UUID.randomUUID().toString();
            MovementCommand command = MovementCommand.withdrawal(accountId, withdrawalAmount, "PEN");
            return operationExecutor.execute("client-escenario-a", idempotencyKey, command);
        });

        // Verificación de resultados de ejecución
        assertThat(summary.errors())
                .withFailMessage("Se produjeron errores en la ejecución concurrente: %s", summary.errors())
                .isEmpty();
        assertThat(summary.successes()).hasSize(concurrency);

        long appliedCount = summary.successes().stream()
                .filter(res -> res.status() == OperationStatus.APPLIED)
                .count();
        assertThat(appliedCount).isEqualTo(concurrency);

        // Verificación en Base de Datos: Saldo final S/ 5,000.00
        BigDecimal expectedFinalBalance = initialBalance.subtract(withdrawalAmount.multiply(BigDecimal.valueOf(concurrency)));
        databaseAssertions.assertAccountBalance(accountId, expectedFinalBalance);

        // Verificación estricta de invariantes: balance = opening_balance + sum(ledger_entries) y balance >= 0
        databaseAssertions.assertAccountInvariant(accountId);
    }
}
