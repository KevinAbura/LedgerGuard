ALTER TABLE journal_transactions
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'POSTED'
        CHECK (status IN ('DRAFT', 'POSTED')),
    ADD COLUMN sealed_at TIMESTAMPTZ;

UPDATE journal_transactions
   SET sealed_at = created_at
 WHERE status = 'POSTED';

ALTER TABLE journal_transactions
    ALTER COLUMN status SET DEFAULT 'DRAFT',
    ADD CONSTRAINT journal_transaction_seal_state
        CHECK ((status = 'DRAFT' AND sealed_at IS NULL)
            OR (status = 'POSTED' AND sealed_at IS NOT NULL));

CREATE OR REPLACE FUNCTION guard_journal_transaction()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    entry_count BIGINT;
    debit_total NUMERIC(30, 8);
    credit_total NUMERIC(30, 8);
    currency_count BIGINT;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DRAFT' OR NEW.sealed_at IS NOT NULL OR NEW.reversed_by IS NOT NULL THEN
            RAISE EXCEPTION 'new journal transactions must start as an unsealed draft';
        END IF;
        RETURN NEW;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'journal transactions are immutable';
    END IF;

    IF OLD.status = 'DRAFT'
       AND NEW.status = 'POSTED'
       AND OLD.sealed_at IS NULL
       AND NEW.sealed_at IS NOT NULL
       AND ROW(OLD.id, OLD.tenant_id, OLD.external_reference, OLD.transaction_type,
               OLD.description, OLD.effective_at, OLD.created_at, OLD.reversal_of, OLD.reversed_by)
           IS NOT DISTINCT FROM
           ROW(NEW.id, NEW.tenant_id, NEW.external_reference, NEW.transaction_type,
               NEW.description, NEW.effective_at, NEW.created_at, NEW.reversal_of, NEW.reversed_by) THEN
        SELECT count(*),
               COALESCE(sum(amount) FILTER (WHERE side = 'DEBIT'), 0),
               COALESCE(sum(amount) FILTER (WHERE side = 'CREDIT'), 0),
               count(DISTINCT currency)
          INTO entry_count, debit_total, credit_total, currency_count
          FROM journal_entries
         WHERE transaction_id = OLD.id;

        IF entry_count < 2 OR debit_total <> credit_total OR currency_count <> 1 THEN
            RAISE EXCEPTION 'transaction % cannot be sealed (debit %, credit %, entries %, currencies %)',
                OLD.id, debit_total, credit_total, entry_count, currency_count;
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.status = 'POSTED'
       AND NEW.status = 'POSTED'
       AND OLD.reversed_by IS NULL
       AND NEW.reversed_by IS NOT NULL
       AND ROW(OLD.id, OLD.tenant_id, OLD.external_reference, OLD.transaction_type,
               OLD.description, OLD.effective_at, OLD.created_at, OLD.reversal_of, OLD.sealed_at)
           IS NOT DISTINCT FROM
           ROW(NEW.id, NEW.tenant_id, NEW.external_reference, NEW.transaction_type,
               NEW.description, NEW.effective_at, NEW.created_at, NEW.reversal_of, NEW.sealed_at)
       AND EXISTS (
            SELECT 1
              FROM journal_transactions reversal
             WHERE reversal.id = NEW.reversed_by
               AND reversal.tenant_id = OLD.tenant_id
               AND reversal.status = 'POSTED'
               AND reversal.reversal_of = OLD.id
       ) THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'journal transactions are immutable after posting';
END;
$$;

CREATE TRIGGER journal_transactions_guard
BEFORE INSERT OR UPDATE OR DELETE ON journal_transactions
FOR EACH ROW EXECUTE FUNCTION guard_journal_transaction();

CREATE OR REPLACE FUNCTION guard_journal_entry_insert()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    transaction_status VARCHAR(16);
BEGIN
    SELECT status
      INTO transaction_status
      FROM journal_transactions
     WHERE id = NEW.transaction_id
       AND tenant_id = NEW.tenant_id
     FOR UPDATE;

    IF transaction_status IS NULL THEN
        RAISE EXCEPTION 'journal transaction % does not exist for tenant %',
            NEW.transaction_id, NEW.tenant_id;
    END IF;
    IF transaction_status <> 'DRAFT' THEN
        RAISE EXCEPTION 'cannot append entries to posted transaction %', NEW.transaction_id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER journal_entries_insert_guard
BEFORE INSERT ON journal_entries
FOR EACH ROW EXECUTE FUNCTION guard_journal_entry_insert();

CREATE OR REPLACE FUNCTION assert_journal_transaction_posted()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    current_status VARCHAR(16);
BEGIN
    SELECT status INTO current_status
      FROM journal_transactions
     WHERE id = NEW.id;
    IF current_status = 'DRAFT' THEN
        RAISE EXCEPTION 'journal transaction % must be sealed before commit', NEW.id;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER journal_transaction_must_be_posted
AFTER INSERT OR UPDATE ON journal_transactions
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION assert_journal_transaction_posted();
