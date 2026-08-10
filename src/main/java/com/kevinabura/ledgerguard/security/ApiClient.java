package com.kevinabura.ledgerguard.security;

import java.util.UUID;

public record ApiClient(
        UUID id,
        UUID tenantId,
        String name,
        int rateCapacity,
        int rateRefillPerSecond) {}
