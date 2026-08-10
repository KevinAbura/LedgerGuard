package com.kevinabura.ledgerguard.application;

import com.kevinabura.ledgerguard.domain.AccountType;
import java.math.BigDecimal;
import java.util.UUID;

public record AccountView(
        UUID id,
        String externalId,
        String currency,
        AccountType accountType,
        boolean allowNegative,
        BigDecimal balance,
        long version,
        String status) {}
