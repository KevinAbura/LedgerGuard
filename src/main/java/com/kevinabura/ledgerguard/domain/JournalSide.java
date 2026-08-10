package com.kevinabura.ledgerguard.domain;

public enum JournalSide {
    DEBIT,
    CREDIT;

    public JournalSide opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}
