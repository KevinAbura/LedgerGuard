# Security policy

## Reporting a vulnerability

Please do not open a public issue for a suspected vulnerability. Contact the repository owner privately with the affected version, reproduction steps, impact, and any proposed mitigation. Avoid including real credentials or customer data.

## Supported versions

Until the first stable release, only the latest commit on `main` receives security fixes.

## Security boundaries

- API keys authenticate clients; their database values are SHA-256 digests, not raw secrets.
- The authenticated tenant ID is injected server-side and included in resource queries.
- Redis rate limiting protects capacity but is not part of financial correctness.
- PostgreSQL constraints, transactions, and immutable journal triggers protect ledger invariants.
- The `ledgerguard-dev-key` bootstrap is public development data, disabled by default, and must never be enabled in a real deployment.

This repository has not undergone an external security audit and should not process real funds without one.
