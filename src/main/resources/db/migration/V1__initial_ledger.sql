CREATE TABLE tenants (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE api_clients (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    name VARCHAR(120) NOT NULL,
    api_key_hash CHAR(64) NOT NULL UNIQUE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    rate_capacity INTEGER NOT NULL CHECK (rate_capacity > 0),
    rate_refill_per_second INTEGER NOT NULL CHECK (rate_refill_per_second > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ledger_accounts (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    external_id VARCHAR(128) NOT NULL,
    currency VARCHAR(12) NOT NULL CHECK (currency ~ '^[A-Z]{3,12}$'),
    account_type VARCHAR(16) NOT NULL CHECK (account_type IN ('ASSET', 'LIABILITY')),
    allow_negative BOOLEAN NOT NULL DEFAULT FALSE,
    balance NUMERIC(30, 8) NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (allow_negative OR balance >= 0),
    UNIQUE (tenant_id, external_id),
    UNIQUE (id, tenant_id),
    UNIQUE (id, tenant_id, currency)
);

CREATE TABLE journal_transactions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    external_reference VARCHAR(128) NOT NULL,
    transaction_type VARCHAR(64) NOT NULL,
    description VARCHAR(500),
    effective_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    reversal_of UUID,
    reversed_by UUID,
    UNIQUE (tenant_id, external_reference),
    UNIQUE (id, tenant_id),
    UNIQUE (reversal_of),
    FOREIGN KEY (reversal_of, tenant_id) REFERENCES journal_transactions(id, tenant_id),
    FOREIGN KEY (reversed_by, tenant_id) REFERENCES journal_transactions(id, tenant_id)
);

CREATE TABLE journal_entries (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    transaction_id UUID NOT NULL,
    account_id UUID NOT NULL,
    side VARCHAR(8) NOT NULL CHECK (side IN ('DEBIT', 'CREDIT')),
    amount NUMERIC(30, 8) NOT NULL CHECK (amount > 0),
    currency VARCHAR(12) NOT NULL CHECK (currency ~ '^[A-Z]{3,12}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (transaction_id, tenant_id) REFERENCES journal_transactions(id, tenant_id),
    FOREIGN KEY (account_id, tenant_id, currency) REFERENCES ledger_accounts(id, tenant_id, currency)
);

CREATE INDEX journal_entries_transaction_idx ON journal_entries(transaction_id);
CREATE INDEX journal_entries_account_idx ON journal_entries(account_id, created_at);

CREATE TABLE idempotency_records (
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    response_status INTEGER,
    response_body JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, idempotency_key)
);

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    lock_token UUID,
    locked_until TIMESTAMPTZ,
    last_error VARCHAR(1000)
);

CREATE INDEX outbox_unpublished_idx
    ON outbox_events(next_attempt_at, created_at)
    WHERE published_at IS NULL;

CREATE OR REPLACE FUNCTION prevent_journal_entry_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'journal entries are immutable';
END;
$$;

CREATE TRIGGER journal_entries_immutable
BEFORE UPDATE OR DELETE ON journal_entries
FOR EACH ROW EXECUTE FUNCTION prevent_journal_entry_mutation();

CREATE OR REPLACE FUNCTION assert_transaction_balanced()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    target_transaction UUID;
    entry_count BIGINT;
    debit_total NUMERIC(30, 8);
    credit_total NUMERIC(30, 8);
    currency_count BIGINT;
BEGIN
    target_transaction := COALESCE(NEW.transaction_id, OLD.transaction_id);
    SELECT count(*),
           COALESCE(sum(amount) FILTER (WHERE side = 'DEBIT'), 0),
           COALESCE(sum(amount) FILTER (WHERE side = 'CREDIT'), 0),
           count(DISTINCT currency)
      INTO entry_count, debit_total, credit_total, currency_count
      FROM journal_entries
     WHERE transaction_id = target_transaction;

    IF entry_count < 2 OR debit_total <> credit_total OR currency_count <> 1 THEN
        RAISE EXCEPTION 'transaction % is invalid (debit %, credit %, entries %, currencies %)',
            target_transaction, debit_total, credit_total, entry_count, currency_count;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER journal_entries_balanced
AFTER INSERT OR UPDATE OR DELETE ON journal_entries
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION assert_transaction_balanced();
