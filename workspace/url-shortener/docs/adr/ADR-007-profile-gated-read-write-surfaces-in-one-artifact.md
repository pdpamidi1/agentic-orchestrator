# ADR-007: Profile-gated read/write surfaces in one artifact

- **Status**: Accepted
- **Context**: Redirect traffic must scale independently of link creation.
- **Decision**: One deployable jar; `@Profile("!write")` on the redirect controller and `@Profile("!read")` on the write controller; profiles `read`, `write`, default.
- **Rejected alternative**: Two Maven modules/services — shared schema and DTOs do not justify the build and deployment overhead yet.
- **Consequence**: A read instance answers 404 to POST and vice versa; one image, three deployment shapes.
