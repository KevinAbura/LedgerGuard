package com.kevinabura.ledgerguard.error;

import java.math.BigDecimal;

public class UnbalancedTransactionException extends LedgerException {
    public UnbalancedTransactionException(BigDecimal debit, BigDecimal credit) {
        super("unbalanced_transaction", "Debit total " + debit + " does not equal credit total " + credit);
    }
}
