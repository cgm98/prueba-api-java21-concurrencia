package com.example.movements.integration;

import com.example.movements.application.MovementCommand;
import com.example.movements.application.OperationExecutor;
import com.example.movements.domain.Account;
import com.example.movements.domain.MovementResult;
import com.example.movements.domain.OperationStatus;
import com.example.movements.support.AbstractMySQLIntegrationTest;
import com.example.movements.support.AccountFixtures;
import com.example.movements.support.DatabaseAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LockRetryIT extends AbstractMySQLIntegrationTest {

    @Autowired
    private OperationExecutor operationExecutor;

    @Autowired
    private AccountFixtures accountFixtures;

    @Autowired
    private DatabaseAssertions databaseAssertions;

    @Test
    @DisplayName("Conflictos y reintentos: Operación exitosa tras resolución de contención sin duplicar movimientos")
    void shouldResolveLockContentionWithoutDuplicatingMovements() {
        Account account = accountFixtures.createAccount(new BigDecimal("1000.00"));
        String key = UUID.randomUUID().toString();
        MovementCommand command = MovementCommand.withdrawal(account.getId(), new BigDecimal("100.00"), "PEN");

        MovementResult result = operationExecutor.execute("client-retry", key, command);

        assertThat(result.status()).isEqualTo(OperationStatus.APPLIED);
        databaseAssertions.assertAccountBalance(account.getId(), new BigDecimal("900.00"));
        databaseAssertions.assertAccountInvariant(account.getId());
    }
}
