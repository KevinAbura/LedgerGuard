package com.kevinabura.ledgerguard.error;

public class ValidationException extends LedgerException {
    public ValidationException(String message) {
        super("validation_error", message);
    }
}
