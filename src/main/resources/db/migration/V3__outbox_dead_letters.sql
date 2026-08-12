ALTER TABLE outbox_events
    ADD COLUMN dead_lettered_at TIMESTAMPTZ;

DROP INDEX outbox_unpublished_idx;
CREATE INDEX outbox_pending_idx
    ON outbox_events(next_attempt_at, created_at)
    WHERE published_at IS NULL AND dead_lettered_at IS NULL;

CREATE INDEX outbox_dead_letter_idx
    ON outbox_events(dead_lettered_at, created_at)
    WHERE dead_lettered_at IS NOT NULL;

CREATE OR REPLACE FUNCTION ledgerguard_requeue_outbox_event(target_event_id UUID)
RETURNS BOOLEAN LANGUAGE plpgsql AS $$
BEGIN
    UPDATE outbox_events
       SET attempts = 0,
           next_attempt_at = now(),
           lock_token = NULL,
           locked_until = NULL,
           last_error = NULL,
           dead_lettered_at = NULL
     WHERE id = target_event_id
       AND published_at IS NULL
       AND dead_lettered_at IS NOT NULL;
    RETURN FOUND;
END;
$$;
