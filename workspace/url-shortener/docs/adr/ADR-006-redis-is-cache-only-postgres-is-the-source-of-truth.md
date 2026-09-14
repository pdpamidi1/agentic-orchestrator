# ADR-006: Redis is cache only, Postgres is the source of truth

- **Status**: Accepted
- **Context**: Mappings must survive a full Redis flush or outage.
- **Decision**: Redis holds only derived state (counter + cache entries); all mappings are written to Postgres synchronously.
- **Rejected alternative**: Write-behind or Redis-primary storage — durability of mappings is non-negotiable.
- **Consequence**: Redis can be flushed at any time with no data loss; write latency includes a Postgres commit.
