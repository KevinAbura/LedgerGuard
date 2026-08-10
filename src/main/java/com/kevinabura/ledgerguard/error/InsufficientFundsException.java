package com.kevinabura.ledgerguard.error;

import java.util.UUID;

public class InsufficientFundsException extends LedgerException {
    public InsufficientFundsException(UUID accountId) {
        super("insufficient_funds", "Posting would make account " + accountId + " negative");
    }
}
