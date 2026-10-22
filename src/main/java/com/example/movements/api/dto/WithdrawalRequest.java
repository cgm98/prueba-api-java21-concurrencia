package com.example.movements.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

public record WithdrawalRequest(
        @NotNull(message = "accountId is required")
        UUID accountId,

        @NotBlank(message = "amount is required")
        @Pattern(regexp = "^(0|[1-9]\\d*)(\\.\\d{2})$", message = "amount must have exactly two decimal places, e.g. '5.00'")
        String amount,

        @NotBlank(message = "currency is required")
        @Pattern(regexp = "(?i)PEN", message = "currency must be PEN")
        String currency
) {
}
