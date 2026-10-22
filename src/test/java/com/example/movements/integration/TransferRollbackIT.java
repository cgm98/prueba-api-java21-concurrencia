package com.example.movements.integration;

import com.example.movements.application.MovementCommand;
import com.example.movements.application.MovementService;
import com.example.movements.application.RequestFingerprint;
import com.example.movements.domain.Account;
import com.example.movements.infrastructure.jdbc.LedgerRepository;
import com.example.movements.support.AbstractMySQLIntegrationTest;
import com.example.movements.support.AccountFixtures;
import com.example.movements.support.DatabaseAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

class TransferRollbackIT extends AbstractMySQLIntegrationTest {

    @Autowired
    private MovementService movementService;

    @Autowired
    private AccountFixtures accountFixtures;

    @Autowired
    private DatabaseAssertions databaseAssertions;

    @SpyBean
    private LedgerRepository ledgerRepository;

    @Test
    @DisplayName("Atomicidad y Rollback: Fallo simulado durante la transferencia revierte completamente débitos, créditos y asientos")
    void shouldRollbackCompletelyWhenErrorOccursDuringTransfer() {
        BigDecimal initialA = new BigDecimal("1000.00");
        BigDecimal initialB = new BigDecimal("500.00");

        Account accountA = accountFixtures.createAccount(initialA);
        Account accountB = accountFixtures.createAccount(initialB);

        UUID opId = UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();
        MovementCommand command = MovementCommand.transfer(accountA.getId(), accountB.getId(), new BigDecimal("100.00"), "PEN");
        String hash = RequestFingerprint.computeHash(command);

        // Simulamos fallo técnico al intentar insertar el segundo asiento contable (el crédito)
        doThrow(new DataIntegrityViolationException("Simulated database failure during credit ledger entry"))
                .when(ledgerRepository).insert(argThat(entry -> "CREDIT".equals(entry.leg())));

        // Verificamos que se propaga la excepción
        assertThatThrownBy(() -> movementService.executeAttempt("client-test", idempotencyKey, command, hash))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Verificamos que NO quedaron cambios parciales en la base de datos
        databaseAssertions.assertAccountBalance(accountA.getId(), initialA);
        databaseAssertions.assertAccountBalance(accountB.getId(), initialB);

        // Ningún asiento contable debió persistirse
        databaseAssertions.assertLedgerEntriesCount(opId, 0);

        // Verificación de invariantes
        databaseAssertions.assertAccountInvariant(accountA.getId());
        databaseAssertions.assertAccountInvariant(accountB.getId());
    }
}
