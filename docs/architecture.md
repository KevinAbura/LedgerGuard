# Architecture

## Posting sequence

```mermaid
sequenceDiagram
    participant C as Client
    participant F as API filter
    participant R as Redis
    participant S as Ledger service
    participant P as PostgreSQL
    participant O as Outbox relay
    participant K as Kafka

    C->>F: POST + API key + Idempotency-Key
    F->>P: Resolve API-key hash and tenant
    F->>R: Atomic Lua token-bucket consume
    alt Redis unavailable
        F->>F: Consume conservative local bucket
    end
    F->>S: Tenant-scoped command
    S->>P: BEGIN; claim idempotency key
    alt completed matching request
        P-->>S: Stored response
        S-->>C: 201 + Idempotent-Replayed=true
    else key reused with different hash
        S-->>C: 409 idempotency_key_reused
    else new request
        S->>P: Lock accounts in sorted UUID order
        S->>P: Insert transaction and immutable entries
        S->>P: Update balance projections with version checks
        S->>P: Insert outbox event and replay response
        S->>P: COMMIT; deferred balance trigger runs
        S-->>C: 201 + Idempotent-Replayed=false
    end
    O->>P: Claim events with FOR UPDATE SKIP LOCKED
    O->>K: Publish by aggregate ID
    O->>P: Mark published or schedule retry
```

## Ledger model

An account stores a balance in its normal-side convention:

| Account type | Normal side | Debit effect | Credit effect |
|---|---|---:|---:|
| Asset | Debit | increases | decreases |
| Liability | Credit | decreases | increases |

Each transaction has 2–100 strictly positive entries in one currency. Amounts are accepted only when they fit `NUMERIC(30, 8)` exactly. Total debits must equal total credits at scale 8. Java rejects invalid commands before I/O, and PostgreSQL independently enforces balanced single-currency entries, tenant-safe account and reversal links, and non-negative protected balances.

`ledger_accounts.balance` is a transactionally maintained projection. `journal_entries` remains the source of truth and is protected by a database trigger that rejects updates and deletes. A production reconciliation job can rebuild the projection from the journal and compare the two.

## Concurrency model

All unique account IDs are sorted and locked one at a time with `SELECT ... FOR UPDATE`. The order is independent of request entry order, preventing the classic transfer deadlock where two requests lock accounts in opposite directions.

After locks are held, the service computes every account delta, checks account status/currency/negative-balance policy, and writes with an expected version. The pessimistic lock provides serialization; the version predicate is defense in depth against a future write path that forgets to lock.

## Idempotency model

The primary key is `(tenant_id, idempotency_key)`. A replay must match both the resource type and stored SHA-256 request hash. The hash includes normalized monetary values but not a server-generated effective time. Therefore a retry of a request that omitted `effectiveAt` still matches.

`INSERT ... ON CONFLICT DO NOTHING` is executed inside the same database transaction as the journal. PostgreSQL makes a concurrent duplicate wait for the first transaction. It then observes either the completed replay body or no row if the original rolled back. Reusing a key for different request content is a conflict, not a replay.

## Reversal model

A reversal is a new transaction whose sides are the exact opposite of the original entries. The original transaction receives a `reversed_by` link; neither its metadata nor entries are overwritten. The database permits only one reversal for a transaction, and the service locks the original row before checking that rule.

## Event delivery model

The ledger transaction and outbox event commit together, removing the database/Kafka dual-write gap. Relay workers claim independent batches using `FOR UPDATE SKIP LOCKED` and a time-bounded lease. Every Kafka send in a claimed batch is dispatched concurrently on virtual threads before the relay waits for acknowledgements, so one timeout window covers the batch even if metadata lookup blocks. Kafka acknowledgement is required before `published_at` is set, and a stale lock token is reported as a lost lease rather than a successful publication.

Delivery is at least once: a relay crash after Kafka acknowledgement but before the database update can republish an event. Consumers must deduplicate on the stable `eventId` in the JSON payload.
