package com.kevinabura.ledgerguard.benchmark;

import com.kevinabura.ledgerguard.domain.AccountType;
import com.kevinabura.ledgerguard.domain.EntryDraft;
import com.kevinabura.ledgerguard.domain.JournalSide;
import com.kevinabura.ledgerguard.domain.LedgerRules;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class LedgerRulesBenchmark {
    private List<EntryDraft> twentyEntries;
    private BigDecimal amount;

    @Setup
    public void setUp() {
        amount = new BigDecimal("123.45670000");
        var entries = new ArrayList<EntryDraft>();
        for (int index = 0; index < 10; index++) {
            entries.add(new EntryDraft(UUID.randomUUID(), JournalSide.DEBIT, amount));
            entries.add(new EntryDraft(UUID.randomUUID(), JournalSide.CREDIT, amount));
        }
        twentyEntries = List.copyOf(entries);
    }

    @Benchmark
    public List<EntryDraft> validateTwentyEntryJournal() {
        return LedgerRules.validateAndNormalize(twentyEntries);
    }

    @Benchmark
    public BigDecimal calculateBalanceDelta() {
        return LedgerRules.balanceDelta(AccountType.LIABILITY, JournalSide.DEBIT, amount);
    }
}
