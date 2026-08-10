package com.kevinabura.ledgerguard.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ApiClientRepository {
    private final JdbcTemplate jdbc;

    public ApiClientRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ApiClient> findActiveByRawKey(String rawKey) {
        var clients = jdbc.query("""
                SELECT id, tenant_id, name, rate_capacity, rate_refill_per_second
                  FROM api_clients
                 WHERE api_key_hash = ? AND active = TRUE
                """, (rs, rowNum) -> new ApiClient(
                rs.getObject("id", java.util.UUID.class),
                rs.getObject("tenant_id", java.util.UUID.class),
                rs.getString("name"),
                rs.getInt("rate_capacity"),
                rs.getInt("rate_refill_per_second")), sha256(rawKey));
        return clients.stream().findFirst();
    }

    private static String sha256(String rawKey) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(rawKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
