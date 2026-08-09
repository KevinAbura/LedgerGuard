package com.kevinabura.ledgerguard.application;

public record OperationResult<T>(T value, boolean replayed) {
    public static <T> OperationResult<T> fresh(T value) {
        return new OperationResult<>(value, false);
    }

    public static <T> OperationResult<T> replay(T value) {
        return new OperationResult<>(value, true);
    }
}
