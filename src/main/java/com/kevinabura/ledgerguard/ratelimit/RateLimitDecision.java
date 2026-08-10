package com.kevinabura.ledgerguard.ratelimit;

public record RateLimitDecision(
        boolean allowed,
        long remaining,
        long retryAfterMillis,
        boolean fallback) {}
