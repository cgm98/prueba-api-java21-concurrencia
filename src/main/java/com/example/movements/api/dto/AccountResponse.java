package com.example.movements.api.dto;

import com.example.movements.domain.Account;
import java.util.UUID;

public record AccountResponse(
        UUID accountId,
        String balance,
        String currency
) {
    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getBalance().setScale(2).toPlainString(),
                account.getCurrency()
        );
    }
}
