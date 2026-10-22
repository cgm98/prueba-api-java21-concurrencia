package com.example.movements.application;

import com.example.movements.domain.Account;
import com.example.movements.domain.LedgerEntry;
import com.example.movements.domain.Operation;
import com.example.movements.domain.exception.AccountNotFoundException;
import com.example.movements.infrastructure.jdbc.AccountRepository;
import com.example.movements.infrastructure.jdbc.LedgerRepository;
import com.example.movements.infrastructure.jdbc.OperationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class QueryService {

    private final AccountRepository accountRepository;
    private final OperationRepository operationRepository;
    private final LedgerRepository ledgerRepository;

    public QueryService(
            AccountRepository accountRepository,
            OperationRepository operationRepository,
            LedgerRepository ledgerRepository
    ) {
        this.accountRepository = accountRepository;
        this.operationRepository = operationRepository;
        this.ledgerRepository = ledgerRepository;
    }

    public Account getAccount(UUID accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    public Optional<Operation> getOperation(UUID operationId) {
        return operationRepository.findById(operationId);
    }

    public List<LedgerEntry> getLedgerEntriesForAccount(UUID accountId) {
        return ledgerRepository.findByAccountId(accountId);
    }

    public boolean verifyAccountInvariant(UUID accountId) {
        Account account = getAccount(accountId);
        BigDecimal sumEntries = ledgerRepository.sumSignedAmountByAccountId(accountId);
        BigDecimal expectedBalance = account.getOpeningBalance().add(sumEntries);
        return account.getBalance().compareTo(expectedBalance) == 0 && account.getBalance().compareTo(BigDecimal.ZERO) >= 0;
    }
}
