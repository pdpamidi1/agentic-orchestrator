# ADR-014: Alias conflict detected by pre-check plus PK violation

- **Status**: Accepted
- **Context**: Two concurrent requests may claim the same custom alias.
- **Decision**: Pre-check `existsByShortCode`, then catch `DataIntegrityViolationException` on the primary key and map both to 409.
- **Rejected alternative**: Pre-check alone — races under concurrent creation of the same alias.
- **Consequence**: 409 is correct under concurrency; the existing mapping is never overwritten; one extra read per aliased write.
