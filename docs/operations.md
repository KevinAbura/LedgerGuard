# Operations and failure modes

## Service health

Use `/actuator/health/liveness` only to decide whether the JVM should restart. Use `/actuator/health/readiness` to remove an instance from traffic when required dependencies are unavailable.

Prometheus metrics are exposed at `/actuator/prometheus`. In an internet-facing deployment, place actuator endpoints on a private management port or protect them at the network layer.

## Expected failure behavior

| Failure | Behavior | Operator action |
|---|---|---|
| PostgreSQL unavailable | Authentication/posting fails; no financial write is acknowledged | Remove instance from traffic, restore database connectivity |
| Redis unavailable | Requests use a smaller per-instance local bucket and return `X-RateLimit-Policy: local-fallback` | Alert on fallback metric; restore Redis; watch aggregate load |
| Kafka unavailable | Posting continues; outbox rows retry with exponential backoff | Alert on backlog age/count; restore broker; do not delete rows |
| Relay crashes after publish | Event may be published twice | Consumers deduplicate by payload `eventId` |
| Concurrent overspend | One transaction obtains the account lock first; later invalid transaction rolls back | Expected business conflict, not an incident |
| Duplicate request | Same hash replays stored result; changed hash returns 409 | Client must preserve one key per logical operation |

## Alerts

Recommended starting signals:

- readiness failures for more than two probe intervals
- any sustained `ledgerguard_rate_limit_redis_errors_total` increase
- local fallback lasting more than one minute
- outbox failed-event or lost-lease rate above zero for five minutes
- oldest unpublished outbox event older than the delivery SLO
- PostgreSQL lock wait or connection-pool saturation
- HTTP 5xx rate, idempotency conflicts, and insufficient-funds conflicts separated by code

The current metrics expose relay outcomes but not oldest-event age. Add that gauge before production use.

## Backup and recovery

Back up PostgreSQL with point-in-time recovery and test restores. Recovery validation must compare journal debit/credit totals, account projections, reversal links, and unpublished outbox rows. Redis data can be rebuilt because it contains admission state only. Kafka retention and consumer replay policy must match downstream recovery objectives.

## Key rotation

The demo migration contains one known development secret. A deployment process should generate at least 256 bits of randomness, show the raw key once, store only the SHA-256 digest, and support overlapping old/new clients during rotation. Disable the old client after consumers migrate; never log request headers containing API keys.

## Shutdown and deployment

Spring graceful shutdown is enabled. Stop routing new requests before terminating an instance so active database transactions can finish. Outbox sends default to a 10-second timeout and leases expire after 30 seconds; another relay can safely reclaim work after an ungraceful stop.
