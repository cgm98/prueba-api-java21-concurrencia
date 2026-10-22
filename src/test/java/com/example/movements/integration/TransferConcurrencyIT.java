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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TransferConcurrencyIT extends AbstractMySQLIntegrationTest {

    @Autowired
    private OperationExecutor operationExecutor;

    @Autowired
    private AccountFixtures accountFixtures;

    @Autowired
    private DatabaseAssertions databaseAssertions;

    @Test
    @DisplayName("Escenario B: 500 transferencias concurrentes de A hacia B de S/ 10.00 (A=10,000, B=0) -> A=5,000, B=5,000; total conservado")
    void shouldExecute500ConcurrentTransfersSuccessfully() throws InterruptedException {
        BigDecimal balanceA = new BigDecimal("10000.00");
        BigDecimal balanceB = new BigDecimal("0.00");

        Account accountA = accountFixtures.createAccount(balanceA);
        Account accountB = accountFixtures.createAccount(balanceB);

        int concurrency = 500;
        BigDecimal transferAmount = new BigDecimal("10.00");

        ConcurrentRunner.ExecutionSummary<MovementResult> summary = ConcurrentRunner.run(concurrency, () -> {
            String idempotencyKey = UUID.randomUUID().toString();
            MovementCommand command = MovementCommand.transfer(accountA.getId(), accountB.getId(), transferAmount, "PEN");
            return operationExecutor.execute("client-escenario-b", idempotencyKey, command);
        });

        assertThat(summary.errors()).isEmpty();
        assertThat(summary.successes()).hasSize(concurrency);

        long appliedCount = summary.successes().stream()
                .filter(res -> res.status() == OperationStatus.APPLIED)
                .count();
        assertThat(appliedCount).isEqualTo(concurrency);

        // Verificación en Base de Datos: A = S/ 5,000.00, B = S/ 5,000.00
        databaseAssertions.assertAccountBalance(accountA.getId(), new BigDecimal("5000.00"));
        databaseAssertions.assertAccountBalance(accountB.getId(), new BigDecimal("5000.00"));

        // Verificación de conservación de saldo total = S/ 10,000.00
        databaseAssertions.assertTotalBalancePreserved(List.of(accountA.getId(), accountB.getId()), new BigDecimal("10000.00"));

        // Verificación de invariantes contables
        databaseAssertions.assertAccountInvariant(accountA.getId());
        databaseAssertions.assertAccountInvariant(accountB.getId());
    }

    @Test
    @DisplayName("Prevención de Deadlocks: Transferencias cruzadas concurrentes A->B y B->A sin ciclos gracias al orden de locks ascendente")
    void shouldPreventDeadlocksOnBidirectionalCrossTransfers() throws InterruptedException {
        Account accountA = accountFixtures.createAccount(new BigDecimal("10000.00"));
        Account accountB = accountFixtures.createAccount(new BigDecimal("10000.00"));

        int totalTransfers = 400; // 200 de A->B y 200 de B->A
        BigDecimal transferAmount = new BigDecimal("10.00");
        AtomicInteger counter = new AtomicInteger();

        ConcurrentRunner.ExecutionSummary<MovementResult> summary = ConcurrentRunner.run(totalTransfers, () -> {
            int current = counter.incrementAndGet();
            UUID source = (current % 2 == 0) ? accountA.getId() : accountB.getId();
            UUID dest = (current % 2 == 0) ? accountB.getId() : accountA.getId();

            String idempotencyKey = UUID.randomUUID().toString();
            MovementCommand command = MovementCommand.transfer(source, dest, transferAmount, "PEN");
            return operationExecutor.execute("client-cross-transfer", idempotencyKey, command);
        });

        assertThat(summary.errors()).isEmpty();
        assertThat(summary.successes()).hasSize(totalTransfers);

        // Conservación del saldo total inicial (10,000 + 10,000 = 20,000.00)
        databaseAssertions.assertTotalBalancePreserved(List.of(accountA.getId(), accountB.getId()), new BigDecimal("20000.00"));

        databaseAssertions.assertAccountInvariant(accountA.getId());
        databaseAssertions.assertAccountInvariant(accountB.getId());
    }
}
