package com.kevinabura.ledgerguard.persistence;

import com.kevinabura.ledgerguard.domain.EntryDraft;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record StoredTransaction(
        UUID id,
        UUID tenantId,
        String externalReference,
        String transactionType,
        String description,
        Instant effectiveAt,
        Instant createdAt,
        UUID reversalOf,
        UUID reversedBy,
        List<EntryDraft> entries) {}
