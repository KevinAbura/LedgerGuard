# Changelog

All notable changes to LedgerGuard are documented in this file.

## [0.1.0] - 2026-08-12

### Added

- Multi-tenant, append-only double-entry journal with deterministic account locking.
- Transactional idempotency, immutable reversals, and a Kafka transactional outbox.
- Redis-backed distributed rate limiting with a conservative local fallback.
- OpenAPI 3.1, Docker Compose, unit/integration/E2E tests, CodeQL, and architecture documentation.
- Database-enforced journal sealing that rejects draft commits and all post-time mutations.
- Bounded outbox retries, dead-letter state, explicit requeue support, and backlog-age metrics.

[0.1.0]: https://github.com/KevinAbura/LedgerGuard/releases/tag/v0.1.0
