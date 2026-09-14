# ADR-005: Cache TTL bounded by remaining expiry

- **Status**: Accepted (AMB-9 default, pending confirmation)
- **Context**: Cached mappings must never outlive the link they point to.
- **Decision**: TTL = min(remaining time to `expires_at`, configurable default 24h when no expiry).
- **Rejected alternative**: Cache indefinitely — a stale entry could serve a redirect for an expired link.
- **Consequence**: Bounded staleness; expired links reliably return 410; a small steady cache-miss rate against Postgres.
