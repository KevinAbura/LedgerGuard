package com.kevinabura.ledgerguard.outbox;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class OutboxRepository {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public OutboxRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public List<OutboxEvent> claimBatch(int batchSize) {
        var lockToken = UUID.randomUUID();
        var now = clock.instant();
        var ids = jdbc.queryForList("""
                SELECT id
                  FROM outbox_events
                 WHERE published_at IS NULL
                   AND next_attempt_at <= ?
                   AND (locked_until IS NULL OR locked_until < ?)
                 ORDER BY created_at
                 FOR UPDATE SKIP LOCKED
                 LIMIT ?
                """, UUID.class, Timestamp.from(now), Timestamp.from(now), batchSize);
        if (ids.isEmpty()) {
            return List.of();
        }
        for (var id : ids) {
            jdbc.update("""
                    UPDATE outbox_events
                       SET lock_token = ?, locked_until = ?
                     WHERE id = ?
                    """, lockToken, Timestamp.from(now.plusSeconds(30)), id);
        }
        return jdbc.query("""
                SELECT id, aggregate_id, event_type, payload::text, attempts, lock_token
                  FROM outbox_events
                 WHERE lock_token = ? AND published_at IS NULL
                 ORDER BY created_at
                """, (rs, rowNum) -> new OutboxEvent(
                rs.getObject("id", UUID.class),
                rs.getObject("aggregate_id", UUID.class),
                rs.getString("event_type"),
                rs.getString("payload"),
                rs.getInt("attempts"),
                rs.getObject("lock_token", UUID.class)), lockToken);
    }

    public void markPublished(OutboxEvent event) {
        jdbc.update("""
                UPDATE outbox_events
                   SET published_at = ?, lock_token = NULL, locked_until = NULL, last_error = NULL
                 WHERE id = ? AND lock_token = ? AND published_at IS NULL
                """, Timestamp.from(clock.instant()), event.id(), event.lockToken());
    }

    public void markFailed(OutboxEvent event, Throwable error) {
        var delay = Duration.ofSeconds(Math.min(300, 1L << Math.min(event.attempts(), 8)));
        var message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        if (message.length() > 1000) {
            message = message.substring(0, 1000);
        }
        jdbc.update("""
                UPDATE outbox_events
                   SET attempts = attempts + 1,
                       next_attempt_at = ?,
                       lock_token = NULL,
                       locked_until = NULL,
                       last_error = ?
                 WHERE id = ? AND lock_token = ? AND published_at IS NULL
                """, Timestamp.from(clock.instant().plus(delay)), message, event.id(), event.lockToken());
    }
}
