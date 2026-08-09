package com.kevinabura.ledgerguard.error;

public class ConflictException extends LedgerException {
    public ConflictException(String code, String message) {
        super(code, message);
    }
}
