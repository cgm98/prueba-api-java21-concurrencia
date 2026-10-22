package com.example.movements.support;

import com.example.movements.domain.Account;
import com.example.movements.infrastructure.jdbc.AccountRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Component
public class AccountFixtures {

    private final AccountRepository accountRepository;

    public AccountFixtures(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    public Account createAccount(BigDecimal initialBalance) {
        return createAccount(UUID.randomUUID(), initialBalance, "PEN");
    }

    public Account createAccount(UUID accountId, BigDecimal initialBalance, String currency) {
        Account account = new Account(
                accountId,
                currency,
                initialBalance,
                initialBalance,
                Instant.now()
        );
        accountRepository.create(account);
        return account;
    }
}
