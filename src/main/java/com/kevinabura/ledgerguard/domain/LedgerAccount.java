package com.kevinabura.ledgerguard.domain;

import java.math.BigDecimal;
import java.util.UUID;

public record LedgerAccount(
        UUID id,
        UUID tenantId,
        String externalId,
        String currency,
        AccountType type,
        boolean allowNegative,
        BigDecimal balance,
        long version,
        String status) {}
