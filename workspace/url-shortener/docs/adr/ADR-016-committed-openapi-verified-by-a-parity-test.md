# ADR-016: Committed OpenAPI verified by a parity test

- **Status**: Accepted
- **Context**: A handwritten contract drifts silently from the implementation.
- **Decision**: Commit `src/main/resources/openapi.yaml` and have `OpenApiContractIT` diff it against the springdoc dump operation- and status-code-wise.
- **Rejected alternative**: Hand-maintain the document with no parity test — the contract gate would pass on stale documents.
- **Consequence**: Any endpoint or status-code change fails the build until the committed document is updated.
