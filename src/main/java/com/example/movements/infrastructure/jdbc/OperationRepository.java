package com.example.movements.infrastructure.jdbc;

import com.example.movements.application.MovementCommand;
import com.example.movements.domain.MovementResult;
import com.example.movements.domain.Operation;
import com.example.movements.domain.OperationStatus;
import com.example.movements.domain.OperationType;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

@Repository
public class OperationRepository {

    private final JdbcTemplate jdbcTemplate;

    public OperationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<Operation> OPERATION_ROW_MAPPER = (rs, rowNum) -> new Operation(
            UUID.fromString(rs.getString("id")),
            rs.getString("client_id"),
            rs.getString("idempotency_key"),
            rs.getString("request_hash"),
            OperationType.valueOf(rs.getString("type")),
            rs.getString("source_id") != null ? UUID.fromString(rs.getString("source_id")) : null,
            rs.getString("destination_id") != null ? UUID.fromString(rs.getString("destination_id")) : null,
            rs.getBigDecimal("amount"),
            rs.getString("currency"),
            rs.getTimestamp("created_at").toInstant(),
            OperationStatus.valueOf(rs.getString("status")),
            rs.getString("result_code"),
            rs.getInt("http_status"),
            rs.getString("response_payload")
    );

    public ReservationResult tryReserve(
            UUID id,
            String clientId,
            String idempotencyKey,
            String requestHash,
            MovementCommand command
    ) {
        // Pre-verificación para claves ya procesadas
        Optional<Operation> preExisting = findByIdempotencyKey(idempotencyKey);
        if (preExisting.isPresent()) {
            return ReservationResult.existing(preExisting.get());
        }

        String insertSql = """
                INSERT INTO operations (
                    id, client_id, idempotency_key, request_hash, type,
                    source_id, destination_id, amount, currency, created_at,
                    status, result_code, http_status, response_payload
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6), 'PENDING', 'PROCESSING', 202, '')
                """;

        try {
            jdbcTemplate.update(
                    insertSql,
                    id.toString(),
                    clientId,
                    idempotencyKey,
                    requestHash,
                    command.type().name(),
                    command.sourceAccountId() != null ? command.sourceAccountId().toString() : null,
                    command.destinationAccountId() != null ? command.destinationAccountId().toString() : null,
                    command.amount(),
                    command.currency()
            );
            return ReservationResult.owned(id);
        } catch (DuplicateKeyException e) {
            Operation existing = findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("DuplicateKey occurred but operation not found", e));
            return ReservationResult.existing(existing);
        }
    }

    public void complete(UUID id, MovementResult result, String responsePayload) {
        String updateSql = """
                UPDATE operations
                SET status = ?, result_code = ?, http_status = ?, response_payload = ?
                WHERE id = ?
                """;
        int updated = jdbcTemplate.update(
                updateSql,
                result.status().name(),
                result.resultCode(),
                result.httpStatus(),
                responsePayload,
                id.toString()
        );
        if (updated == 0) {
            throw new IllegalStateException("Failed to complete operation: " + id);
        }
    }

    public Optional<Operation> findById(UUID id) {
        String sql = "SELECT * FROM operations WHERE id = ?";
        try {
            Operation op = jdbcTemplate.queryForObject(sql, OPERATION_ROW_MAPPER, id.toString());
            return Optional.ofNullable(op);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<Operation> findByClientIdAndIdempotencyKey(String clientId, String idempotencyKey) {
        String sql = "SELECT * FROM operations WHERE client_id = ? AND idempotency_key = ?";
        try {
            Operation op = jdbcTemplate.queryForObject(sql, OPERATION_ROW_MAPPER, clientId, idempotencyKey);
            return Optional.ofNullable(op);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<Operation> findByIdempotencyKey(String idempotencyKey) {
        String sql = "SELECT * FROM operations WHERE idempotency_key = ?";
        try {
            Operation op = jdbcTemplate.queryForObject(sql, OPERATION_ROW_MAPPER, idempotencyKey);
            return Optional.ofNullable(op);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}
