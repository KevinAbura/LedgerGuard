package com.kevinabura.ledgerguard.domain;

import java.math.BigDecimal;
import java.util.UUID;

public record EntryDraft(UUID accountId, JournalSide side, BigDecimal amount) {}
