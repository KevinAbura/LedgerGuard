package com.kevinabura.ledgerguard.persistence;

public sealed interface IdempotencyClaim {
    record New() implements IdempotencyClaim {}
    record Replay(String responseBody) implements IdempotencyClaim {}
    record Conflict() implements IdempotencyClaim {}
}
