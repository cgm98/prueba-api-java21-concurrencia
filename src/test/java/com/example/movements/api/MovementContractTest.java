package com.example.movements.api;

import com.example.movements.application.MovementCommand;
import com.example.movements.application.OperationExecutor;
import com.example.movements.domain.MovementResult;
import com.example.movements.domain.OperationStatus;
import com.example.movements.domain.OperationType;
import com.example.movements.domain.exception.AccountNotFoundException;
import com.example.movements.domain.exception.IdempotencyConflictException;
import com.example.movements.domain.exception.SameAccountTransferException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MovementController.class)
class MovementContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OperationExecutor operationExecutor;

    @Test
    void shouldReturn201WithLocationAndHeadersOnSuccessfulWithdrawal() throws Exception {
        UUID accountId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();

        MovementResult result = MovementResult.applied(
                operationId, OperationType.WITHDRAWAL, accountId, null,
                new BigDecimal("5.00"), "PEN", Instant.parse("2026-10-22T15:00:00Z")
        );

        when(operationExecutor.execute(any(), eq(idempotencyKey), any(MovementCommand.class)))
                .thenReturn(result);

        String json = String.format("""
                {
                    "accountId": "%s",
                    "amount": "5.00",
                    "currency": "PEN"
                }
                """, accountId);

        mockMvc.perform(post("/api/v1/withdrawals")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/operations/" + operationId))
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(jsonPath("$.operationId").value(operationId.toString()))
                .andExpect(jsonPath("$.type").value("WITHDRAWAL"))
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.amount").value("5.00"))
                .andExpect(jsonPath("$.currency").value("PEN"))
                .andExpect(jsonPath("$.resultCode").value("OK"));
    }

    @Test
    void shouldReturn201WithReplayedTrueOnIdempotentReplay() throws Exception {
        UUID accountId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();

        MovementResult result = MovementResult.applied(
                operationId, OperationType.WITHDRAWAL, accountId, null,
                new BigDecimal("5.00"), "PEN", Instant.parse("2026-10-22T15:00:00Z")
        ).withReplayed(true);

        when(operationExecutor.execute(any(), eq(idempotencyKey), any(MovementCommand.class)))
                .thenReturn(result);

        String json = String.format("""
                {
                    "accountId": "%s",
                    "amount": "5.00",
                    "currency": "PEN"
                }
                """, accountId);

        mockMvc.perform(post("/api/v1/withdrawals")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.status").value("APPLIED"));
    }

    @Test
    void shouldReturn422WhenInsufficientFunds() throws Exception {
        UUID accountId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();

        MovementResult result = MovementResult.rejected(
                operationId, OperationType.WITHDRAWAL, accountId, null,
                new BigDecimal("50000.00"), "PEN", "INSUFFICIENT_FUNDS", 422,
                Instant.parse("2026-10-22T15:00:00Z")
        );

        when(operationExecutor.execute(any(), eq(idempotencyKey), any(MovementCommand.class)))
                .thenReturn(result);

        String json = String.format("""
                {
                    "accountId": "%s",
                    "amount": "50000.00",
                    "currency": "PEN"
                }
                """, accountId);

        mockMvc.perform(post("/api/v1/withdrawals")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.resultCode").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    void shouldReturn409WhenIdempotencyKeyReusedWithDifferentPayload() throws Exception {
        UUID accountId = UUID.randomUUID();
        UUID existingOpId = UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();

        when(operationExecutor.execute(any(), eq(idempotencyKey), any(MovementCommand.class)))
                .thenThrow(new IdempotencyConflictException(
                        idempotencyKey, existingOpId, OperationStatus.APPLIED, "hash-original-sha256", "hash-incoming-sha256"
                ));

        String json = String.format("""
                {
                    "accountId": "%s",
                    "amount": "100.00",
                    "currency": "PEN"
                }
                """, accountId);

        mockMvc.perform(post("/api/v1/withdrawals")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
                .andExpect(jsonPath("$.existingOperationId").value(existingOpId.toString()))
                .andExpect(jsonPath("$.existingStatus").value("APPLIED"))
                .andExpect(jsonPath("$.securityWarning").value("IDEMPOTENCY_PAYLOAD_TAMPERING_BLOCKED"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void shouldReturn400WhenIdempotencyKeyHeaderIsMissing() throws Exception {
        UUID accountId = UUID.randomUUID();
        String json = String.format("""
                {
                    "accountId": "%s",
                    "amount": "10.00",
                    "currency": "PEN"
                }
                """, accountId);

        mockMvc.perform(post("/api/v1/withdrawals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldReturn400WhenAmountHasInvalidFormat() throws Exception {
        UUID accountId = UUID.randomUUID();
        String json = String.format("""
                {
                    "accountId": "%s",
                    "amount": "10.555",
                    "currency": "PEN"
                }
                """, accountId);

        mockMvc.perform(post("/api/v1/withdrawals")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldReturn400WhenSameAccountInTransfer() throws Exception {
        UUID accountId = UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();

        when(operationExecutor.execute(any(), eq(idempotencyKey), any(MovementCommand.class)))
                .thenThrow(new SameAccountTransferException(accountId));

        String json = String.format("""
                {
                    "sourceAccountId": "%s",
                    "destinationAccountId": "%s",
                    "amount": "10.00",
                    "currency": "PEN"
                }
                """, accountId, accountId);

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SAME_ACCOUNT"));
    }

    @Test
    void shouldReturn404WhenAccountNotFound() throws Exception {
        UUID accountId = UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();

        when(operationExecutor.execute(any(), eq(idempotencyKey), any(MovementCommand.class)))
                .thenThrow(new AccountNotFoundException(accountId));

        String json = String.format("""
                {
                    "accountId": "%s",
                    "amount": "10.00",
                    "currency": "PEN"
                }
                """, accountId);

        mockMvc.perform(post("/api/v1/withdrawals")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }
}
