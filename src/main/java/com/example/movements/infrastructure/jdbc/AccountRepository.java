package com.example.movements.infrastructure.jdbc;

import com.example.movements.domain.Account;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AccountRepository {

    private final JdbcTemplate jdbcTemplate;

    public AccountRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<Account> ACCOUNT_ROW_MAPPER = (rs, rowNum) -> new Account(
            UUID.fromString(rs.getString("id")),
            rs.getString("currency"),
            rs.getBigDecimal("opening_balance"),
            rs.getBigDecimal("balance"),
            rs.getTimestamp("created_at").toInstant()
    );

    public Optional<Account> findById(UUID id) {
        String sql = "SELECT id, currency, opening_balance, balance, created_at FROM accounts WHERE id = ?";
        try {
            Account account = jdbcTemplate.queryForObject(sql, ACCOUNT_ROW_MAPPER, id.toString());
            return Optional.ofNullable(account);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<Account> findByIdForUpdate(UUID id) {
        String sql = "SELECT id, currency, opening_balance, balance, created_at FROM accounts WHERE id = ? FOR UPDATE";
        try {
            Account account = jdbcTemplate.queryForObject(sql, ACCOUNT_ROW_MAPPER, id.toString());
            return Optional.ofNullable(account);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public void updateBalance(UUID id, BigDecimal balance) {
        String sql = "UPDATE accounts SET balance = ? WHERE id = ?";
        int updated = jdbcTemplate.update(sql, balance, id.toString());
        if (updated == 0) {
            throw new IllegalStateException("Failed to update balance for non-existent account: " + id);
        }
    }

    public void create(Account account) {
        String sql = "INSERT INTO accounts (id, currency, opening_balance, balance, created_at) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(
                sql,
                account.getId().toString(),
                account.getCurrency(),
                account.getOpeningBalance(),
                account.getBalance(),
                Timestamp.from(account.getCreatedAt())
        );
    }
}
