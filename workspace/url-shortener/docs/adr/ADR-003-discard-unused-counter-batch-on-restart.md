# ADR-003: Discard unused counter batch on restart

- **Status**: Accepted (AMB-10 default, pending confirmation)
- **Context**: An instance holding a reserved 1000-value range loses the remainder when it restarts.
- **Decision**: Accept gaps; never persist or reclaim reserved ranges.
- **Rejected alternative**: Persist and reclaim ranges — reintroduces the shared-state write that batching was meant to remove.
- **Consequence**: Code space is consumed faster than links are created; no correctness impact; documented in `docs/operations.md`.
