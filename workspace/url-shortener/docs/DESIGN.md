# Design choices

This document explains *why* the url-shortener looks the way it does. The code was produced by an agentic SDLC
pipeline (the `agentic-orchestrator` in the parent repository) from a normalised specification, a plan and a
low-level design; every decision below was recorded as an ADR one-liner in that design and expanded by the
documentation stage. Each ADR names the alternative that was rejected and why. The full decision records are in
[`docs/adr/`](adr/).

## The problem in one paragraph

Create short links for long URLs (`POST /api/v1/urls`, optional custom alias and expiry) and redirect on
`GET /{short_code}` (302, `Cache-Control: private`; 404 unknown, 410 expired). Postgres is the source of truth
for the `urls` table; short codes are base62 encodings of a globally unique counter kept in Redis (batched
`INCRBY 1000`) with a Postgres sequence as fallback when Redis is unavailable; the read path checks a Redis cache
before the database; read and write surfaces are deployable separately through Spring profiles. Authentication
and analytics are explicit non-goals.

## Architecture at a glance

```
                 +-------------------------+        +----------------------------+
 POST /api/v1/urls --> write.UrlWriteController --> write.UrlWriteService --+--> codegen.ShortCodeAllocator
                 +-------------------------+        +----------------------------+     |  RedisBatchCounterSource
                                                                                       |  DbSequenceCounterSource
 GET /{code} ------> read.RedirectController ----> read.UrlReadService ----+          |  Base62Codec
                 +-------------------------+        +----------------------------+     v
                                                          |   read.RedisUrlCache      domain.UrlMappingRepository
                                                          v            (cache-aside)        |
                                                     Redis (cache + counter)          Postgres `urls` (Flyway)
```

Layering rules the code must obey (checked by the generated `src/test/java/sdlc/ArchitectureTest.java`):

- Layer order is web (write, read controllers) -> service (UrlWriteService, UrlReadService) -> infrastructure (codegen, domain repository, RedisUrlCache); dependencies point downward only, never upward.
- domain must not depend on api.*, write, read or codegen; it knows only JPA and CodeSource.
- config is a leaf: ShortenerProperties depends on nothing inside the application.
- api.dto and api.error contain no persistence or Redis types; DTOs never expose JPA entities, services map entity <-> DTO.
- write must not depend on read and read must not depend on write; the two deployment surfaces stay independently registrable via @Profile.
- Only classes in write and read may carry web annotations (@RestController/@RequestMapping); services and repositories stay framework-web-free.
- Only RedisBatchCounterSource and RedisUrlCache may reference Spring Data Redis types; all other code talks to the CounterSource and UrlCache interfaces.
- Infrastructure failures (Redis) are handled at the boundary class that owns the technology: RedisUrlCache swallows and logs, RedisBatchCounterSource throws and ShortCodeAllocator decides the fallback. Controllers never catch infrastructure exceptions.
- No controller builds an error body; every non-2xx response is produced by GlobalExceptionHandler as application/problem+json.
- Test sources in com.example.shortener.it may depend on all production packages; no production package may depend on test sources.
- No cyclic package dependencies (enforced by the provisioned sdlc.ArchitectureTest).

## API contract (committed in `src/main/resources/openapi.yaml`)

- `POST /api/v1/urls` (`createShortUrl`): 201 created; body carries short_url, short_code, long_url, expires_at and code_source; row persisted with created_by null, 400 invalid long_url (non-http/https, blank, >2048 chars), invalid custom_alias ([0-9a-zA-Z], 3-32), malformed or past expiration_date, unreadable JSON body; nothing persisted, 409 custom_alias / short_code already taken; existing mapping unchanged, 500 unhandled server error rendered as problem+json by the global handler
- `GET /{short_code}` (`redirectToLongUrl`): 302 found; Location = stored long_url, Cache-Control: private, 404 short code unknown in cache and database, 410 short code expired (expires_at in the past), 500 unhandled server error rendered as problem+json by the global handler

A parity test (`OpenApiContractIT`) compares the committed document with what the running application exposes
through springdoc, so the document can never drift from the code.

## Data model

- `urls`: `short_code` varchar(32), `long_url` varchar(2048), `created_at` timestamptz, `expires_at` timestamptz, `created_by` varchar(255), `code_source` varchar(16)
- `url_code_seq`: `last_value` bigint

Migrations are forward-only Flyway scripts under `src/main/resources/db/migration` (expand/contract for future
changes); Hibernate runs with `ddl-auto: validate` and never alters the schema.

## Decisions and rejected alternatives

- **ADR-001** Short codes come from a batched Redis INCRBY 1000 counter base62-encoded; rejected UUID/random-with-collision-retry because it needs a uniqueness read per attempt and yields longer codes.
- **ADR-002** Redis outage during allocation falls back to the Postgres sequence url_code_seq with code_source='db_sequence'; rejected failing the write with 503 because availability of link creation outweighs counter-space contiguity.
- **ADR-003** Unused values of a reserved 1000-range are discarded on restart (gaps accepted, AMB-10); rejected persisting and reclaiming ranges because it reintroduces the shared-state write the batching was meant to remove.
- **ADR-004** The counter is seeded at 62^3 = 238328 so every generated code is >= 3 characters (AMB-11); rejected allowing 1-2 character codes because it would split the code format from the 3-32 alias rule. *Correction: the delivered code seeds at 62^5 = 916132832 (codes >= 6 characters); see the ADR's implementation note.*
- **ADR-005** Cache entries use TTL = min(remaining time to expires_at, configurable 24h default) (AMB-9); rejected caching indefinitely because a flushed-but-stale entry could outlive the link.
- **ADR-006** Redis is a read-through cache only and Postgres is the single source of truth; rejected write-behind/Redis-primary storage because durability of mappings is non-negotiable.
- **ADR-007** Read and write surfaces are separated by @Profile("!write")/@Profile("!read") in one artifact; rejected two Maven modules/services because the shared schema and DTOs do not justify the build and deployment overhead yet.
- **ADR-008** All errors are emitted by a single @RestControllerAdvice as RFC 9457 application/problem+json; rejected per-controller ResponseEntity error bodies because the shape would drift from the committed contract.
- **ADR-009** API is versioned with the /api/v1 path prefix on the write endpoint while redirects stay at the bare root path /{short_code}; rejected versioning redirects because a short link must stay as short as possible.
- **ADR-010** Schema is managed by forward-only Flyway migrations with expand/contract for every future change; rejected Hibernate ddl-auto update because it is unreviewable and non-deterministic across environments.
- **ADR-011** code_source is varchar + CHECK constraint rather than a Postgres ENUM; rejected ENUM because adding a value later requires a type migration.
- **ADR-012** No Kafka topics and no outbox table in this release since no domain events exist (analytics is a non-goal); rejected pre-emptively adding an outbox because it would be dead schema and unverifiable by any acceptance criterion.
- **ADR-013** Stack stays blocking Spring MVC (servlet) rather than WebFlux; rejected reactive because JPA/Flyway are blocking and redirect latency is dominated by a single cache lookup.
- **ADR-014** Alias conflicts are detected by a pre-check plus catching the primary-key DataIntegrityViolationException; rejected the pre-check alone because it races under concurrent creation of the same alias.
- **ADR-015** Integration tests use Testcontainers for Postgres and Redis with *IT naming under the `it` profile; rejected embedded/H2 + embedded Redis because they diverge from production behaviour (sequences, TTL semantics).
- **ADR-016** The committed src/main/resources/openapi.yaml is verified against the springdoc dump by OpenApiContractIT; rejected hand-maintaining the document without a parity test because the contract gate would fail on silent drift.
- **ADR-017** created_by stays nullable and is never populated in this release; rejected dropping the column because re-adding it later would cost an extra expand/contract cycle.

## Resolved ambiguities (answers folded into the specification)

The requirement was deliberately terse; the pipeline raised these questions and the answers became binding
assumptions:

- Custom aliases and short codes must match [0-9a-zA-Z] with length 3-32; violations return 400 with application/problem+json (per AMB-1 answer)
- 409 applies only to a taken custom_alias; re-posting an already-shortened long_url creates a new distinct short code (per AMB-2 answer)
- Only http and https schemes are accepted for long_url, with a maximum length of 2048 characters; violations return 400 problem+json (per AMB-3 answer)
- created_by is a nullable column left null in this scenario (per AMB-4 answer)
- Both malformed ISO-8601 and past expiration_date values are rejected with 400 problem+json (per AMB-5 answer)
- When Redis is unavailable on the read path, the service bypasses the cache and serves redirects directly from Postgres, logging the degradation (per AMB-6 answer)
- Spring profiles 'read', 'write', and a default combined profile conditionally register controllers; a read instance returns 404 for write endpoints and vice versa (per AMB-7 answer)
- The short_url base is a configurable application property defaulting to http://localhost:8080 (per AMB-8 answer)
- Target stack is Java 25 / Spring Boot 4 (SDLC_TARGET_STACK=java for this run); Flyway is used for migrations

## Non-goals

- Authentication and per-user ownership (`created_by` stays nullable and unpopulated, ADR-017).
- Click analytics and domain events (no Kafka topics, no outbox, ADR-012); the brownfield scenario adds them.
- Horizontal coordination of the counter beyond one Redis instance.

## Where to look next

- Operations, degraded modes and the counter-gap policy: [`operations.md`](operations.md).
- Individual decision records: [`adr/`](adr/).

## ADR index

- [ADR-001: Base62 codes from a batched Redis counter](adr/ADR-001-base62-codes-from-a-batched-redis-counter.md)
- [ADR-002: Postgres sequence fallback on Redis outage](adr/ADR-002-postgres-sequence-fallback-on-redis-outage.md)
- [ADR-003: Discard unused counter batch on restart](adr/ADR-003-discard-unused-counter-batch-on-restart.md)
- [ADR-004: Counter seeded at 62^3](adr/ADR-004-counter-seeded-at-62-5-in-the-delivered-code.md)
- [ADR-005: Cache TTL bounded by remaining expiry](adr/ADR-005-cache-ttl-bounded-by-remaining-expiry.md)
- [ADR-006: Redis is cache only, Postgres is the source of truth](adr/ADR-006-redis-is-cache-only-postgres-is-the-source-of-truth.md)
- [ADR-007: Profile-gated read/write surfaces in one artifact](adr/ADR-007-profile-gated-read-write-surfaces-in-one-artifact.md)
- [ADR-008: Single problem+json exception handler](adr/ADR-008-single-problem-json-exception-handler.md)
- [ADR-009: Versioned write path, unversioned redirect path](adr/ADR-009-versioned-write-path-unversioned-redirect-path.md)
- [ADR-010: Forward-only Flyway migrations with expand/contract](adr/ADR-010-forward-only-flyway-migrations-with-expand-contract.md)
- [ADR-011: `code_source` as varchar + CHECK, not a Postgres ENUM](adr/ADR-011-code-source-as-varchar-check-not-a-postgres-enum.md)
- [ADR-012: No outbox and no event topics in this release](adr/ADR-012-no-outbox-and-no-event-topics-in-this-release.md)
- [ADR-013: Blocking Spring MVC, not WebFlux](adr/ADR-013-blocking-spring-mvc-not-webflux.md)
- [ADR-014: Alias conflict detected by pre-check plus PK violation](adr/ADR-014-alias-conflict-detected-by-pre-check-plus-pk-violation.md)
- [ADR-015: Testcontainers for integration tests](adr/ADR-015-testcontainers-for-integration-tests.md)
- [ADR-016: Committed OpenAPI verified by a parity test](adr/ADR-016-committed-openapi-verified-by-a-parity-test.md)
- [ADR-017: `created_by` kept nullable and unpopulated](adr/ADR-017-created-by-kept-nullable-and-unpopulated.md)
