package com.kevinabura.ledgerguard.persistence;

import com.kevinabura.ledgerguard.application.AccountView;
import com.kevinabura.ledgerguard.application.TransactionView;
import com.kevinabura.ledgerguard.domain.AccountType;
import com.kevinabura.ledgerguard.domain.EntryDraft;
import com.kevinabura.ledgerguard.domain.JournalSide;
import com.kevinabura.ledgerguard.domain.LedgerAccount;
import com.kevinabura.ledgerguard.error.ConflictException;
import com.kevinabura.ledgerguard.error.NotFoundException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LedgerRepository {
    private final JdbcTemplate jdbc;

    public LedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public AccountView createAccount(
            UUID tenantId,
            UUID id,
            String externalId,
            String currency,
            AccountType type,
            boolean allowNegative,
            Instant now) {
        try {
            jdbc.update("""
                    INSERT INTO ledger_accounts
                        (id, tenant_id, external_id, currency, account_type, allow_negative, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, tenantId, externalId, currency, type.name(), allowNegative,
                    Timestamp.from(now), Timestamp.from(now));
        } catch (DataIntegrityViolationException exception) {
            if (!isUniqueViolation(exception)) {
                throw exception;
            }
            throw new ConflictException("account_exists", "An account with this externalId already exists");
        }
        return getAccount(tenantId, id);
    }

    public AccountView getAccount(UUID tenantId, UUID id) {
        var accounts = jdbc.query("""
                SELECT id, external_id, currency, account_type, allow_negative, balance, version, status
                  FROM ledger_accounts
                 WHERE tenant_id = ? AND id = ?
                """, LedgerRepository::mapAccountView, tenantId, id);
        if (accounts.isEmpty()) {
            throw new NotFoundException("Account");
        }
        return accounts.getFirst();
    }

    public Map<UUID, LedgerAccount> lockAccounts(UUID tenantId, List<UUID> ids) {
        var uniqueSortedIds = ids.stream().distinct().sorted(Comparator.comparing(UUID::toString)).toList();
        var result = new ArrayList<LedgerAccount>();
        // One row at a time in a deterministic order avoids array ordering ambiguity and deadlocks.
        for (var id : uniqueSortedIds) {
            var accounts = jdbc.query("""
                    SELECT id, tenant_id, external_id, currency, account_type,
                           allow_negative, balance, version, status
                      FROM ledger_accounts
                     WHERE tenant_id = ? AND id = ?
                     FOR UPDATE
                    """, LedgerRepository::mapLedgerAccount, tenantId, id);
            if (accounts.isEmpty()) {
                throw new NotFoundException("Account " + id);
            }
            result.add(accounts.getFirst());
        }
        return result.stream().collect(Collectors.toMap(LedgerAccount::id, Function.identity()));
    }

    public IdempotencyClaim claimIdempotency(
            UUID tenantId, String key, String requestHash, String resourceType, Instant now) {
        var inserted = jdbc.update("""
                INSERT INTO idempotency_records
                    (tenant_id, idempotency_key, request_hash, resource_type, created_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
                """, tenantId, key, requestHash, resourceType, Timestamp.from(now));
        if (inserted == 1) {
            return new IdempotencyClaim.New();
        }

        return jdbc.queryForObject("""
                SELECT request_hash, resource_type, response_body::text
                  FROM idempotency_records
                 WHERE tenant_id = ? AND idempotency_key = ?
                """, (rs, rowNum) -> {
                    if (!resourceType.equals(rs.getString("resource_type"))
                            || !requestHash.equals(rs.getString("request_hash"))) {
                        return new IdempotencyClaim.Conflict();
                    }
                    var body = rs.getString("response_body");
                    if (body == null) {
                        throw new ConflictException("idempotency_in_progress", "The original request is still in progress");
                    }
                    return new IdempotencyClaim.Replay(body);
                }, tenantId, key);
    }

    public void completeIdempotency(UUID tenantId, String key, int status, String responseBody, Instant now) {
        var updated = jdbc.update("""
                UPDATE idempotency_records
                   SET response_status = ?, response_body = ?::jsonb, completed_at = ?
                 WHERE tenant_id = ? AND idempotency_key = ? AND response_body IS NULL
                """, status, responseBody, Timestamp.from(now), tenantId, key);
        if (updated != 1) {
            throw new IllegalStateException("Idempotency record was not completed exactly once");
        }
    }

    public void insertTransaction(
            UUID tenantId,
            UUID transactionId,
            String externalReference,
            String transactionType,
            String description,
            Instant effectiveAt,
            Instant now,
            UUID reversalOf,
            List<EntryDraft> entries,
            Map<UUID, LedgerAccount> accounts) {
        try {
            jdbc.update("""
                    INSERT INTO journal_transactions
                        (id, tenant_id, external_reference, transaction_type, description,
                         effective_at, created_at, reversal_of)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, transactionId, tenantId, externalReference, transactionType, description,
                    Timestamp.from(effectiveAt), Timestamp.from(now), reversalOf);

            for (var entry : entries) {
                jdbc.update("""
                        INSERT INTO journal_entries
                            (id, tenant_id, transaction_id, account_id, side, amount, currency, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """, UUID.randomUUID(), tenantId, transactionId, entry.accountId(), entry.side().name(),
                        entry.amount(), accounts.get(entry.accountId()).currency(), Timestamp.from(now));
            }
        } catch (DataIntegrityViolationException exception) {
            if (!isUniqueViolation(exception)) {
                throw exception;
            }
            throw new ConflictException(
                    "external_reference_exists",
                    "A transaction with this externalReference already exists or was already reversed");
        }
    }

    public void updateBalance(
            UUID tenantId, UUID accountId, BigDecimal newBalance, long expectedVersion, Instant now) {
        var updated = jdbc.update("""
                UPDATE ledger_accounts
                   SET balance = ?, version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND id = ? AND version = ?
                """, newBalance, Timestamp.from(now), tenantId, accountId, expectedVersion);
        if (updated != 1) {
            throw new ConflictException("concurrent_modification", "Account version changed while posting");
        }
    }

    public StoredTransaction lockTransaction(UUID tenantId, UUID transactionId) {
        var transactions = jdbc.query("""
                SELECT id, tenant_id, external_reference, transaction_type, description,
                       effective_at, created_at, reversal_of, reversed_by
                  FROM journal_transactions
                 WHERE tenant_id = ? AND id = ?
                 FOR UPDATE
                """, (rs, rowNum) -> new StoredTransaction(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("external_reference"),
                rs.getString("transaction_type"),
                rs.getString("description"),
                rs.getTimestamp("effective_at").toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getObject("reversal_of", UUID.class),
                rs.getObject("reversed_by", UUID.class),
                loadEntries(transactionId)), tenantId, transactionId);
        if (transactions.isEmpty()) {
            throw new NotFoundException("Transaction");
        }
        return transactions.getFirst();
    }

    public void markReversed(UUID originalId, UUID reversalId) {
        var updated = jdbc.update("""
                UPDATE journal_transactions
                   SET reversed_by = ?
                 WHERE id = ? AND reversed_by IS NULL
                """, reversalId, originalId);
        if (updated != 1) {
            throw new ConflictException("already_reversed", "Transaction has already been reversed");
        }
    }

    public TransactionView getTransaction(UUID tenantId, UUID transactionId) {
        var transactions = jdbc.query("""
                SELECT id, external_reference, transaction_type, description,
                       effective_at, created_at, reversal_of, reversed_by
                  FROM journal_transactions
                 WHERE tenant_id = ? AND id = ?
                """, (rs, rowNum) -> new TransactionView(
                rs.getObject("id", UUID.class),
                rs.getString("external_reference"),
                rs.getString("transaction_type"),
                rs.getString("description"),
                rs.getTimestamp("effective_at").toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getObject("reversal_of", UUID.class),
                rs.getObject("reversed_by", UUID.class),
                loadEntryViews(transactionId)), tenantId, transactionId);
        if (transactions.isEmpty()) {
            throw new NotFoundException("Transaction");
        }
        return transactions.getFirst();
    }

    public void insertOutboxEvent(
            UUID id,
            UUID tenantId,
            UUID aggregateId,
            String eventType,
            String payload,
            Instant now) {
        jdbc.update("""
                INSERT INTO outbox_events
                    (id, tenant_id, aggregate_type, aggregate_id, event_type, payload, created_at, next_attempt_at)
                VALUES (?, ?, 'journal_transaction', ?, ?, ?::jsonb, ?, ?)
                """, id, tenantId, aggregateId, eventType, payload, Timestamp.from(now), Timestamp.from(now));
    }

    private List<EntryDraft> loadEntries(UUID transactionId) {
        return jdbc.query("""
                SELECT account_id, side, amount
                  FROM journal_entries
                 WHERE transaction_id = ?
                 ORDER BY created_at, id
                """, (rs, rowNum) -> new EntryDraft(
                rs.getObject("account_id", UUID.class),
                JournalSide.valueOf(rs.getString("side")),
                rs.getBigDecimal("amount")), transactionId);
    }

    private List<TransactionView.EntryView> loadEntryViews(UUID transactionId) {
        return jdbc.query("""
                SELECT id, account_id, side, amount, currency
                  FROM journal_entries
                 WHERE transaction_id = ?
                 ORDER BY created_at, id
                """, (rs, rowNum) -> new TransactionView.EntryView(
                rs.getObject("id", UUID.class),
                rs.getObject("account_id", UUID.class),
                JournalSide.valueOf(rs.getString("side")),
                rs.getBigDecimal("amount"),
                rs.getString("currency")), transactionId);
    }

    private static boolean isUniqueViolation(Throwable error) {
        for (var cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException && "23505".equals(sqlException.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private static AccountView mapAccountView(ResultSet rs, int rowNum) throws SQLException {
        return new AccountView(
                rs.getObject("id", UUID.class),
                rs.getString("external_id"),
                rs.getString("currency"),
                AccountType.valueOf(rs.getString("account_type")),
                rs.getBoolean("allow_negative"),
                rs.getBigDecimal("balance"),
                rs.getLong("version"),
                rs.getString("status"));
    }

    private static LedgerAccount mapLedgerAccount(ResultSet rs, int rowNum) throws SQLException {
        return new LedgerAccount(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("external_id"),
                rs.getString("currency"),
                AccountType.valueOf(rs.getString("account_type")),
                rs.getBoolean("allow_negative"),
                rs.getBigDecimal("balance"),
                rs.getLong("version"),
                rs.getString("status"));
    }
}
