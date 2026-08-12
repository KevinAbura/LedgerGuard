package com.kevinabura.ledgerguard.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.kevinabura.ledgerguard.application.LedgerService;
import com.kevinabura.ledgerguard.application.PostTransactionCommand;
import com.kevinabura.ledgerguard.application.ReverseTransactionCommand;
import com.kevinabura.ledgerguard.domain.AccountType;
import com.kevinabura.ledgerguard.domain.EntryDraft;
import com.kevinabura.ledgerguard.domain.JournalSide;
import com.kevinabura.ledgerguard.error.ConflictException;
import com.kevinabura.ledgerguard.error.InsufficientFundsException;
import com.kevinabura.ledgerguard.error.NotFoundException;
import com.kevinabura.ledgerguard.outbox.OutboxFailureResult;
import com.kevinabura.ledgerguard.outbox.OutboxRepository;
import com.kevinabura.ledgerguard.persistence.IdempotencyClaim;
import com.kevinabura.ledgerguard.persistence.LedgerRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
    "ledgerguard.outbox.enabled=false",
    "ledgerguard.bootstrap.development-enabled=true"
})
class LedgerIntegrationTest {
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("ledgerguard")
            .withUsername("ledgerguard")
            .withPassword("ledgerguard");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    LedgerService ledger;

    @Autowired
    LedgerRepository repository;

    @Autowired
    OutboxRepository outbox;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transactions;

    @Test
    void postingIsBalancedIdempotentSerializedAndReversible() throws Exception {
        var suffix = UUID.randomUUID().toString();
        var bank = ledger.createAccount(TENANT_ID, "bank-" + suffix, "USD", AccountType.ASSET, true);
        var wallet = ledger.createAccount(TENANT_ID, "wallet-" + suffix, "USD", AccountType.LIABILITY, false);

        var deposit = command("deposit-" + suffix, bank.id(), JournalSide.DEBIT,
                wallet.id(), JournalSide.CREDIT, "100.00");
        var first = ledger.post(TENANT_ID, "deposit-idem-" + suffix, deposit);
        var replay = ledger.post(TENANT_ID, "deposit-idem-" + suffix, deposit);

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.value().id()).isEqualTo(first.value().id());
        assertThat(ledger.getAccount(TENANT_ID, wallet.id()).balance()).isEqualByComparingTo("100");
        assertThatThrownBy(() -> ledger.post(
                TENANT_ID,
                "deposit-idem-" + suffix,
                command("changed-" + suffix, bank.id(), JournalSide.DEBIT,
                        wallet.id(), JournalSide.CREDIT, "100.00")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("different request");

        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        var attempts = List.of(1, 2).stream().map(index -> executor.submit(() -> {
            start.await();
            return ledger.post(
                    TENANT_ID,
                    "withdraw-idem-" + index + "-" + suffix,
                    command("withdraw-" + index + "-" + suffix,
                            wallet.id(), JournalSide.DEBIT,
                            bank.id(), JournalSide.CREDIT,
                            "80.00"));
        })).toList();
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();

        var successes = 0;
        UUID withdrawalId = null;
        for (var attempt : attempts) {
            try {
                withdrawalId = attempt.get().value().id();
                successes++;
            } catch (java.util.concurrent.ExecutionException exception) {
                assertThat(exception.getCause()).isInstanceOf(InsufficientFundsException.class);
            }
        }
        assertThat(successes).isEqualTo(1);
        assertThat(ledger.getAccount(TENANT_ID, wallet.id()).balance()).isEqualByComparingTo("20");

        var reversal = ledger.reverse(
                TENANT_ID,
                withdrawalId,
                "reverse-idem-" + suffix,
                new ReverseTransactionCommand("reverse-" + suffix, "customer dispute"));
        var reversalReplay = ledger.reverse(
                TENANT_ID,
                withdrawalId,
                "reverse-idem-" + suffix,
                new ReverseTransactionCommand("reverse-" + suffix, "customer dispute"));

        assertThat(reversal.value().reversalOf()).isEqualTo(withdrawalId);
        assertThat(reversalReplay.replayed()).isTrue();
        assertThat(ledger.getAccount(TENANT_ID, wallet.id()).balance()).isEqualByComparingTo("100");
    }

    @Test
    void postedJournalsAreSealedAgainstHeaderEntryAndDeleteMutation() {
        var suffix = UUID.randomUUID().toString();
        var debit = ledger.createAccount(TENANT_ID, "sealed-debit-" + suffix, "USD", AccountType.ASSET, true);
        var credit = ledger.createAccount(TENANT_ID, "sealed-credit-" + suffix, "USD", AccountType.LIABILITY, false);
        var posted = ledger.post(
                TENANT_ID,
                "sealed-idem-" + suffix,
                command("sealed-" + suffix, debit.id(), JournalSide.DEBIT,
                        credit.id(), JournalSide.CREDIT, "10.00"));

        var status = jdbc.queryForObject(
                "SELECT status FROM journal_transactions WHERE id = ?", String.class, posted.value().id());
        assertThat(status).isEqualTo("POSTED");
        assertThat(jdbc.queryForObject(
                "SELECT sealed_at IS NOT NULL FROM journal_transactions WHERE id = ?",
                Boolean.class,
                posted.value().id())).isTrue();

        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE journal_transactions SET description = 'tampered' WHERE id = ?",
                        posted.value().id()))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("journal transactions are immutable after posting");
        assertThatThrownBy(() -> insertEntry(
                        posted.value().id(), TENANT_ID, debit.id(), "DEBIT", "1.00", "USD"))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("cannot append entries to posted transaction " + posted.value().id());
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE journal_entries SET amount = 11 WHERE transaction_id = ?",
                        posted.value().id()))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("journal entries are immutable");
        assertThatThrownBy(() -> jdbc.update(
                        "DELETE FROM journal_transactions WHERE id = ?", posted.value().id()))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("journal transactions are immutable");
    }

    @Test
    void draftsCannotCommitOrSealWithoutABalancedJournal() {
        var suffix = UUID.randomUUID().toString();
        var account = ledger.createAccount(TENANT_ID, "draft-account-" + suffix, "USD", AccountType.ASSET, true);

        var abandonedDraft = UUID.randomUUID();
        var commitFailure = catchThrowable(() -> transactions.executeWithoutResult(status ->
                insertTransaction(abandonedDraft, TENANT_ID, "abandoned-" + suffix, null)));
        assertThat(commitFailure).isNotNull();
        assertThat(rootCause(commitFailure).getMessage()).contains("must be sealed before commit");

        var unbalanced = UUID.randomUUID();
        var sealFailure = catchThrowable(() -> transactions.executeWithoutResult(status -> {
            insertTransaction(unbalanced, TENANT_ID, "unbalanced-" + suffix, null);
            insertEntry(unbalanced, TENANT_ID, account.id(), "DEBIT", "10.00", "USD");
            sealTransaction(unbalanced);
        }));
        assertThat(sealFailure).isNotNull();
        assertThat(rootCause(sealFailure).getMessage()).contains("cannot be sealed");
    }

    @Test
    void poisonOutboxEventsDeadLetterAndCanBeExplicitlyRequeued() {
        var eventId = UUID.randomUUID();
        repository.insertOutboxEvent(
                eventId,
                TENANT_ID,
                UUID.randomUUID(),
                "ledger.transaction.posted.v1",
                "{}",
                Instant.now());

        var firstClaim = outbox.claimBatch(1_000).stream()
                .filter(event -> event.id().equals(eventId))
                .findFirst()
                .orElseThrow();
        assertThat(outbox.markFailed(firstClaim, new IllegalStateException("first failure"), 2))
                .isEqualTo(OutboxFailureResult.RETRY_SCHEDULED);

        jdbc.update("UPDATE outbox_events SET next_attempt_at = now() WHERE id = ?", eventId);
        var secondClaim = outbox.claimBatch(1_000).stream()
                .filter(event -> event.id().equals(eventId))
                .findFirst()
                .orElseThrow();
        assertThat(outbox.markFailed(secondClaim, new IllegalStateException("poison event"), 2))
                .isEqualTo(OutboxFailureResult.DEAD_LETTERED);
        assertThat(jdbc.queryForObject(
                "SELECT dead_lettered_at IS NOT NULL FROM outbox_events WHERE id = ?",
                Boolean.class,
                eventId)).isTrue();
        assertThat(outbox.claimBatch(1_000)).noneMatch(event -> event.id().equals(eventId));

        assertThat(jdbc.queryForObject(
                "SELECT ledgerguard_requeue_outbox_event(?)", Boolean.class, eventId)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT dead_lettered_at IS NULL AND attempts = 0 FROM outbox_events WHERE id = ?",
                Boolean.class,
                eventId)).isTrue();
        assertThat(outbox.claimBatch(1_000)).anyMatch(event -> event.id().equals(eventId));
    }

    @Test
    void repositoryAndDatabaseEnforceTenantAndCurrencyBoundaries() {
        var suffix = UUID.randomUUID().toString();
        var otherTenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", otherTenant, "Other tenant " + suffix);

        var usd = ledger.createAccount(TENANT_ID, "usd-" + suffix, "USD", AccountType.ASSET, true);
        var eur = ledger.createAccount(TENANT_ID, "eur-" + suffix, "EUR", AccountType.ASSET, true);
        var protectedWallet = ledger.createAccount(
                TENANT_ID, "protected-" + suffix, "USD", AccountType.LIABILITY, false);

        assertThatThrownBy(() -> ledger.getAccount(otherTenant, usd.id()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE ledger_accounts SET balance = -1 WHERE id = ?", protectedWallet.id()))
                .isInstanceOf(DataIntegrityViolationException.class);

        var crossCurrencyTransaction = UUID.randomUUID();
        var crossCurrencyFailure = catchThrowable(() -> transactions.executeWithoutResult(status -> {
            insertTransaction(crossCurrencyTransaction, TENANT_ID, "cross-currency-" + suffix, null);
            insertEntry(crossCurrencyTransaction, TENANT_ID, usd.id(), "DEBIT", "10.00", "USD");
            insertEntry(crossCurrencyTransaction, TENANT_ID, eur.id(), "CREDIT", "10.00", "EUR");
            sealTransaction(crossCurrencyTransaction);
        }));
        assertThat(crossCurrencyFailure).isNotNull();
        assertThat(rootCause(crossCurrencyFailure).getMessage()).contains("currencies 2");

        var otherDebit = ledger.createAccount(otherTenant, "other-debit-" + suffix, "USD", AccountType.ASSET, true);
        var otherCredit = ledger.createAccount(
                otherTenant, "other-credit-" + suffix, "USD", AccountType.LIABILITY, false);
        var otherTransaction = ledger.post(
                otherTenant,
                "other-idem-" + suffix,
                command("other-" + suffix, otherDebit.id(), JournalSide.DEBIT,
                        otherCredit.id(), JournalSide.CREDIT, "1.00")).value().id();
        assertThatThrownBy(() -> insertTransaction(
                        UUID.randomUUID(), TENANT_ID, "cross-tenant-reversal-" + suffix, otherTransaction))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void idempotencyKeysCannotReplayAcrossResourceTypes() {
        var key = "resource-type-" + UUID.randomUUID();
        var hash = "a".repeat(64);
        var now = Instant.now();

        assertThat(repository.claimIdempotency(TENANT_ID, key, hash, "transaction", now))
                .isInstanceOf(IdempotencyClaim.New.class);
        repository.completeIdempotency(TENANT_ID, key, 201, "{}", now);

        assertThat(repository.claimIdempotency(TENANT_ID, key, hash, "reversal", now))
                .isInstanceOf(IdempotencyClaim.Conflict.class);
    }

    private void insertTransaction(UUID id, UUID tenantId, String reference, UUID reversalOf) {
        var now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO journal_transactions
                    (id, tenant_id, external_reference, transaction_type, effective_at, created_at,
                     reversal_of, status, sealed_at)
                VALUES (?, ?, ?, 'TEST', ?, ?, ?, 'DRAFT', NULL)
                """, id, tenantId, reference, now, now, reversalOf);
    }

    private void sealTransaction(UUID id) {
        jdbc.update(
                "UPDATE journal_transactions SET status = 'POSTED', sealed_at = now() WHERE id = ?",
                id);
    }

    private void insertEntry(
            UUID transactionId,
            UUID tenantId,
            UUID accountId,
            String side,
            String amount,
            String currency) {
        jdbc.update("""
                INSERT INTO journal_entries
                    (id, tenant_id, transaction_id, account_id, side, amount, currency)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), tenantId, transactionId, accountId, side, new BigDecimal(amount), currency);
    }

    private static Throwable rootCause(Throwable failure) {
        var cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static PostTransactionCommand command(
            String reference,
            UUID debitAccount,
            JournalSide debitSide,
            UUID creditAccount,
            JournalSide creditSide,
            String amount) {
        return new PostTransactionCommand(
                reference,
                "TRANSFER",
                "integration test",
                null,
                List.of(
                        new EntryDraft(debitAccount, debitSide, new BigDecimal(amount)),
                        new EntryDraft(creditAccount, creditSide, new BigDecimal(amount))));
    }
}
