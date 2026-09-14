# ADR-002: Postgres sequence fallback on Redis outage

- **Status**: Accepted
- **Context**: Redis is a single dependency of the write path; an outage must not stop link creation.
- **Decision**: On Redis failure, allocate from `nextval('url_code_seq')` and persist `code_source='db_sequence'`.
- **Rejected alternative**: Fail the write with 503 — availability of link creation outweighs counter-space contiguity.
- **Consequence**: Codes remain unique (disjoint seeded ranges are not guaranteed contiguous); `code_source` makes degraded writes auditable.
