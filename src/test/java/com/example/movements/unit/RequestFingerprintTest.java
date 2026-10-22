package com.example.movements.unit;

import com.example.movements.application.MovementCommand;
import com.example.movements.application.RequestFingerprint;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RequestFingerprintTest {

    @Test
    void shouldProduceSameHashForIdenticalCommands() {
        UUID accountId = UUID.randomUUID();
        MovementCommand cmd1 = MovementCommand.withdrawal(accountId, new BigDecimal("50.00"), "PEN");
        MovementCommand cmd2 = MovementCommand.withdrawal(accountId, new BigDecimal("50.00"), "PEN");

        String hash1 = RequestFingerprint.computeHash(cmd1);
        String hash2 = RequestFingerprint.computeHash(cmd2);

        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).hasSize(64); // SHA-256 hex string
    }

    @Test
    void shouldProduceDifferentHashForDifferentAmounts() {
        UUID accountId = UUID.randomUUID();
        MovementCommand cmd1 = MovementCommand.withdrawal(accountId, new BigDecimal("50.00"), "PEN");
        MovementCommand cmd2 = MovementCommand.withdrawal(accountId, new BigDecimal("50.01"), "PEN");

        String hash1 = RequestFingerprint.computeHash(cmd1);
        String hash2 = RequestFingerprint.computeHash(cmd2);

        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void shouldProduceDifferentHashForDifferentAccounts() {
        UUID acc1 = UUID.randomUUID();
        UUID acc2 = UUID.randomUUID();
        MovementCommand cmd1 = MovementCommand.withdrawal(acc1, new BigDecimal("10.00"), "PEN");
        MovementCommand cmd2 = MovementCommand.withdrawal(acc2, new BigDecimal("10.00"), "PEN");

        assertThat(RequestFingerprint.computeHash(cmd1)).isNotEqualTo(RequestFingerprint.computeHash(cmd2));
    }

    @Test
    void shouldProduceDifferentHashForWithdrawalVsTransfer() {
        UUID acc1 = UUID.randomUUID();
        UUID acc2 = UUID.randomUUID();
        MovementCommand cmd1 = MovementCommand.withdrawal(acc1, new BigDecimal("10.00"), "PEN");
        MovementCommand cmd2 = MovementCommand.transfer(acc1, acc2, new BigDecimal("10.00"), "PEN");

        assertThat(RequestFingerprint.computeHash(cmd1)).isNotEqualTo(RequestFingerprint.computeHash(cmd2));
    }
}
