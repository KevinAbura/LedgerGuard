package com.kevinabura.ledgerguard.application;

import com.kevinabura.ledgerguard.domain.JournalSide;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TransactionView(
        UUID id,
        String externalReference,
        String transactionType,
        String description,
        Instant effectiveAt,
        Instant createdAt,
        UUID reversalOf,
        UUID reversedBy,
        List<EntryView> entries) {

    public record EntryView(
            UUID id,
            UUID accountId,
            JournalSide side,
            BigDecimal amount,
            String currency) {}
}
