package com.example.movements.application;

import com.example.movements.api.dto.OperationResponse;
import com.example.movements.domain.Account;
import com.example.movements.domain.MovementResult;
import com.example.movements.domain.OperationStatus;
import com.example.movements.domain.OperationType;
import com.example.movements.domain.exception.AccountNotFoundException;
import com.example.movements.domain.exception.CurrencyMismatchException;
import com.example.movements.domain.exception.IdempotencyConflictException;
import com.example.movements.infrastructure.jdbc.AccountRepository;
import com.example.movements.infrastructure.jdbc.LedgerRepository;
import com.example.movements.infrastructure.jdbc.OperationRepository;
import com.example.movements.infrastructure.jdbc.ReservationResult;
import com.example.movements.domain.LedgerEntry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class MovementService {

    private static final Logger log = LoggerFactory.getLogger(MovementService.class);

    private final AccountRepository accountRepository;
    private final OperationRepository operationRepository;
    private final LedgerRepository ledgerRepository;
    private final ObjectMapper objectMapper;

    public MovementService(
            AccountRepository accountRepository,
            OperationRepository operationRepository,
            LedgerRepository ledgerRepository,
            ObjectMapper objectMapper
    ) {
        this.accountRepository = accountRepository;
        this.operationRepository = operationRepository;
        this.ledgerRepository = ledgerRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public MovementResult executeAttempt(
            String clientId,
            String idempotencyKey,
            MovementCommand command,
            String requestHash
    ) {
        UUID operationId = UUID.randomUUID();
        ReservationResult reservation = operationRepository.tryReserve(
                operationId,
                clientId,
                idempotencyKey,
                requestHash,
                command
        );

        if (reservation instanceof ReservationResult.Existing existing) {
            var existingOp = existing.operation();

            // Validación de Seguridad: Detección de Ataque de Manipulación de Payload o Reutilización de Clave
            if (!existingOp.requestHash().equals(requestHash)) {
                log.warn("ALERTA DE SEGURIDAD: Se detectó intento de reutilización de Idempotency-Key '{}' con payload modificado. Operación existente ID: {}, Estado: {}, Hash original: {}, Hash recibido: {}. Origen/Cliente: '{}'",
                        idempotencyKey, existingOp.id(), existingOp.status(), existingOp.requestHash(), requestHash, clientId);

                throw new IdempotencyConflictException(
                        idempotencyKey,
                        existingOp.id(),
                        existingOp.status(),
                        existingOp.requestHash(),
                        requestHash
                );
            }

            return new MovementResult(
                    existingOp.id(),
                    existingOp.type(),
                    existingOp.status(),
                    existingOp.sourceId(),
                    existingOp.destinationId(),
                    existingOp.amount(),
                    existingOp.currency(),
                    existingOp.resultCode(),
                    existingOp.httpStatus(),
                    existingOp.createdAt(),
                    true
            );
        }

        UUID reservedId = ((ReservationResult.Owned) reservation).operationId();

        if (command.type() == OperationType.WITHDRAWAL) {
            return processWithdrawal(reservedId, command);
        } else {
            return processTransfer(reservedId, command);
        }
    }

    private MovementResult processWithdrawal(UUID operationId, MovementCommand command) {
        Account account = accountRepository.findByIdForUpdate(command.sourceAccountId())
                .orElseThrow(() -> new AccountNotFoundException(command.sourceAccountId()));

        if (!account.getCurrency().equalsIgnoreCase(command.currency())) {
            throw new CurrencyMismatchException("Account currency " + account.getCurrency() + " does not match requested " + command.currency());
        }

        Instant now = Instant.now();
        if (!account.hasSufficientBalance(command.amount())) {
            MovementResult rejected = MovementResult.rejected(
                    operationId,
                    OperationType.WITHDRAWAL,
                    account.getId(),
                    null,
                    command.amount(),
                    command.currency(),
                    "INSUFFICIENT_FUNDS",
                    422,
                    now
            );
            operationRepository.complete(operationId, rejected, serializeResponse(rejected));
            return rejected;
        }

        account.debit(command.amount());
        accountRepository.updateBalance(account.getId(), account.getBalance());

        ledgerRepository.insert(new LedgerEntry(
                null,
                operationId,
                account.getId(),
                "DEBIT",
                command.amount().negate(),
                now
        ));

        MovementResult applied = MovementResult.applied(
                operationId,
                OperationType.WITHDRAWAL,
                account.getId(),
                null,
                command.amount(),
                command.currency(),
                now
        );
        operationRepository.complete(operationId, applied, serializeResponse(applied));
        return applied;
    }

    private MovementResult processTransfer(UUID operationId, MovementCommand command) {
        UUID sourceId = command.sourceAccountId();
        UUID destId = command.destinationAccountId();

        // Consistent ascending lock ordering prevents cross-transfer deadlocks
        UUID firstId = sourceId.compareTo(destId) < 0 ? sourceId : destId;
        UUID secondId = sourceId.compareTo(destId) < 0 ? destId : sourceId;

        Account first = accountRepository.findByIdForUpdate(firstId)
                .orElseThrow(() -> new AccountNotFoundException(firstId));
        Account second = accountRepository.findByIdForUpdate(secondId)
                .orElseThrow(() -> new AccountNotFoundException(secondId));

        Account source = first.getId().equals(sourceId) ? first : second;
        Account destination = first.getId().equals(destId) ? first : second;

        if (!source.getCurrency().equalsIgnoreCase(command.currency())) {
            throw new CurrencyMismatchException("Source currency " + source.getCurrency() + " does not match requested " + command.currency());
        }
        if (!destination.getCurrency().equalsIgnoreCase(command.currency())) {
            throw new CurrencyMismatchException("Destination currency " + destination.getCurrency() + " does not match requested " + command.currency());
        }

        Instant now = Instant.now();
        if (!source.hasSufficientBalance(command.amount())) {
            MovementResult rejected = MovementResult.rejected(
                    operationId,
                    OperationType.TRANSFER,
                    source.getId(),
                    destination.getId(),
                    command.amount(),
                    command.currency(),
                    "INSUFFICIENT_FUNDS",
                    422,
                    now
            );
            operationRepository.complete(operationId, rejected, serializeResponse(rejected));
            return rejected;
        }

        source.debit(command.amount());
        destination.credit(command.amount());

        accountRepository.updateBalance(source.getId(), source.getBalance());
        accountRepository.updateBalance(destination.getId(), destination.getBalance());

        ledgerRepository.insert(new LedgerEntry(
                null,
                operationId,
                source.getId(),
                "DEBIT",
                command.amount().negate(),
                now
        ));

        ledgerRepository.insert(new LedgerEntry(
                null,
                operationId,
                destination.getId(),
                "CREDIT",
                command.amount(),
                now
        ));

        MovementResult applied = MovementResult.applied(
                operationId,
                OperationType.TRANSFER,
                source.getId(),
                destination.getId(),
                command.amount(),
                command.currency(),
                now
        );
        operationRepository.complete(operationId, applied, serializeResponse(applied));
        return applied;
    }

    private String serializeResponse(MovementResult result) {
        try {
            return objectMapper.writeValueAsString(OperationResponse.from(result));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize OperationResponse", e);
        }
    }
}
