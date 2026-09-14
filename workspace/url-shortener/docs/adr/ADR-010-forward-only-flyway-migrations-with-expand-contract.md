# ADR-010: Forward-only Flyway migrations with expand/contract

- **Status**: Accepted
- **Context**: Schema changes must be reviewable and deterministic across environments.
- **Decision**: Versioned Flyway scripts under `db/migration`; every future change is a new `Vn` file following expand then contract.
- **Rejected alternative**: Hibernate `ddl-auto=update` — unreviewable and non-deterministic between environments.
- **Consequence**: `V1` is immutable once integration tests have run; no destructive change may ship inside a single release.
