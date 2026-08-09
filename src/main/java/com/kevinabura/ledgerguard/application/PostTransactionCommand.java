package com.kevinabura.ledgerguard.application;

import com.kevinabura.ledgerguard.domain.EntryDraft;
import java.time.Instant;
import java.util.List;

public record PostTransactionCommand(
        String externalReference,
        String transactionType,
        String description,
        Instant effectiveAt,
        List<EntryDraft> entries) {}
