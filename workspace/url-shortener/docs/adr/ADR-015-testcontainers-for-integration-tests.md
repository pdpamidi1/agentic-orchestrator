# ADR-015: Testcontainers for integration tests

- **Status**: Accepted
- **Context**: Sequences, TTL semantics and Redis failure behaviour must be exercised realistically.
- **Decision**: `*IT` classes under the `it` Maven profile start real Postgres and Redis containers via Testcontainers.
- **Rejected alternative**: H2 plus embedded Redis — diverges from production behaviour for sequences and TTLs.
- **Consequence**: `./mvnw test` stays container-free and fast; `-Pit verify` requires Docker and runs ~2 minutes.
