package com.kevinabura.ledgerguard.domain;

import com.kevinabura.ledgerguard.error.UnbalancedTransactionException;
import com.kevinabura.ledgerguard.error.ValidationException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public final class LedgerRules {
    public static final int MONEY_SCALE = 8;

    private LedgerRules() {}

    public static List<EntryDraft> validateAndNormalize(List<EntryDraft> entries) {
        if (entries == null || entries.size() < 2) {
            throw new ValidationException("A journal transaction requires at least two entries");
        }

        var normalized = entries.stream().map(entry -> {
            if (entry.accountId() == null || entry.side() == null || entry.amount() == null) {
                throw new ValidationException("Each entry requires accountId, side, and amount");
            }
            var amount = normalize(entry.amount());
            if (amount.signum() <= 0) {
                throw new ValidationException("Entry amount must be greater than zero");
            }
            return new EntryDraft(entry.accountId(), entry.side(), amount);
        }).toList();

        var debit = total(normalized, JournalSide.DEBIT);
        var credit = total(normalized, JournalSide.CREDIT);
        if (debit.compareTo(credit) != 0) {
            throw new UnbalancedTransactionException(debit, credit);
        }
        return normalized;
    }

    public static BigDecimal balanceDelta(AccountType type, JournalSide side, BigDecimal amount) {
        var normalized = normalize(amount);
        return type.normalSide() == side ? normalized : normalized.negate();
    }

    public static List<EntryDraft> reverse(List<EntryDraft> entries) {
        return entries.stream()
                .map(entry -> new EntryDraft(entry.accountId(), entry.side().opposite(), normalize(entry.amount())))
                .toList();
    }

    public static BigDecimal normalize(BigDecimal amount) {
        try {
            return amount.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new ValidationException("Amounts support at most " + MONEY_SCALE + " decimal places");
        }
    }

    public static BigDecimal total(List<EntryDraft> entries, JournalSide side) {
        return entries.stream()
                .filter(entry -> entry.side() == side)
                .map(EntryDraft::amount)
                .reduce(BigDecimal.ZERO.setScale(MONEY_SCALE), BigDecimal::add);
    }
}
