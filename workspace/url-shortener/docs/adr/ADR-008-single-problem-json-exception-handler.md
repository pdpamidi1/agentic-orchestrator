# ADR-008: Single problem+json exception handler

- **Status**: Accepted
- **Context**: Every non-2xx response must have one consistent, contract-documented shape.
- **Decision**: One `@RestControllerAdvice` extending `ResponseEntityExceptionHandler` maps all errors to RFC 9457 `ProblemDetail` with `application/problem+json`.
- **Rejected alternative**: Per-controller `ResponseEntity` error bodies — the shape would drift from the committed contract.
- **Consequence**: Controllers never build error bodies; no stack traces or internal identifiers leak to clients.
