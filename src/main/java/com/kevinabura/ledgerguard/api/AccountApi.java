package com.kevinabura.ledgerguard.api;

import com.kevinabura.ledgerguard.application.AccountView;
import com.kevinabura.ledgerguard.application.LedgerService;
import com.kevinabura.ledgerguard.domain.AccountType;
import com.kevinabura.ledgerguard.security.ApiSecurityFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/accounts")
public class AccountApi {
    private final LedgerService ledgerService;

    public AccountApi(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountView create(@RequestBody CreateAccountRequest body, HttpServletRequest request) {
        return ledgerService.createAccount(
                tenantId(request),
                body.externalId(),
                body.currency(),
                body.accountType(),
                body.allowNegative());
    }

    @GetMapping("/{accountId}")
    public AccountView get(@PathVariable UUID accountId, HttpServletRequest request) {
        return ledgerService.getAccount(tenantId(request), accountId);
    }

    private UUID tenantId(HttpServletRequest request) {
        return (UUID) request.getAttribute(ApiSecurityFilter.TENANT_ID_ATTRIBUTE);
    }

    public record CreateAccountRequest(
            String externalId,
            String currency,
            AccountType accountType,
            boolean allowNegative) {}
}
