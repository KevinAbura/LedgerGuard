# Benchmark methodology

LedgerGuard includes a JMH microbenchmark for two CPU-only operations:

- validating and normalizing a balanced 20-entry journal
- calculating a normal-side account balance delta

Run it with:

```bash
mvn -Pbenchmark test-compile exec:exec
```

The configured run uses one fork, three warm-up iterations, and five one-second measurement iterations. JMH prevents common JVM benchmark mistakes such as dead-code elimination and unmeasured warm-up.

## Local reference run

Recorded on 2026-08-09 using macOS 14.7.6 arm64 and Temurin/OpenJDK 25.0.4+7 LTS:

| Benchmark | Throughput | 99.9% error interval reported by JMH |
|---|---:|---:|
| `calculateBalanceDelta` | 91,573,450 ops/s | ±72,371,331 ops/s |
| `validateTwentyEntryJournal` | 604,634 ops/s | ±281,444 ops/s |

The wide intervals show that this short laptop run is a smoke baseline, not a capacity claim. Retain longer controlled runs before comparing revisions.

Record the following with every result:

- Git commit
- CPU and operating system
- JVM vendor/version and flags
- warm-up, measurement, fork, and thread counts
- whether the machine was otherwise idle

Do not interpret the microbenchmark as ledger TPS. End-to-end throughput depends on PostgreSQL fsync latency, connection-pool size, number of accounts per transaction, and contention distribution. A defensible system benchmark should separately test:

1. uncontended postings across many accounts;
2. a hot-account workload to expose lock serialization;
3. duplicate-idempotency replay versus fresh writes;
4. Redis normal/fallback admission behavior;
5. outbox throughput while Kafka is healthy and during broker recovery.

The project does not publish a fabricated end-to-end number. CI can run correctness on shared hardware, but performance baselines should run on controlled, dedicated infrastructure and retain raw output.
