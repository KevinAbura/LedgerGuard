# Design decisions and trade-offs

## PostgreSQL is the ledger authority

PostgreSQL owns journals, idempotency records, balance projections, and the outbox. This keeps all financial state changes in one ACID boundary. Redis is never authoritative; losing it can change admission capacity but cannot change money.

Trade-off: a single primary database limits horizontal write scale. Partitioning by tenant/account and carefully designed multi-region ownership would be a later step, not something hidden behind an abstraction in v0.1.

## Pessimistic account locks plus version checks

`SELECT ... FOR UPDATE` makes negative-balance decisions serializable for the accounts touched by a transaction. Sorted lock order prevents transfer deadlocks. The balance update also checks `version` as defense in depth.

Trade-off: hot accounts become throughput bottlenecks. Optimistic-only retries can outperform locks when contention is low, but become wasteful and hard to reason about for heavily used settlement accounts. LedgerGuard favors deterministic correctness and observable contention.

## Append-only corrections

Posted entries cannot be updated or deleted. Corrections are equal-and-opposite reversals linked to the original transaction.

Trade-off: history grows and reporting must understand reversal links. The benefit is a complete audit trail and no ambiguity about what was originally posted.

## Transactional idempotency instead of a Redis lock

The request claim, financial write, outbox event, and stored response share one PostgreSQL transaction. A Redis lock would introduce lease-expiry races and another failure domain around the core invariant.

Trade-off: idempotency rows grow with traffic and need a retention policy. Financial keys should be retained for at least the business retry/dispute window; deletion must not outlive external-reference uniqueness.

## Transactional outbox instead of direct Kafka publish

Direct publication inside the request cannot atomically commit with PostgreSQL. The outbox accepts asynchronous propagation and at-least-once delivery to remove lost-event dual writes.

Trade-off: downstream consumers see small propagation delay and must deduplicate. Backlog age, retry count, and oldest unpublished event should be alerted on in production.

## Redis token bucket with local fallback

The Lua script uses Redis server time and atomically refills/consumes a per-client, per-route bucket across instances. On Redis failure each instance switches to a smaller local bucket.

Trade-off: fallback is fail-soft, not a globally exact limit. With `N` API instances, worst-case fallback admission is approximately `N × local capacity`. A strict fail-closed mode would protect a fragile downstream system better but would make a Redis outage a total API outage. Financial correctness is unchanged either way because PostgreSQL invariants remain authoritative.

## API key lookup from PostgreSQL

Every request hashes the presented secret and resolves the client/tenant from PostgreSQL. It keeps revocation immediate and the first version easy to audit.

Trade-off: this adds a read per request. A production evolution could cache active client metadata briefly, carrying a bounded revocation delay, or move authentication/rate limiting to a gateway while still passing a cryptographically verified tenant identity.

## Stored balance is a projection

The account balance is updated in the same transaction as the immutable journal for fast reads. It is not treated as an independent source of truth.

Trade-off: any future write path must update both correctly. Database permissions should deny direct writes, and scheduled reconciliation should compare `sum(entries)` with the projection.
