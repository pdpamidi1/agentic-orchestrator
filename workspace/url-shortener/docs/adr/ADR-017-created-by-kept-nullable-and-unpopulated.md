# ADR-017: `created_by` kept nullable and unpopulated

- **Status**: Accepted
- **Context**: Authentication is out of scope, but ownership will be needed later.
- **Decision**: Ship the nullable `created_by varchar(255)` column and never write to it in this release.
- **Rejected alternative**: Omit the column and add it later — costs an extra expand/contract cycle.
- **Consequence**: No PII is stored today; when auth lands, the column is backfilled and then made NOT NULL via expand/contract.
