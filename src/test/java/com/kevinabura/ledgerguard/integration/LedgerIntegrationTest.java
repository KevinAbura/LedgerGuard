package com.kevinabura.ledgerguard.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kevinabura.ledgerguard.application.LedgerService;
import com.kevinabura.ledgerguard.application.PostTransactionCommand;
import com.kevinabura.ledgerguard.application.ReverseTransactionCommand;
import com.kevinabura.ledgerguard.domain.AccountType;
import com.kevinabura.ledgerguard.domain.EntryDraft;
import com.kevinabura.ledgerguard.domain.JournalSide;
import com.kevinabura.ledgerguard.error.ConflictException;
import com.kevinabura.ledgerguard.error.InsufficientFundsException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
    "ledgerguard.outbox.enabled=false",
    "ledgerguard.bootstrap.development-enabled=true"
})
class LedgerIntegrationTest {
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("ledgerguard")
            .withUsername("ledgerguard")
            .withPassword("ledgerguard");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    LedgerService ledger;

    @Test
    void postingIsBalancedIdempotentSerializedAndReversible() throws Exception {
        var suffix = UUID.randomUUID().toString();
        var bank = ledger.createAccount(TENANT_ID, "bank-" + suffix, "USD", AccountType.ASSET, true);
        var wallet = ledger.createAccount(TENANT_ID, "wallet-" + suffix, "USD", AccountType.LIABILITY, false);

        var deposit = command("deposit-" + suffix, bank.id(), JournalSide.DEBIT,
                wallet.id(), JournalSide.CREDIT, "100.00");
        var first = ledger.post(TENANT_ID, "deposit-idem-" + suffix, deposit);
        var replay = ledger.post(TENANT_ID, "deposit-idem-" + suffix, deposit);

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.value().id()).isEqualTo(first.value().id());
        assertThat(ledger.getAccount(TENANT_ID, wallet.id()).balance()).isEqualByComparingTo("100");
        assertThatThrownBy(() -> ledger.post(
                TENANT_ID,
                "deposit-idem-" + suffix,
                command("changed-" + suffix, bank.id(), JournalSide.DEBIT,
                        wallet.id(), JournalSide.CREDIT, "100.00")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("different request");

        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        var attempts = List.of(1, 2).stream().map(index -> executor.submit(() -> {
            start.await();
            return ledger.post(
                    TENANT_ID,
                    "withdraw-idem-" + index + "-" + suffix,
                    command("withdraw-" + index + "-" + suffix,
                            wallet.id(), JournalSide.DEBIT,
                            bank.id(), JournalSide.CREDIT,
                            "80.00"));
        })).toList();
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();

        var successes = 0;
        UUID withdrawalId = null;
        for (var attempt : attempts) {
            try {
                withdrawalId = attempt.get().value().id();
                successes++;
            } catch (java.util.concurrent.ExecutionException exception) {
                assertThat(exception.getCause()).isInstanceOf(InsufficientFundsException.class);
            }
        }
        assertThat(successes).isEqualTo(1);
        assertThat(ledger.getAccount(TENANT_ID, wallet.id()).balance()).isEqualByComparingTo("20");

        var reversal = ledger.reverse(
                TENANT_ID,
                withdrawalId,
                "reverse-idem-" + suffix,
                new ReverseTransactionCommand("reverse-" + suffix, "customer dispute"));
        var reversalReplay = ledger.reverse(
                TENANT_ID,
                withdrawalId,
                "reverse-idem-" + suffix,
                new ReverseTransactionCommand("reverse-" + suffix, "customer dispute"));

        assertThat(reversal.value().reversalOf()).isEqualTo(withdrawalId);
        assertThat(reversalReplay.replayed()).isTrue();
        assertThat(ledger.getAccount(TENANT_ID, wallet.id()).balance()).isEqualByComparingTo("100");
    }

    private static PostTransactionCommand command(
            String reference,
            UUID debitAccount,
            JournalSide debitSide,
            UUID creditAccount,
            JournalSide creditSide,
            String amount) {
        return new PostTransactionCommand(
                reference,
                "TRANSFER",
                "integration test",
                null,
                List.of(
                        new EntryDraft(debitAccount, debitSide, new BigDecimal(amount)),
                        new EntryDraft(creditAccount, creditSide, new BigDecimal(amount))));
    }
}
