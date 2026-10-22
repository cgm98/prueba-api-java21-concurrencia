package com.example.movements.infrastructure.jdbc;

import com.example.movements.domain.LedgerEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Repository
public class LedgerRepository {

    private final JdbcTemplate jdbcTemplate;

    public LedgerRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<LedgerEntry> LEDGER_ROW_MAPPER = (rs, rowNum) -> new LedgerEntry(
            rs.getLong("id"),
            UUID.fromString(rs.getString("operation_id")),
            UUID.fromString(rs.getString("account_id")),
            rs.getString("leg"),
            rs.getBigDecimal("signed_amount"),
            rs.getTimestamp("created_at").toInstant()
    );

    public void insert(LedgerEntry entry) {
        String sql = """
                INSERT INTO ledger_entries (operation_id, account_id, leg, signed_amount, created_at)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP(6))
                """;
        jdbcTemplate.update(
                sql,
                entry.operationId().toString(),
                entry.accountId().toString(),
                entry.leg(),
                entry.signedAmount()
        );
    }

    public List<LedgerEntry> findByOperationId(UUID operationId) {
        String sql = "SELECT id, operation_id, account_id, leg, signed_amount, created_at FROM ledger_entries WHERE operation_id = ?";
        return jdbcTemplate.query(sql, LEDGER_ROW_MAPPER, operationId.toString());
    }

    public List<LedgerEntry> findByAccountId(UUID accountId) {
        String sql = "SELECT id, operation_id, account_id, leg, signed_amount, created_at FROM ledger_entries WHERE account_id = ? ORDER BY id ASC";
        return jdbcTemplate.query(sql, LEDGER_ROW_MAPPER, accountId.toString());
    }

    public BigDecimal sumSignedAmountByAccountId(UUID accountId) {
        String sql = "SELECT COALESCE(SUM(signed_amount), 0) FROM ledger_entries WHERE account_id = ?";
        BigDecimal sum = jdbcTemplate.queryForObject(sql, BigDecimal.class, accountId.toString());
        return sum != null ? sum : BigDecimal.ZERO;
    }

    public int countByOperationId(UUID operationId) {
        String sql = "SELECT COUNT(*) FROM ledger_entries WHERE operation_id = ?";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, operationId.toString());
        return count != null ? count : 0;
    }
}
