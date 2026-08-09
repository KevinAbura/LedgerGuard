package com.kevinabura.ledgerguard.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kevinabura.ledgerguard.error.UnbalancedTransactionException;
import com.kevinabura.ledgerguard.error.ValidationException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LedgerRulesTest {

    @Test
    void acceptsAndNormalizesBalancedEntries() {
        var entries = LedgerRules.validateAndNormalize(List.of(
                new EntryDraft(UUID.randomUUID(), JournalSide.DEBIT, new BigDecimal("10.5")),
                new EntryDraft(UUID.randomUUID(), JournalSide.CREDIT, new BigDecimal("10.500"))));

        assertThat(entries).allSatisfy(entry -> assertThat(entry.amount().scale()).isEqualTo(8));
    }

    @Test
    void rejectsUnbalancedEntries() {
        assertThatThrownBy(() -> LedgerRules.validateAndNormalize(List.of(
                new EntryDraft(UUID.randomUUID(), JournalSide.DEBIT, new BigDecimal("10")),
                new EntryDraft(UUID.randomUUID(), JournalSide.CREDIT, new BigDecimal("9")))))
                .isInstanceOf(UnbalancedTransactionException.class);
    }

    @Test
    void rejectsExcessPrecisionRatherThanRoundingMoney() {
        assertThatThrownBy(() -> LedgerRules.validateAndNormalize(List.of(
                new EntryDraft(UUID.randomUUID(), JournalSide.DEBIT, new BigDecimal("1.000000001")),
                new EntryDraft(UUID.randomUUID(), JournalSide.CREDIT, new BigDecimal("1.000000001")))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("at most 8");
    }

    @Test
    void calculatesNormalSideBalanceAndExactReversal() {
        var amount = new BigDecimal("25.00000000");

        assertThat(LedgerRules.balanceDelta(AccountType.ASSET, JournalSide.DEBIT, amount))
                .isEqualByComparingTo(amount);
        assertThat(LedgerRules.balanceDelta(AccountType.LIABILITY, JournalSide.DEBIT, amount))
                .isEqualByComparingTo(amount.negate());

        var original = List.of(new EntryDraft(UUID.randomUUID(), JournalSide.DEBIT, amount));
        assertThat(LedgerRules.reverse(LedgerRules.reverse(original))).isEqualTo(original);
    }
}
