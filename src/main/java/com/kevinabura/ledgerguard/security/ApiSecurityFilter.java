package com.kevinabura.ledgerguard.security;

import com.kevinabura.ledgerguard.ratelimit.DistributedRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

@Component
public class ApiSecurityFilter extends OncePerRequestFilter {
    public static final String TENANT_ID_ATTRIBUTE = ApiSecurityFilter.class.getName() + ".tenantId";
    private static final Pattern UUID_SEGMENT = Pattern.compile(
            "/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final ApiClientRepository clients;
    private final DistributedRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public ApiSecurityFilter(
            ApiClientRepository clients,
            DistributedRateLimiter rateLimiter,
            ObjectMapper objectMapper) {
        this.clients = clients;
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        var traceId = request.getHeader("X-Request-Id");
        if (traceId == null || traceId.isBlank() || traceId.length() > 128) {
            traceId = UUID.randomUUID().toString();
        }
        MDC.put("traceId", traceId);
        response.setHeader("X-Request-Id", traceId);
        try {
            var rawKey = request.getHeader("X-API-Key");
            if (rawKey == null || rawKey.isBlank() || rawKey.length() > 512) {
                writeError(response, 401, "unauthorized", "X-API-Key is required");
                return;
            }
            var client = clients.findActiveByRawKey(rawKey).orElse(null);
            if (client == null) {
                writeError(response, 401, "unauthorized", "API key is invalid or inactive");
                return;
            }

            var route = request.getMethod() + ":" + UUID_SEGMENT.matcher(request.getRequestURI()).replaceAll("/{id}");
            var decision = rateLimiter.consume(
                    client.id() + ":" + route, client.rateCapacity(), client.rateRefillPerSecond());
            response.setHeader("X-RateLimit-Limit", Integer.toString(client.rateCapacity()));
            response.setHeader("X-RateLimit-Remaining", Long.toString(decision.remaining()));
            response.setHeader("X-RateLimit-Policy", decision.fallback() ? "local-fallback" : "redis-token-bucket");
            if (!decision.allowed()) {
                var retrySeconds = Math.max(1, (decision.retryAfterMillis() + 999) / 1000);
                response.setHeader("Retry-After", Long.toString(retrySeconds));
                writeError(response, 429, "rate_limit_exceeded", "Request rate exceeded; retry later");
                return;
            }

            request.setAttribute(TENANT_ID_ATTRIBUTE, client.tenantId());
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
        }
    }

    private void writeError(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), Map.of("code", code, "message", message));
    }
}
