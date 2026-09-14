# ADR-012: No outbox and no event topics in this release

- **Status**: Accepted
- **Context**: Analytics and click tracking are explicit non-goals; no domain events exist.
- **Decision**: Ship no Kafka topics and no outbox table.
- **Rejected alternative**: Pre-emptively add an outbox table — dead schema unverifiable by any acceptance criterion.
- **Consequence**: When analytics arrives, an outbox (`aggregate_id`, `event_type`, `payload jsonb`, …) plus `.v1`-suffixed event schemas is added by an expand migration.
