package com.example.movements.integration;

import com.example.movements.application.MovementCommand;
import com.example.movements.application.OperationExecutor;
import com.example.movements.domain.Account;
import com.example.movements.domain.MovementResult;
import com.example.movements.domain.OperationStatus;
import com.example.movements.domain.exception.IdempotencyConflictException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyIT extends AbstractMySQLIntegrationTest {

    @Autowired
    private OperationExecutor operationExecutor;

    @Autowired
    private AccountFixtures accountFixtures;

    @Autowired
    private DatabaseAssertions databaseAssertions;

    @Test
    @DisplayName("Idempotencia concurrente: Múltiples tareas con la misma clave y payload generan un único efecto financiero")
    void shouldExecuteOnlyOnceWhenSameKeySentConcurrently() throws InterruptedException {
        Account account = accountFixtures.createAccount(new BigDecimal("1000.00"));
        UUID accountId = account.getId();

        String sharedKey = UUID.randomUUID().toString();
        BigDecimal amount = new BigDecimal("100.00");
        int concurrency = 50;

        ConcurrentRunner.ExecutionSummary<MovementResult> summary = ConcurrentRunner.run(concurrency, () -> {
            MovementCommand command = MovementCommand.withdrawal(accountId, amount, "PEN");
            return operationExecutor.execute("client-idempotency", sharedKey, command);
        });

        assertThat(summary.errors()).isEmpty();
        assertThat(summary.successes()).hasSize(concurrency);

        // Todas las respuestas comparten el mismo operationId
        UUID firstOpId = summary.successes().get(0).operationId();
        assertThat(summary.successes()).allMatch(res -> res.operationId().equals(firstOpId));

        // Exactamente una debió ser la original y las demás replayed
        long replayedCount = summary.successes().stream().filter(MovementResult::replayed).count();
        long originalCount = summary.successes().stream().filter(res -> !res.replayed()).count();

        assertThat(originalCount).isEqualTo(1);
        assertThat(replayedCount).isEqualTo(concurrency - 1);

        // Saldo debitado exactamente una vez (1000 - 100 = 900.00)
        databaseAssertions.assertAccountBalance(accountId, new BigDecimal("900.00"));
        databaseAssertions.assertLedgerEntriesCount(firstOpId, 1);
        databaseAssertions.assertAccountInvariant(accountId);
    }

    @Test
    @DisplayName("Repetición secuencial: Misma clave reproduce exactamente el resultado persistido")
    void shouldReplayStoredResultOnSequentialRepeats() {
        Account account = accountFixtures.createAccount(new BigDecimal("500.00"));
        String key = UUID.randomUUID().toString();
        BigDecimal amount = new BigDecimal("50.00");

        MovementCommand command = MovementCommand.withdrawal(account.getId(), amount, "PEN");

        // Primer intento
        MovementResult first = operationExecutor.execute("client-seq", key, command);
        assertThat(first.replayed()).isFalse();
        assertThat(first.status()).isEqualTo(OperationStatus.APPLIED);

        // Segundo intento con la misma clave y contenido
        MovementResult second = operationExecutor.execute("client-seq", key, command);
        assertThat(second.replayed()).isTrue();
        assertThat(second.operationId()).isEqualTo(first.operationId());
        assertThat(second.amount()).isEqualByComparingTo(first.amount());

        // Saldo debitado solo una vez
        databaseAssertions.assertAccountBalance(account.getId(), new BigDecimal("450.00"));
    }

    @Test
    @DisplayName("Clave reutilizada con payload diferente: Lanza IdempotencyConflictException (HTTP 409)")
    void shouldThrowConflictWhenKeyReusedWithDifferentPayload() {
        Account account = accountFixtures.createAccount(new BigDecimal("500.00"));
        String key = UUID.randomUUID().toString();

        MovementCommand cmd1 = MovementCommand.withdrawal(account.getId(), new BigDecimal("50.00"), "PEN");
        MovementCommand cmd2 = MovementCommand.withdrawal(account.getId(), new BigDecimal("75.00"), "PEN");

        // Primer comando exitoso
        operationExecutor.execute("client-conflict", key, cmd1);

        // Segundo comando con importe diferente usando la misma clave
        assertThatThrownBy(() -> operationExecutor.execute("client-conflict", key, cmd2))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("Idempotency key reused with a different request payload");

        // Saldo solo refleja la primera operación (500 - 50 = 450.00)
        databaseAssertions.assertAccountBalance(account.getId(), new BigDecimal("450.00"));
    }

    @Test
    @DisplayName("Idempotencia en rechazos: Un rechazo por saldo insuficiente se reproduce de forma idempotente")
    void shouldReplayRejectedResultIdempotently() {
        Account account = accountFixtures.createAccount(new BigDecimal("20.00"));
        String key = UUID.randomUUID().toString();
        MovementCommand command = MovementCommand.withdrawal(account.getId(), new BigDecimal("100.00"), "PEN");

        // Intento rechazado por saldo insuficiente
        MovementResult first = operationExecutor.execute("client-rejected", key, command);
        assertThat(first.status()).isEqualTo(OperationStatus.REJECTED);
        assertThat(first.resultCode()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(first.replayed()).isFalse();

        // Repetición con la misma clave debe devolver el rechazo almacenado
        MovementResult second = operationExecutor.execute("client-rejected", key, command);
        assertThat(second.status()).isEqualTo(OperationStatus.REJECTED);
        assertThat(second.resultCode()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(second.replayed()).isTrue();

        // Saldo intacto y sin asientos
        databaseAssertions.assertAccountBalance(account.getId(), new BigDecimal("20.00"));
        databaseAssertions.assertLedgerEntriesCount(first.operationId(), 0);
    }

    @Test
    @DisplayName("Seguridad: Atacante intenta reutilizar una Idempotency-Key YA PROCESADA cambiando el monto -> Bloqueado con HTTP 409 y saldo intacto")
    void shouldBlockAttackerTamperingPayloadOnProcessedKey() {
        Account account = accountFixtures.createAccount(new BigDecimal("1000.00"));
        UUID accountId = account.getId();
        String idempotencyKey = UUID.randomUUID().toString();

        // 1. Operación legítima procesada exitosamente
        MovementCommand legitimateCommand = MovementCommand.withdrawal(accountId, new BigDecimal("50.00"), "PEN");
        MovementResult legitimateResult = operationExecutor.execute("client-legitimate", idempotencyKey, legitimateCommand);

        assertThat(legitimateResult.status()).isEqualTo(OperationStatus.APPLIED);
        assertThat(legitimateResult.replayed()).isFalse();
        databaseAssertions.assertAccountBalance(accountId, new BigDecimal("950.00"));

        // 2. Atacante intenta enviar la MISMA clave pero con un monto alterado (S/ 500.00)
        MovementCommand tamperedCommand = MovementCommand.withdrawal(accountId, new BigDecimal("500.00"), "PEN");

        assertThatThrownBy(() -> operationExecutor.execute("client-attacker", idempotencyKey, tamperedCommand))
                .isInstanceOf(IdempotencyConflictException.class)
                .satisfies(ex -> {
                    IdempotencyConflictException ice = (IdempotencyConflictException) ex;
                    assertThat(ice.getIdempotencyKey()).isEqualTo(idempotencyKey);
                    assertThat(ice.getExistingOperationId()).isEqualTo(legitimateResult.operationId());
                    assertThat(ice.getExistingStatus()).isEqualTo(OperationStatus.APPLIED);
                });

        // 3. Verificación de blindaje: El saldo de la cuenta NO fue alterado por el ataque
        databaseAssertions.assertAccountBalance(accountId, new BigDecimal("950.00"));

        // Exactamente 1 asiento contable en base de datos (el de S/ 50.00 original)
        databaseAssertions.assertLedgerEntriesCount(legitimateResult.operationId(), 1);
        databaseAssertions.assertAccountInvariant(accountId);
    }

    @Test
    @DisplayName("Seguridad: Atacante intenta reutilizar clave procesada cambiando tipo de operación (WITHDRAWAL a TRANSFER) -> Bloqueado")
    void shouldBlockAttackerSwitchingOperationTypeOnProcessedKey() {
        Account accountA = accountFixtures.createAccount(new BigDecimal("1000.00"));
        Account accountB = accountFixtures.createAccount(new BigDecimal("200.00"));
        String idempotencyKey = UUID.randomUUID().toString();

        // Operación original de retiro procesada
        MovementCommand withdrawal = MovementCommand.withdrawal(accountA.getId(), new BigDecimal("100.00"), "PEN");
        MovementResult result = operationExecutor.execute("client-origin", idempotencyKey, withdrawal);
        assertThat(result.status()).isEqualTo(OperationStatus.APPLIED);

        // Atacante intenta transformar la clave en una transferencia a la cuenta B
        MovementCommand transfer = MovementCommand.transfer(accountA.getId(), accountB.getId(), new BigDecimal("100.00"), "PEN");

        assertThatThrownBy(() -> operationExecutor.execute("client-attacker", idempotencyKey, transfer))
                .isInstanceOf(IdempotencyConflictException.class);

        // Los saldos deben mantenerse inalterados
        databaseAssertions.assertAccountBalance(accountA.getId(), new BigDecimal("900.00"));
        databaseAssertions.assertAccountBalance(accountB.getId(), new BigDecimal("200.00"));
    }
}
