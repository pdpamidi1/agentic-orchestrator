Run: {run_id}. Target stack: {target_stack}.

Produce the Design: ApiContract (full OpenAPI 3.1 YAML + operations list with response codes and `breaking` flags),
DataModel (tables, constraints, migration bodies, and an evolution strategy — expand/contract, versioned events),
ClassStructure (packages, classes with responsibilities, allowed dependency directions, layering rules), and decisions
as ADR one-liners with the rejected alternative. Defaults unless the spec says otherwise: REST + OpenAPI, API versioning
via path prefix, global exception handler with problem+json, async/reactive where I/O bound, Kafka for events with an
outbox table, Testcontainers for integration tests, Docker + Kubernetes manifests out of scope for agents (infra is protected).

## Spec
{spec}

## Plan
{plan}

## Impact (may be none)
{impact}

## System design notes from the human (authoritative constraints)
{system_design_notes}

## Tech stack preferences
{tech_stack}

## Feedback
{feedback}
