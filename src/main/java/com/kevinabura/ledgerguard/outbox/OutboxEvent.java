package com.kevinabura.ledgerguard.outbox;

import java.util.UUID;

public record OutboxEvent(
        UUID id,
        UUID aggregateId,
        String eventType,
        String payload,
        int attempts,
        UUID lockToken) {}
