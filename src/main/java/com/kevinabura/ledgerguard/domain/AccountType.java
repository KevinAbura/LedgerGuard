package com.kevinabura.ledgerguard.domain;

public enum AccountType {
    ASSET(JournalSide.DEBIT),
    LIABILITY(JournalSide.CREDIT);

    private final JournalSide normalSide;

    AccountType(JournalSide normalSide) {
        this.normalSide = normalSide;
    }

    public JournalSide normalSide() {
        return normalSide;
    }
}
