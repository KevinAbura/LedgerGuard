package com.kevinabura.ledgerguard.config;

import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "ledgerguard.bootstrap.development-enabled", havingValue = "true")
public class DevelopmentBootstrap implements ApplicationRunner {
    public static final UUID DEVELOPMENT_TENANT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DEVELOPMENT_CLIENT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final String DEVELOPMENT_KEY_HASH =
            "619145a5a3afec298b26789a64eb94b8be41674234bac2ce286b831cd3927197";

    private final JdbcTemplate jdbc;

    public DevelopmentBootstrap(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        jdbc.update("""
                INSERT INTO tenants (id, name)
                VALUES (?, 'Local Development')
                ON CONFLICT (id) DO NOTHING
                """, DEVELOPMENT_TENANT_ID);
        jdbc.update("""
                INSERT INTO api_clients
                    (id, tenant_id, name, api_key_hash, rate_capacity, rate_refill_per_second)
                VALUES (?, ?, 'Local CLI', ?, 100, 50)
                ON CONFLICT (id) DO NOTHING
                """, DEVELOPMENT_CLIENT_ID, DEVELOPMENT_TENANT_ID, DEVELOPMENT_KEY_HASH);
    }
}
