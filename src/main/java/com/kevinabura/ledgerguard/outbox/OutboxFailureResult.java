package com.kevinabura.ledgerguard.outbox;

public enum OutboxFailureResult {
    RETRY_SCHEDULED,
    DEAD_LETTERED,
    LEASE_LOST
}
