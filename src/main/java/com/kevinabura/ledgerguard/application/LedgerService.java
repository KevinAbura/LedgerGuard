package com.kevinabura.ledgerguard.application;

import com.kevinabura.ledgerguard.domain.AccountType;
import com.kevinabura.ledgerguard.domain.EntryDraft;
import com.kevinabura.ledgerguard.domain.LedgerRules;
import com.kevinabura.ledgerguard.error.ConflictException;
import com.kevinabura.ledgerguard.error.InsufficientFundsException;
import com.kevinabura.ledgerguard.error.ValidationException;
import com.kevinabura.ledgerguard.persistence.IdempotencyClaim;
import com.kevinabura.ledgerguard.persistence.LedgerRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class LedgerService {
    private static final int IDEMPOTENCY_KEY_MAX_LENGTH = 128;

    private final LedgerRepository repository;
    private final RequestHasher requestHasher;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public LedgerService(
            LedgerRepository repository,
            RequestHasher requestHasher,
            ObjectMapper objectMapper,
            Clock clock) {
        this.repository = repository;
        this.requestHasher = requestHasher;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public AccountView createAccount(
            UUID tenantId,
            String externalId,
            String currency,
            AccountType type,
            boolean allowNegative) {
        if (externalId == null || externalId.isBlank() || externalId.length() > 128) {
            throw new ValidationException("externalId must contain 1 to 128 characters");
        }
        if (currency == null || !currency.matches("[A-Z]{3,12}")) {
            throw new ValidationException("currency must be 3 to 12 uppercase letters");
        }
        if (type == null) {
            throw new ValidationException("accountType is required");
        }
        return repository.createAccount(
                tenantId, UUID.randomUUID(), externalId, currency, type, allowNegative, clock.instant());
    }

    @Transactional(readOnly = true)
    public AccountView getAccount(UUID tenantId, UUID accountId) {
        return repository.getAccount(tenantId, accountId);
    }

    @Transactional(readOnly = true)
    public TransactionView getTransaction(UUID tenantId, UUID transactionId) {
        return repository.getTransaction(tenantId, transactionId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OperationResult<TransactionView> post(
            UUID tenantId, String idempotencyKey, PostTransactionCommand command) {
        validateIdempotencyKey(idempotencyKey);
        validateCommand(command);
        var normalizedEntries = LedgerRules.validateAndNormalize(command.entries());
        var normalizedForHash = new PostTransactionCommand(
                command.externalReference(),
                command.transactionType(),
                command.description(),
                command.effectiveAt(),
                normalizedEntries);
        var now = clock.instant();
        var claim = repository.claimIdempotency(
                tenantId, idempotencyKey, requestHasher.hash(normalizedForHash), "transaction", now);
        if (claim instanceof IdempotencyClaim.Conflict) {
            throw new ConflictException(
                    "idempotency_key_reused", "Idempotency-Key was already used with a different request");
        }
        if (claim instanceof IdempotencyClaim.Replay replay) {
            return OperationResult.replay(readResponse(replay.responseBody()));
        }

        var transactionToPost = new PostTransactionCommand(
                normalizedForHash.externalReference(),
                normalizedForHash.transactionType(),
                normalizedForHash.description(),
                normalizedForHash.effectiveAt() == null ? now : normalizedForHash.effectiveAt(),
                normalizedForHash.entries());
        var transactionId = UUID.randomUUID();
        postNewTransaction(tenantId, transactionId, transactionToPost, null, now);
        var response = repository.getTransaction(tenantId, transactionId);
        repository.completeIdempotency(tenantId, idempotencyKey, 201, writeJson(response), now);
        return OperationResult.fresh(response);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OperationResult<TransactionView> reverse(
            UUID tenantId,
            UUID originalTransactionId,
            String idempotencyKey,
            ReverseTransactionCommand command) {
        validateIdempotencyKey(idempotencyKey);
        if (command == null || command.externalReference() == null || command.externalReference().isBlank()) {
            throw new ValidationException("externalReference is required");
        }
        if (command.externalReference().length() > 128) {
            throw new ValidationException("externalReference must contain at most 128 characters");
        }
        if (command.reason() != null && command.reason().length() > 500) {
            throw new ValidationException("reason must contain at most 500 characters");
        }
        var request = new ReversalHashRequest(originalTransactionId, command);
        var now = clock.instant();
        var claim = repository.claimIdempotency(
                tenantId, idempotencyKey, requestHasher.hash(request), "reversal", now);
        if (claim instanceof IdempotencyClaim.Conflict) {
            throw new ConflictException(
                    "idempotency_key_reused", "Idempotency-Key was already used with a different request");
        }
        if (claim instanceof IdempotencyClaim.Replay replay) {
            return OperationResult.replay(readResponse(replay.responseBody()));
        }

        var original = repository.lockTransaction(tenantId, originalTransactionId);
        if (original.reversalOf() != null) {
            throw new ConflictException("reversal_cannot_be_reversed", "A reversal cannot itself be reversed");
        }
        if (original.reversedBy() != null) {
            throw new ConflictException("already_reversed", "Transaction has already been reversed");
        }

        var reversalId = UUID.randomUUID();
        var reversedEntries = LedgerRules.reverse(original.entries());
        var reversalCommand = new PostTransactionCommand(
                command.externalReference(),
                "REVERSAL",
                command.reason(),
                now,
                reversedEntries);
        postNewTransaction(tenantId, reversalId, reversalCommand, originalTransactionId, now);
        repository.markReversed(originalTransactionId, reversalId);
        var response = repository.getTransaction(tenantId, reversalId);
        repository.completeIdempotency(tenantId, idempotencyKey, 201, writeJson(response), now);
        return OperationResult.fresh(response);
    }

    private void postNewTransaction(
            UUID tenantId,
            UUID transactionId,
            PostTransactionCommand command,
            UUID reversalOf,
            Instant now) {
        var accounts = repository.lockAccounts(
                tenantId, command.entries().stream().map(EntryDraft::accountId).toList());
        String currency = null;
        for (var entry : command.entries()) {
            var account = accounts.get(entry.accountId());
            if (!"ACTIVE".equals(account.status())) {
                throw new ConflictException("account_not_active", "Account " + account.id() + " is not active");
            }
            if (currency == null) {
                currency = account.currency();
            } else if (!currency.equals(account.currency())) {
                throw new ValidationException("All accounts in a transaction must use the same currency");
            }
        }

        repository.insertTransaction(
                tenantId,
                transactionId,
                command.externalReference(),
                command.transactionType(),
                command.description(),
                command.effectiveAt(),
                now,
                reversalOf,
                command.entries(),
                accounts);

        for (var account : accounts.values()) {
            var delta = command.entries().stream()
                    .filter(entry -> entry.accountId().equals(account.id()))
                    .map(entry -> LedgerRules.balanceDelta(account.type(), entry.side(), entry.amount()))
                    .reduce(java.math.BigDecimal.ZERO.setScale(LedgerRules.MONEY_SCALE), java.math.BigDecimal::add);
            var newBalance = LedgerRules.normalize(account.balance().add(delta));
            if (!account.allowNegative() && newBalance.signum() < 0) {
                throw new InsufficientFundsException(account.id());
            }
            repository.updateBalance(tenantId, account.id(), newBalance, account.version(), now);
        }

        var event = new LedgerPostedEvent(
                UUID.randomUUID(), tenantId, transactionId, command.externalReference(), reversalOf, now);
        repository.insertOutboxEvent(
                event.eventId(), tenantId, transactionId, "ledger.transaction.posted.v1", writeJson(event), now);
    }

    private void validateCommand(PostTransactionCommand command) {
        if (command == null) {
            throw new ValidationException("Request body is required");
        }
        if (command.externalReference() == null
                || command.externalReference().isBlank()
                || command.externalReference().length() > 128) {
            throw new ValidationException("externalReference must contain 1 to 128 characters");
        }
        if (command.transactionType() == null
                || command.transactionType().isBlank()
                || command.transactionType().length() > 64) {
            throw new ValidationException("transactionType must contain 1 to 64 characters");
        }
        if (command.description() != null && command.description().length() > 500) {
            throw new ValidationException("description must contain at most 500 characters");
        }
    }

    private void validateIdempotencyKey(String key) {
        if (key == null || key.isBlank() || key.length() > IDEMPOTENCY_KEY_MAX_LENGTH) {
            throw new ValidationException("Idempotency-Key must contain 1 to 128 characters");
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not serialize ledger response", exception);
        }
    }

    private TransactionView readResponse(String body) {
        try {
            return objectMapper.readValue(body, TransactionView.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored idempotency response is invalid", exception);
        }
    }

    private record ReversalHashRequest(UUID originalTransactionId, ReverseTransactionCommand command) {}

    private record LedgerPostedEvent(
            UUID eventId,
            UUID tenantId,
            UUID transactionId,
            String externalReference,
            UUID reversalOf,
            Instant occurredAt) {}
}
