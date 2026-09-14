# ADR-011: `code_source` as varchar + CHECK, not a Postgres ENUM

- **Status**: Accepted
- **Context**: A third code source (e.g. snowflake IDs) is plausible later.
- **Decision**: Store `code_source` as `varchar(16)` with `CHECK (code_source IN ('redis','db_sequence'))`.
- **Rejected alternative**: Postgres ENUM type — adding a value later requires a type migration.
- **Consequence**: Adding a source is an `ALTER ... CHECK` constraint change; type safety lives in the Java `CodeSource` enum.
