package com.kevinabura.ledgerguard.error;

public class NotFoundException extends LedgerException {
    public NotFoundException(String resource) {
        super("not_found", resource + " was not found");
    }
}
