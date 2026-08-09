package com.kevinabura.ledgerguard.api;

import com.kevinabura.ledgerguard.application.LedgerService;
import com.kevinabura.ledgerguard.application.PostTransactionCommand;
import com.kevinabura.ledgerguard.application.ReverseTransactionCommand;
import com.kevinabura.ledgerguard.application.TransactionView;
import com.kevinabura.ledgerguard.domain.EntryDraft;
import com.kevinabura.ledgerguard.domain.JournalSide;
import com.kevinabura.ledgerguard.security.ApiSecurityFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/transactions")
public class TransactionApi {
    private final LedgerService ledgerService;

    public TransactionApi(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionView post(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PostTransactionRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        var result = ledgerService.post(
                tenantId(request),
                idempotencyKey,
                new PostTransactionCommand(
                        body.externalReference(),
                        body.transactionType(),
                        body.description(),
                        body.effectiveAt(),
                        body.entries() == null ? null : body.entries().stream()
                                .map(entry -> new EntryDraft(entry.accountId(), entry.side(), entry.amount()))
                                .toList()));
        response.setHeader("Idempotent-Replayed", Boolean.toString(result.replayed()));
        return result.value();
    }

    @PostMapping("/{transactionId}/reverse")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionView reverse(
            @PathVariable UUID transactionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ReverseRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        var result = ledgerService.reverse(
                tenantId(request),
                transactionId,
                idempotencyKey,
                new ReverseTransactionCommand(body.externalReference(), body.reason()));
        response.setHeader("Idempotent-Replayed", Boolean.toString(result.replayed()));
        return result.value();
    }

    @GetMapping("/{transactionId}")
    public TransactionView get(@PathVariable UUID transactionId, HttpServletRequest request) {
        return ledgerService.getTransaction(tenantId(request), transactionId);
    }

    private UUID tenantId(HttpServletRequest request) {
        return (UUID) request.getAttribute(ApiSecurityFilter.TENANT_ID_ATTRIBUTE);
    }

    public record PostTransactionRequest(
            String externalReference,
            String transactionType,
            String description,
            Instant effectiveAt,
            List<EntryRequest> entries) {}

    public record EntryRequest(UUID accountId, JournalSide side, BigDecimal amount) {}

    public record ReverseRequest(String externalReference, String reason) {}
}
