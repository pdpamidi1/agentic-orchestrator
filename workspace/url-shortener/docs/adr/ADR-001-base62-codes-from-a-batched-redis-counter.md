# ADR-001: Base62 codes from a batched Redis counter

- **Status**: Accepted
- **Context**: Short codes must be globally unique across concurrently running write instances and stay short.
- **Decision**: Reserve 1000 counter values per instance with a single `INCRBY` on `shortener:counter`, hand them out locally and base62-encode them.
- **Rejected alternative**: UUID or random-with-collision-retry — requires a uniqueness read per attempt and yields longer codes.
- **Consequence**: One Redis round-trip per 1000 creations; uniqueness is structural, not probabilistic; codes are non-sequential-looking but enumerable.
