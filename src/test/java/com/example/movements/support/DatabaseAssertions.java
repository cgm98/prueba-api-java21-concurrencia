package com.example.movements.support;

import com.example.movements.domain.Account;
import com.example.movements.infrastructure.jdbc.AccountRepository;
import com.example.movements.infrastructure.jdbc.LedgerRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Component
public class DatabaseAssertions {

    private final AccountRepository accountRepository;
    private final LedgerRepository ledgerRepository;

    public DatabaseAssertions(AccountRepository accountRepository, LedgerRepository ledgerRepository) {
        this.accountRepository = accountRepository;
        this.ledgerRepository = ledgerRepository;
    }

    public void assertAccountBalance(UUID accountId, BigDecimal expectedBalance) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AssertionError("Account not found: " + accountId));
        assertThat(account.getBalance().compareTo(expectedBalance))
                .withFailMessage("Expected account %s balance to be %s but was %s",
                        accountId, expectedBalance, account.getBalance())
                .isZero();
    }

    public void assertAccountInvariant(UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AssertionError("Account not found: " + accountId));

        BigDecimal sumLedger = ledgerRepository.sumSignedAmountByAccountId(accountId);
        BigDecimal expectedBalance = account.getOpeningBalance().add(sumLedger);

        assertThat(account.getBalance().compareTo(expectedBalance))
                .withFailMessage("Invariant violated for account %s: balance (%s) != openingBalance (%s) + sum(ledger) (%s)",
                        accountId, account.getBalance(), account.getOpeningBalance(), sumLedger)
                .isZero();

        assertThat(account.getBalance().compareTo(BigDecimal.ZERO))
                .withFailMessage("Account balance cannot be negative: %s", account.getBalance())
                .isGreaterThanOrEqualTo(0);
    }

    public void assertLedgerEntriesCount(UUID operationId, int expectedCount) {
        int count = ledgerRepository.countByOperationId(operationId);
        assertThat(count)
                .withFailMessage("Expected %d ledger entries for operation %s, found %d",
                        expectedCount, operationId, count)
                .isEqualTo(expectedCount);
    }

    public void assertTotalBalancePreserved(List<UUID> accountIds, BigDecimal expectedTotal) {
        BigDecimal total = BigDecimal.ZERO;
        for (UUID id : accountIds) {
            Account account = accountRepository.findById(id)
                    .orElseThrow(() -> new AssertionError("Account not found: " + id));
            total = total.add(account.getBalance());
        }

        assertThat(total.compareTo(expectedTotal))
                .withFailMessage("Total balance not conserved! Expected: %s, Actual: %s", expectedTotal, total)
                .isZero();
    }
}
