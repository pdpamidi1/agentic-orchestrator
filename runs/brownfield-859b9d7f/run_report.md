# Run report: brownfield-859b9d7f

| Field | Value |
|---|---|
| Scenario | brownfield |
| Status | RUNNING |
| Halt reason | - |
| First event | 2026-09-14 14:18:40 UTC |
| Last event | 2026-09-14 19:23:44 UTC |
| Agent (LLM) tokens | 223,647 |
| Executor (Claude Code) calls | 14 |
| Re-plans (state.json) | 0 |

## 1. Requirement

```text
Add click analytics to the existing URL shortener in the workspace.
- Every successful redirect publishes url.clicked {short_code, occurred_at, hashed_ip, referrer_host} to Kafka topic url.clicked
  through an outbox table (redirect must never block on Kafka).
- An analytics consumer aggregates into click_stats {short_code, total_clicks, last_clicked_at, clicks_by_day}.
- GET /api/v1/urls/{short_code}/stats returns the aggregate; unknown -> 404.
- Raw IP must never be persisted (salted SHA-256 only). Retain click events for 90 days.
- Integrate bounded retries using exponential backoff with jitter, timeouts, and idempotency keys.
- No regression to GET /{short_code} latency; existing contract must remain backward compatible (additive only).
```

## 2. Specification

Version 2. Extend the existing Java URL shortener with privacy-preserving click analytics: every successful GET /{short_code} redirect records a url.clicked event via a transactional outbox (never blocking the redirect), an in-process poller publishes those events to the Kafka topic url.clicked keyed by short_code, an analytics consumer aggregates them into click_stats, and a new additive endpoint GET /api/v1/urls/{short_code}/stats exposes total clicks, last clicked timestamp, and a 30-day daily breakdown with an as_of timestamp. Raw IPs are never persisted (salted SHA-256 only), raw click events are retained 90 days, and publishing/consuming use bounded retries with exponential backoff + jitter, timeouts, and idempotency keys. The existing redirect contract stays backward compatible with no more than a 5% p95 latency increase.

### Stories

| Id | As a | I want | So that |
|---|---|---|---|
| US-1 | link owner | every successful redirect of my short link to be recorded as a click event | I can see how often my link is used |
| US-2 | end user following a short link | the redirect to complete at the same speed even when Kafka is slow or unavailable | analytics never degrades my browsing experience |
| US-3 | link owner | an API that returns aggregated click statistics for a short code | I can review total clicks, the last click time, and daily trends |
| US-4 | data protection officer | IP addresses to be stored only as salted SHA-256 hashes and raw events purged after 90 days | the service stays compliant with privacy and retention policy |
| US-5 | platform operator | outbox publishing and consumption to use bounded retries with exponential backoff, jitter, timeouts, and idempotency keys | transient broker or database failures do not cause data loss, duplicates, or unbounded resource use |
| US-6 | existing API consumer | the current redirect and URL endpoints to keep their exact contract | my integration continues to work after the analytics release |

### Acceptance criteria

| Id | Given | When | Then |
|---|---|---|---|
| AC-1 | a short code that resolves to a target URL | a client issues GET /{short_code} and the service returns a successful redirect response | exactly one row is committed to the outbox table in the same database transaction as the redirect handling, containing short_code, occurred_at (UTC), hashed_ip… |
| AC-2 | a request for an unknown or expired short code | GET /{short_code} does not produce a successful redirect | no outbox row and no url.clicked event are created |
| AC-3 | Kafka is unreachable or responding slower than the configured timeout | a client issues GET /{short_code} | the redirect response is returned with its normal status and Location header, the request does not block on any Kafka call, and the event remains pending in th… |
| AC-4 | pending rows exist in the outbox table | the in-process scheduled poller runs | it claims a bounded batch using SELECT ... FOR UPDATE SKIP LOCKED, publishes each event to topic url.clicked with short_code as the message key, and marks publ… |
| AC-5 | the Kafka producer or consumer call fails with a transient error | the retry policy executes | attempts are retried with exponential backoff plus jitter up to the configured maximum attempts, each attempt bounded by a timeout, and after exhaustion the ev… |
| AC-6 | the same url.clicked event (same idempotency key) is delivered to the analytics consumer more than once | the consumer processes both deliveries | click_stats totals, last_clicked_at and clicks_by_day reflect the event exactly once |
| AC-7 | url.clicked events have been consumed for a short code | the consumer aggregates them | click_stats holds short_code, total_clicks, last_clicked_at and clicks_by_day where day buckets are computed in UTC |
| AC-8 | a short code that exists and has recorded clicks | a client calls GET /api/v1/urls/{short_code}/stats with credentials accepted by the existing /api/v1 auth scheme | the response is 200 with total_clicks, last_clicked_at, an as_of timestamp, and clicks_by_day as an ordered list of {date, count} covering the last 30 days |
| AC-9 | a short code that does not exist | a client calls GET /api/v1/urls/{short_code}/stats | the response is 404 |
| AC-10 | a redirect request carrying a client IP address | the click event is persisted and published | the payload contains only the SHA-256 hash of the IP combined with the configured static salt, and no raw IP value appears in the outbox table, click_stats, Ka… |
| AC-11 | a redirect request with a Referer header | the click event is built | referrer_host contains only the host component with no path or query, and is null when the header is absent or cannot be parsed as a URI |
| AC-12 | raw click event rows older than 90 days | the retention job runs | those raw rows are deleted while click_stats aggregate rows remain unchanged |
| AC-13 | the baseline redirect latency measured before the change | the automated load-test gate runs against the build with analytics enabled | p95 latency of GET /{short_code} is no more than 5% above baseline and the gate fails the build otherwise |
| AC-14 | existing clients of GET /{short_code} and the current /api/v1 URL endpoints | the analytics release is deployed | existing request/response contracts are unchanged, only additive fields and the new stats endpoint are introduced, and the existing contract test suite passes … |
| AC-15 | a click event published but not yet consumed | GET /api/v1/urls/{short_code}/stats is called | the response reflects eventual consistency and its as_of timestamp indicates the point in time the aggregate was last updated |

### Non-goals

- Bot, crawler or fraud filtering of clicks
- Analytics on unsuccessful redirect attempts (404s, expired links)
- Salt rotation or re-hashing of historical hashed_ip values
- Geo-IP, device, browser or user-agent enrichment
- A dashboard, UI or CSV/report export for click statistics
- Provisioning or configuring the Kafka cluster and the url.clicked topic (owned by infrastructure)
- Changing the existing authentication/authorization scheme
- Real-time/strongly consistent stats or streaming push of click updates
- Deleting or expiring click_stats aggregate rows

### Assumptions (answered ambiguities)

- hashed_ip is computed with SHA-256 over a single static salt read from the existing secret/config mechanism; the salt is never rotated (AMB-1).
- Outbox draining is performed by an in-process scheduled poller using SELECT ... FOR UPDATE SKIP LOCKED batching, safe for multiple application instances (AMB-2).
- clicks_by_day covers the last 30 days and is returned as an ordered list of {date, count} objects (AMB-3).
- All timestamps and day bucketing use UTC (AMB-4).
- The 90-day retention applies to raw click events only; click_stats aggregates are retained indefinitely (AMB-5).
- The latency budget is a maximum 5% p95 increase on GET /{short_code}, verified by an automated load-test gate in CI (AMB-6).
- The new stats endpoint reuses whatever authentication/authorization scheme the existing /api/v1 endpoints already enforce (AMB-7).
- referrer_host holds the host component only (no path or query) and is null when the Referer header is absent or unparsable (AMB-8).
- Stats are eventually consistent and the response includes an as_of timestamp (AMB-9).
- The Kafka topic url.clicked is provisioned by infrastructure; messages are keyed by short_code to preserve per-code ordering (AMB-10).
- Only successful redirects generate click events; no bot filtering is performed (AMB-11).
- The work is brownfield: it extends the existing Java URL shortener in the workspace and reuses its current persistence, configuration and build tooling.

### Open ambiguities

| Id | Question | Default |
|---|---|---|
| AMB-12 | What retry bounds should the outbox publisher and analytics consumer use (max attempts and backoff ceiling)? | Configurable via application properties with defaults of 5 attempts, base 100ms, cap 10s, full jitter |
| AMB-13 | Where should the analytics consumer store click_stats? | Same relational database as the shortener, in a separate table owned by the analytics module |
| AMB-14 | What should GET /api/v1/urls/{short_code}/stats return for a short code that exists but has zero recorded clicks? | 200 with total_clicks=0, last_clicked_at=null and an empty clicks_by_day list |
| AMB-15 | Should clicks_by_day include days with zero clicks within the 30-day window? | No, sparse list containing only days with at least one click |
| AMB-16 | How should the 90-day raw click event purge be triggered? | In-process scheduled job in the application, with interval and batch size configurable |
| AMB-17 | What scope defines the idempotency key for a click event? | Random UUID generated per redirect, stored on the outbox row and carried in the Kafka message header |
| AMB-18 | What poller cadence and batch size should the outbox publisher use? | Configurable with defaults of 500ms interval and batch size 100 |

## 3. Impact on the existing code (brownfield)

- Impacted packages: `com.example.shortener`, `com.example.shortener.read`, `com.example.shortener.domain`, `com.example.shortener.config`, `com.example.shortener.api.dto`, `com.example.shortener.api.error`, `com.example.shortener.it`, `com.example.shortener.write`
- New packages: `com.example.shortener.analytics`, `com.example.shortener.analytics.domain`, `com.example.shortener.analytics.outbox`, `com.example.shortener.analytics.publish`, `com.example.shortener.analytics.consume`, `com.example.shortener.analytics.aggregate`, `com.example.shortener.analytics.api`, `com.example.shortener.analytics.api.dto`, `com.example.shortener.analytics.privacy`, `com.example.shortener.analytics.retention`, `com.example.shortener.analytics.config`
- Impacted endpoints: `GET /{short_code} (com.example.shortener.read) - HOT PATH, PUBLIC CONTRACT: handler must additionally write one outbox row in the same DB transaction as successful redirect resolution; response status/Location/headers must remain byte-for-byte unchanged (AC-1, AC-3, AC-13, AC-14)`, `GET /api/v1/urls/{short_code}/stats (NEW, com.example.shortener.analytics.api) - additive endpoint reusing the existing /api/v1 auth scheme; 200 with total_clicks, last_clicked_at, as_of, clicks_by_day; 404 for unknown short_code (AC-8, AC-9, AC-15)`, `POST /api/v1/urls (com.example.shortener.write) - PUBLIC CONTRACT: no functional change expected, but shares /api/v1 security/DTO/error config that the new endpoint plugs into; existing contract tests must pass unmodified (AC-14)`

### Data flows

- GET /{short_code} request (client IP, Referer header) -> read module resolves short_code against urls table; on successful resolution build ClickEvent (salted SHA-256 of IP via analytics.privacy, host-only referrer_host, occurred_at UTC, random UUID idempotency key) -> INSERT into click_outbox in the SAME transaction as redirect handling -> commit -> 302/301 returned without any Kafka call on the request thread (AC-1, AC-3, AC-10, AC-11)
- Scheduled outbox poller (interval + batch size configurable, defaults 500ms/100) -> SELECT ... FOR UPDATE SKIP LOCKED claims pending click_outbox batch -> serialize event, publish to Kafka topic url.clicked with key=short_code and idempotency key in message header, bounded per-attempt timeout + exponential backoff with full jitter (default 5 attempts, base 100ms, cap 10s) -> mark row PUBLISHED (or FAILED terminal state, logged + metered) in click_outbox (AC-4, AC-5)
- Kafka topic url.clicked -> analytics consumer (bounded retry/backoff, per-attempt timeout) -> dedupe on idempotency key via processed_click_event table / unique constraint -> INSERT raw row into click_events and UPSERT aggregate into click_stats (total_clicks, last_clicked_at, clicks_by_day bucketed in UTC, updated_at as as_of) (AC-6, AC-7)
- GET /api/v1/urls/{short_code}/stats -> existing /api/v1 auth filter -> verify short_code exists in urls (404 otherwise) -> read click_stats + last-30-day day buckets -> StatsResponse DTO {total_clicks, last_clicked_at, as_of=aggregate updated_at, clicks_by_day[{date,count}] sparse, ordered} (AC-8, AC-9, AC-14, AC-15)
- Scheduled retention job (in-process, interval + batch size configurable) -> DELETE FROM click_events WHERE occurred_at < now-90d (batched) -> click_stats and urls left untouched; metrics/log of purged row count (AC-12)

### Risks identified by impact analysis

| Id | Description | Likelihood | Severity | Mitigation |
|---|---|---|---|---|
| R-1 | HOT PATH: the extra transactional outbox INSERT on every successful redirect pushes p95 latency of GET /{short_code} beyond the 5% budget (extra row write, ind… | high | high | Keep the outbox row minimal with a single narrow index on (status, id); reuse the already-open redirect transaction with no additional round trips beyond one I… |
| R-2 | MIGRATIONS: new tables (click_outbox, click_events, click_stats, dedupe table) plus indexes are added to the shortener's existing schema; a long-running or loc… | medium | high | Additive, forward-only migrations via the existing migration tool (no ALTER on urls); create indexes concurrently where the engine supports it; separate analyt… |
| R-3 | DEPENDENCIES: introducing a Kafka client / spring-kafka (plus retry and possibly Testcontainers) can conflict with existing managed versions, inflate startup t… | medium | medium | Pin versions through the existing dependency-management BOM, run a dependency-tree/convergence check, scope test-only libraries to test scope, and confirm clie… |
| R-4 | PUBLIC CONTRACT: refactoring the read module to inject outbox recording, or adding shared DTO/error/security wiring for the new /api/v1 stats endpoint, acciden… | medium | high | Change-by-addition: new code lives in com.example.shortener.analytics.* and is invoked from a single seam in the read handler; no modification to existing DTOs… |
| R-5 | PRIVACY: a raw client IP leaks into the outbox payload, Kafka message, click_events, aggregate rows, access/debug logs, or an exception stack trace/MDC, breach… | medium | high | Hash at the earliest boundary in analytics.privacy so no raw-IP-typed field ever reaches the event/entity types; forbid IP fields on outbox/event/DTO classes; … |
| R-6 | Duplicate or lost analytics: at-least-once delivery plus poller/consumer retries double-count clicks, or a crash between publish and marking the outbox row pub… | high | medium | Carry a per-redirect UUID idempotency key on the outbox row and in the Kafka header; enforce exactly-once effect in the consumer via a unique constraint on the… |
| R-7 | Concurrent poller instances across application replicas contend on click_outbox (lock waits, SKIP LOCKED misuse, or hot-row contention), degrading DB throughpu… | medium | medium | Claim strictly bounded batches with SELECT ... FOR UPDATE SKIP LOCKED ordered by id, keep claim transactions short (claim -> publish outside the claiming lock … |
| R-8 | Eventual-consistency and semantics surprises on the stats endpoint: clients treat stats as real-time, or the zero-click / sparse-vs-dense clicks_by_day behavio… | medium | medium | Serve stats only from the click_stats aggregate with an as_of timestamp, document eventual consistency, implement the recorded defaults (200 with total_clicks=… |
| R-9 | Retention job (90-day purge of raw click_events) runs as a large unbatched DELETE, blocking or bloating the shared database, or incorrectly deletes click_stats… | medium | medium | Delete in configurable bounded batches with a sleep between batches, run off-peak on a dedicated connection, scope the statement to click_events only (never cl… |

## 4. Design

### API operations

| Method | Path | Operation | Responses | Breaking |
|---|---|---|---|---|
| GET | /{short_code} | redirectToTargetUrl | 302, 404 | False |
| POST | /api/v1/urls | createShortUrl | 201, 400, 401, 409 | False |
| GET | /api/v1/urls/{short_code} | getShortUrl | 200, 401, 404 | False |
| GET | /api/v1/urls/{short_code}/stats | getUrlClickStats | 200, 401, 404 | False |

### Tables

| Table | Columns | Constraints |
|---|---|---|
| click_outbox | {'name': 'id', 'type': 'BIGINT GENERATED BY DEFAULT AS IDENTITY', 'nullable': False, 'notes': 'surrogate primary key, also the claim/publish ordering key'}, {'… | CONSTRAINT pk_click_outbox PRIMARY KEY (id); CONSTRAINT uq_click_outbox_idempotency_key UNIQUE (idempotency_key); CONSTRAINT ck_click_outbox_status CHECK (stat… |
| click_stats | {'name': 'short_code', 'type': 'VARCHAR(64)', 'nullable': False, 'notes': 'primary key; owned by the analytics module, no FK to the existing url table to keep … | CONSTRAINT pk_click_stats PRIMARY KEY (short_code); CONSTRAINT ck_click_stats_total CHECK (total_clicks >= 0) |
| click_stats_daily | {'name': 'short_code', 'type': 'VARCHAR(64)', 'nullable': False, 'notes': 'part of the composite primary key'}, {'name': 'day', 'type': 'DATE', 'nullable': Fal… | CONSTRAINT pk_click_stats_daily PRIMARY KEY (short_code, day); CONSTRAINT ck_click_stats_daily_count CHECK (click_count >= 0); CONSTRAINT fk_click_stats_daily_… |
| processed_click_event | {'name': 'idempotency_key', 'type': 'UUID', 'nullable': False, 'notes': 'primary key; insert-first dedupe guard so a duplicate delivery is a no-op before any a… | CONSTRAINT pk_processed_click_event PRIMARY KEY (idempotency_key); CREATE INDEX idx_processed_click_event_processed_at ON processed_click_event (processed_at) |

### Migrations

```sql
-- src/main/resources/db/migration/V<next>__click_analytics.sql (Flyway, forward-only, additive).
-- The executor MUST renumber <next> to one above the highest existing script; no existing
-- table is altered and no data is rewritten, so this is an expand-only step.

CREATE TABLE click_outbox (
    id              BIGINT GENERATED BY DEFAULT AS IDENTITY,
    idempotency_key UUID        NOT NULL,
    short_code      VARCHAR(64) NOT NULL,
    occurred_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    hashed_ip       CHAR(64),
    referrer_host   VARCHAR(255),
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts        INTEGER     NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now(),
    published_at    TIMESTAMP(6) WITH TIME ZONE,
    last_error      VARCHAR(512),
    created_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT pk_click_outbox PRIMARY KEY (id),
    CONSTRAINT uq_click_outbox_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_click_outbox_status CHECK (status IN ('PENDING','PUBLISHED','FAILED')),
    CONSTRAINT ck_click_outbox_attempts CHECK (attempts >= 0)
);

CREATE INDEX idx_click_outbox_claim       ON click_outbox (status, next_attempt_at, id);
CREATE INDEX idx_click_outbox_occurred_at ON click_outbox (occurred_at);

COMMENT ON COLUMN click_outbox.hashed_ip IS 'Salted SHA-256 hex digest of the client IP; raw IP addresses are never persisted.';

CREATE TABLE click_stats (
    short_code      VARCHAR(64) NOT NULL,
    total_clicks    BIGINT      NOT NULL DEFAULT 0,
    last_clicked_at TIMESTAMP(6) WITH TIME ZONE,
    updated_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT pk_click_stats PRIMARY KEY (short_code),
    CONSTRAINT ck_click_stats_total CHECK (total_clicks >= 0)
);

CREATE TABLE click_stats_daily (
    short_code  VARCHAR(64) NOT NULL,
    day         DATE        NOT NULL,
    click_count BIGINT      NOT NULL DEFAULT 0,
    updated_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT pk_click_stats_daily PRIMARY KEY (short_code, day),
    CONSTRAINT ck_click_stats_daily_count CHECK (click_count >= 0),
    CONSTRAINT fk_click_stats_daily_short_code FOREIGN KEY (short_code)
        REFERENCES click_stats (short_code) ON DELETE CASCADE
);

CREATE INDEX idx_click_stats_daily_window ON click_stats_daily (short_code, day DESC);

CREATE TABLE processed_click_event (
    idempotency_key UUID        NOT NULL,
    short_code      VARCHAR(64) NOT NULL,
    processed_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT pk_processed_click_event PRIMARY KEY (idempotency_key)
);

CREATE INDEX idx_processed_click_event_processed_at ON processed_click_event (processed_at);
```

```sql
-- Reference statements used by the runtime components (NOT a migration file; they live in
-- repository code and are reproduced here so the executor implements them verbatim).

-- 1) Outbox claim (OutboxPoller / ClickOutboxRepository), bounded and multi-instance safe:
SELECT *
  FROM click_outbox
 WHERE status = 'PENDING'
   AND next_attempt_at <= now()
 ORDER BY id
   FOR UPDATE SKIP LOCKED
 LIMIT :batchSize;

-- 2) Aggregate upsert (ClickStatsAggregator), UTC day bucketing:
INSERT INTO click_stats (short_code, total_clicks, last_clicked_at, updated_at)
VALUES (:shortCode, 1, :occurredAt, now())
ON CONFLICT (short_code) DO UPDATE
   SET total_clicks    = click_stats.total_clicks + 1,
       last_clicked_at = GREATEST(COALESCE(click_stats.last_clicked_at, :occurredAt), :occurredAt),
       updated_at      = now();

INSERT INTO click_stats_daily (short_code, day, click_count, updated_at)
VALUES (:shortCode, CAST((:occurredAt AT TIME ZONE 'UTC') AS DATE), 1, now())
ON CONFLICT (short_code, day) DO UPDATE
   SET click_count = click_stats_daily.click_count + 1,
       updated_at  = now();

-- 3) Retention purge (ClickRetentionJob), bounded batches, aggregates untouched, PENDING kept:
DELETE FROM click_outbox
 WHERE id IN (
   SELECT id FROM click_outbox
    WHERE occurred_at < :cutoff
      AND status <> 'PENDING'
    ORDER BY id
    LIMIT :purgeBatchSize
 );

DELETE FROM processed_click_event
 WHERE idempotency_key IN (
   SELECT idempotency_key FROM processed_click_event
    WHERE processed_at < :cutoff
    ORDER BY processed_at
    LIMIT :purgeBatchSize
 );
```

```sql
-- Contract-phase script, DEFERRED to a later release; do NOT create it in this run.
-- Kept here so the expand/contract path is explicit and reviewable.
-- src/main/resources/db/migration/V<later>__click_analytics_contract.sql
--   DROP INDEX IF EXISTS idx_click_outbox_occurred_at;   -- once outbox rows are partitioned
--   ALTER TABLE click_outbox DROP COLUMN IF EXISTS last_error; -- once failures live in metrics only
-- Rollback of THIS release (T2 rollback_note) is likewise a forward migration:
--   DROP TABLE IF EXISTS processed_click_event;
--   DROP TABLE IF EXISTS click_stats_daily;
--   DROP TABLE IF EXISTS click_stats;
--   DROP TABLE IF EXISTS click_outbox;
```

### Data evolution notes

- Expand/contract only. This release is a pure expand step: four new tables, zero ALTERs on existing shortener tables, so an old application binary and the new schema coexist and a bad deploy is rolled back by redeploying the previous artifact (the new tables simply stop receiving writes). Contract steps (dropping last_error, partitioning click_outbox by occurred_at day so retention becomes a partition drop) are deferred to a later release and sketched in the third migration body. Flyway scripts are forward-only and immutable; the executor must renumber V<next> above the highest existing script and never edit an applied one.

Event versioning: the Kafka value carries an explicit `schema_version` field (starts at 1) and messages also carry an `event-version` header alongside `idempotency-key`. Producers only ever add optional fields within a version; a breaking payload change means schema_version 2 published to the same topic with consumers accepting both for at least one release (tolerant reader: unknown fields ignored, missing optional fields defaulted). The topic name url.clicked is owned by infrastructure and is not versioned in this release.

Data lifecycle: raw rows (click_outbox, processed_click_event) are purged after analytics.retention.days (default 90) in bounded batches; PENDING outbox rows are excluded from the purge so an unavailable broker never causes silent data loss. click_stats and click_stats_daily are aggregates and are never deleted or expired. The 30-day API window is a query-time filter, not a retention rule.

Ambiguity defaults materialised here: AMB-13 same relational database, analytics-owned tables; AMB-14/AMB-15 sparse clicks_by_day, zero-click code returns 200 with empty list; AMB-16 in-process scheduled purge; AMB-17 random per-redirect UUID idempotency key; AMB-18 configurable poller (500 ms / batch 100). Any answer that differs from these defaults invalidates this DataModel and requires a design revision.

### Packages and classes

**`com.example.shortener.analytics.config`** (may depend on: `com.example.shortener.analytics.kafka`)

| Class | Responsibility |
|---|---|
| AnalyticsProperties | Validated @ConfigurationProperties(prefix="analytics") holding the IP salt (resolved from the existing secret mechanism, never a committed literal), outbox pol… |
| AnalyticsSchedulingConfig | Enables scheduling for the analytics module and exposes a dedicated bounded TaskScheduler/executor so no analytics job can ever run on a request thread. |
| AnalyticsKafkaConfig | Declares the KafkaTemplate, producer/consumer factories, JSON (de)serialisation for ClickEventMessage and the url.clicked topic name binding; the topic itself … |

**`com.example.shortener.analytics.api`** (may depend on: `com.example.shortener.analytics.repository`, `com.example.shortener.analytics.domain`, `com.example.shortener.analytics.config`)

| Class | Responsibility |
|---|---|
| ClickStatsController | Exposes GET /api/v1/urls/{short_code}/stats (operationId getUrlClickStats) under the existing /api/v1 auth scheme; maps the query result to ClickStatsResponse … |
| ClickStatsQueryService | Verifies the short code exists via the existing URL lookup port, reads click_stats plus the last 30 UTC days of click_stats_daily, builds the sparse ascending … |
| ClickStatsResponse | Immutable snake_case response record: short_code, total_clicks, last_clicked_at (nullable), as_of, clicks_by_day. |
| ClickStatsDayEntry | Immutable record of one UTC day bucket: date (ISO date) and count (>=1). |

**`com.example.shortener.analytics.recording`** (may depend on: `com.example.shortener.analytics.repository`, `com.example.shortener.analytics.domain`, `com.example.shortener.analytics.config`)

| Class | Responsibility |
|---|---|
| ClickEventRecorder | Called by the existing redirect handler only after a redirect is known to be successful; builds and inserts exactly one PENDING click_outbox row (random UUID i… |
| IpHasher | Computes the lowercase hex SHA-256 of (configured static salt \|\| client IP) and guarantees the raw IP never leaves the method (no logging, no return, no toSt… |
| ReferrerHostExtractor | Parses the Referer header and returns only its host component, null when the header is absent, blank, unparsable as a URI or has no host; drops path, query and… |

**`com.example.shortener.analytics.outbox`** (may depend on: `com.example.shortener.analytics.repository`, `com.example.shortener.analytics.domain`, `com.example.shortener.analytics.kafka`, `com.example.shortener.analytics.support`, `com.example.shortener.analytics.config`)

| Class | Responsibility |
|---|---|
| OutboxPoller | @Scheduled (fixed delay = analytics.outbox.poll-interval-ms) trigger running on the analytics scheduler; invokes OutboxPublishService for one bounded batch per… |
| OutboxPublishService | Claims at most batchSize PENDING rows with SELECT ... FOR UPDATE SKIP LOCKED, publishes each through ClickEventProducer inside RetryExecutor, marks rows PUBLIS… |

**`com.example.shortener.analytics.kafka`** (may depend on: `com.example.shortener.analytics.config`)

| Class | Responsibility |
|---|---|
| ClickEventProducer | Sends one ClickEventMessage to topic url.clicked keyed by short_code with idempotency-key and event-version headers, bounded by the configured per-attempt time… |
| ClickEventMessage | Versioned wire record for url.clicked: schema_version, short_code, occurred_at (UTC), hashed_ip, referrer_host, idempotency_key; contains no raw IP and no othe… |

**`com.example.shortener.analytics.consumer`** (may depend on: `com.example.shortener.analytics.aggregation`, `com.example.shortener.analytics.kafka`, `com.example.shortener.analytics.support`, `com.example.shortener.analytics.config`)

| Class | Responsibility |
|---|---|
| ClickEventConsumer | @KafkaListener on url.clicked: deserialises ClickEventMessage (tolerant reader), delegates to ClickStatsAggregator in a single transaction, applies the shared … |

**`com.example.shortener.analytics.aggregation`** (may depend on: `com.example.shortener.analytics.repository`, `com.example.shortener.analytics.domain`)

| Class | Responsibility |
|---|---|
| ClickStatsAggregator | Transactionally inserts the idempotency key into processed_click_event FIRST (duplicate -> no-op return), then upserts click_stats (total +1, monotonic last_cl… |

**`com.example.shortener.analytics.retention`** (may depend on: `com.example.shortener.analytics.repository`, `com.example.shortener.analytics.config`, `com.example.shortener.analytics.support`)

| Class | Responsibility |
|---|---|
| ClickRetentionJob | @Scheduled in-process purge deleting non-PENDING click_outbox rows older than retention.days and processed_click_event rows older than the same cutoff in bound… |

**`com.example.shortener.analytics.support`** (may depend on: `com.example.shortener.analytics.config`)

| Class | Responsibility |
|---|---|
| RetryExecutor | Reusable bounded retry: max attempts, exponential backoff with full jitter capped at max backoff, per-attempt timeout, injectable clock/random source for deter… |
| AnalyticsMetrics | Counters and timers for outbox published/failed, consumer processed/duplicate/failed, retention rows purged and outbox backlog gauge; emits no payload fields. |

**`com.example.shortener.analytics.domain`**

| Class | Responsibility |
|---|---|
| ClickOutboxEntry | JPA entity mapping click_outbox, including status/attempts/next_attempt_at transitions expressed as domain methods (markPublished, scheduleRetry, markFailed). |
| OutboxStatus | Enum PENDING \| PUBLISHED \| FAILED mirroring the click_outbox check constraint. |
| ClickStats | JPA entity mapping click_stats (short_code PK, total_clicks, last_clicked_at, updated_at). |
| ClickStatsDaily | JPA entity mapping click_stats_daily with the composite (short_code, day) key and click_count. |
| ProcessedClickEvent | JPA entity mapping processed_click_event, the consumer-side dedupe ledger keyed by idempotency_key. |

**`com.example.shortener.analytics.repository`** (may depend on: `com.example.shortener.analytics.domain`)

| Class | Responsibility |
|---|---|
| ClickOutboxRepository | Persists outbox rows and exposes claimPendingBatch(limit, now) using SELECT ... FOR UPDATE SKIP LOCKED plus the bounded retention delete; no business rules. |
| ClickStatsRepository | Upsert and read access to click_stats (increment total, monotonic last_clicked_at, updated_at). |
| ClickStatsDailyRepository | Upsert of a UTC day bucket and windowed read of the last 30 days ordered by day ascending. |
| ProcessedClickEventRepository | Insert-if-absent of an idempotency key (returns whether the row was new) and the bounded retention delete by processed_at. |

**`com.example.shortener.redirect`** (may depend on: `com.example.shortener.analytics.recording`)

| Class | Responsibility |
|---|---|
| RedirectController | Existing public redirect endpoint; unchanged status codes and Location header. Only change: after a successful resolution it calls the recording port on the sa… |
| RedirectService | Existing resolution logic; on success (and only on success) invokes ClickEventRecorder inside the existing transaction, then returns the unchanged redirect out… |

### Layering rules

- Layer order is api -> (application services) -> repository -> domain; no class may depend on a layer above it.
- com.example.shortener.analytics.domain must not depend on any other application package (no Spring web, no Kafka, no repository types).
- com.example.shortener.analytics.repository may depend only on ..analytics.domain; it must not depend on api, outbox, consumer, kafka, recording or retention.
- com.example.shortener.analytics.api must not depend on ..analytics.outbox, ..analytics.consumer, ..analytics.kafka or ..analytics.retention: the stats endpoint reads aggregates only.
- com.example.shortener.analytics.recording must not depend on ..analytics.kafka, ..analytics.outbox or any Kafka/producer type; the redirect path performs zero network I/O.
- No package outside com.example.shortener.analytics.kafka and ..analytics.consumer and ..analytics.config may import org.apache.kafka.. or org.springframework.kafka...
- com.example.shortener.redirect may depend on com.example.shortener.analytics.recording only; it must not reach into analytics.repository, analytics.domain or analytics.api.
- Nothing in com.example.shortener.analytics may depend on com.example.shortener.redirect (analytics is a downstream consumer, not an upstream dependency): no cycles between top-level packages.
- Controllers (@RestController) exist only in ..api packages; @Entity classes exist only in ..domain packages; @Repository / Spring Data interfaces only in ..repository packages.
- @Scheduled methods may exist only in ..analytics.outbox and ..analytics.retention and must run on the dedicated analytics scheduler, never on a request thread.
- No class may log, return or store a raw client IP: java.net.InetAddress / remote-address values may be referenced only inside ..analytics.recording.IpHasher and the redirect controller that reads the request.
- Error responses are produced only by the existing global exception handler as application/problem+json; controllers must not build ad-hoc error bodies.

### Design decisions

- ADR-1: Record clicks via a transactional outbox written in the redirect transaction and drained by an in-process poller; rejected publishing to Kafka directly from the request thread, which couples redirect latency and availability to the broker (violates AC-3 and the 5% p95 budget).
- ADR-2: Claim outbox batches with SELECT ... FOR UPDATE SKIP LOCKED bounded by batchSize; rejected a leader-election/single-publisher instance, which adds coordination infrastructure and a single point of failure for a workload that is already row-partitionable.
- ADR-3: Use a random per-redirect UUID as the idempotency key stored on the outbox row and carried in a Kafka header (AMB-17 default); rejected a deterministic hash of (short_code, occurred_at, hashed_ip, referrer_host), which collapses two genuine clicks that share the same millisecond, IP hash and referrer.
- ADR-4: Deduplicate in the consumer by inserting into processed_click_event before any aggregate write (insert-first guard); rejected checking-then-writing or relying on Kafka exactly-once semantics, both of which double-count under concurrent redelivery (AC-6).
- ADR-5: Keep click_stats and click_stats_daily in the same relational database as the shortener, owned by the analytics module (AMB-13 default); rejected a dedicated analytics datastore, which is new protected infrastructure and out of scope for this release.
- ADR-6: Store per-day aggregates in a click_stats_daily table keyed (short_code, day); rejected a JSON/JSONB clicks_by_day column on click_stats, which makes concurrent per-day increments a read-modify-write hot spot and blocks indexed window queries.
- ADR-7: Return clicks_by_day as a sparse ascending list over the last 30 UTC days and 200/0/null/empty for a zero-click code (AMB-14 and AMB-15 defaults); rejected dense zero-filled days (bigger payload, server-side calendar synthesis) and rejected 404 for zero clicks (conflates 'no data' with 'no such link').
- ADR-8: Expose freshness through an explicit as_of field taken from click_stats.updated_at; rejected presenting stats as strongly consistent by reading the outbox at query time, which would put analytics work on the read path and still not be exact.
- ADR-9: Implement one shared RetryExecutor with bounded attempts, exponential backoff, full jitter, per-attempt timeout and an injectable random source (defaults 5 / 100 ms / 10 s, configurable per AMB-12 and AMB-18); rejected Kafka's built-in infinite producer retries and a dead-letter topic, which either retry forever or require infrastructure-owned topic provisioning.
- ADR-10: Terminal failures move the outbox row to FAILED with a truncated last_error plus a metric; rejected deleting failed rows or leaving them PENDING, which respectively loses evidence and creates an unbounded retry loop (AC-5).
- ADR-11: Hash client IPs with SHA-256 over a single static salt supplied by the existing secret mechanism and never commit the salt; rejected storing truncated or encrypted IPs, which remain reversible/re-identifiable personal data (AC-10).
- ADR-12: Purge raw click rows with an in-process scheduled job in bounded batches, excluding PENDING outbox rows (AMB-16 default); rejected a DB partition-drop job, which needs protected DDL/infrastructure ownership now and is deferred to the contract phase.
- ADR-13: Version the url.clicked payload with an explicit schema_version field plus an event-version header and tolerant-reader consumers; rejected an unversioned payload or a schema registry, the first blocking safe evolution and the second adding infrastructure outside this release's scope.
- ADR-14: Ship the stats endpoint as a purely additive operation under the existing /api/v1 path prefix and existing auth scheme, with the committed src/main/resources/openapi.yaml as the single source of truth verified against the springdoc dump from -Pit verify; rejected a new /api/v2 prefix or changing the redirect response shape, which breaks existing consumers (AC-14).
- ADR-15: Errors are emitted as application/problem+json by the existing global exception handler; rejected per-controller ad-hoc error bodies, which drift from the committed contract and fail the contract gate.
- ADR-16: Existing operations (redirectToTargetUrl, createShortUrl, getShortUrl) are documented here as-is and must be reconciled byte-for-byte with what the running application exposes before the contract gate runs; rejected rewriting them to a 'cleaner' shape, which would be a breaking change requiring an api.contract.breaking_change approval.

## 5. Plan

Version 1 (from spec v2). The DAG follows the dependency reality of an outbox pipeline in a brownfield service. T1 lands every protected build/config change first (dependencies, the `it` profile with the springdoc dump, analytics properties) because the compile, unit, integration and contract gates all depend on it, and it resolves AMB-12/16/17/18 to their documented defaults. T2 lands the additive schema plus JPA model in one protected-path task so that the writer, publisher, consumer and retention job all code against a stable, already-migrated shape. T3 then changes the hot redirect path in isolation, keeping the blast radius on the existing contract small and pairing it with tests that prove AC-2/AC-3/AC-10/AC-11. T4, T5 and T6 are independent leaves over the same schema (publisher, consumer, retention) and share the `analytics-pipeline` group: disjoint file globs, serialised execution in the shared tree. T7 is sequenced after the consumer because the stats projection and its as_of semantics depend on the aggregate tables, and it owns the additive OpenAPI change so the contract gate has a single source of truth. T8 closes the release checklist (Dockerfile, workflow, README, OpenAPI presence) and the cross-cutting integration/latency gates once all behaviour exists. Every task that touches src/** ships tests in the same commit, all globs stay inside allowed or protected paths, migrations/pom/config/Dockerfile/workflow tasks are HIGH, and the orchestrator-provisioned mvnw and ArchitectureTest are never listed in any allowed_files while each task's definition of done requires them to keep passing.

| Task | Title | Impact | Depends on | Group | Files (globs) | Committed as |
|---|---|---|---|---|---|---|
| T1 | Build, dependency and configuration foundation for click analytics | HIGH |  |  | 7 | 644e26db6b |
| T2 | Schema migrations plus persistence model for click_outbox and click_stats | HIGH | T1 |  | 5 | 5aadc57213 |
| T3 | Record click events transactionally on successful redirect (hashing + referrer host) | MEDIUM | T2 |  | 5 | cb36162095 |
| T4 | Outbox poller and Kafka publisher with bounded retries, backoff and jitter | MEDIUM | T2 | analytics-pipeline | 6 | 3ca3fbc1b0 |
| T5 | Idempotent analytics consumer aggregating url.clicked into click_stats | MEDIUM | T2 | analytics-pipeline | 4 | 914be70c20 |
| T6 | 90-day retention purge for raw click events | MEDIUM | T2 | analytics-pipeline | 3 | 39ed57bebb |
| T7 | Additive GET /api/v1/urls/{short_code}/stats endpoint and OpenAPI document | MEDIUM | T5 |  | 4 | 725d1120fc |
| T8 | Integration tests, latency gate and release artifacts | HIGH | T3, T4, T6, T7 |  | 6 | 725d1120fc |

### T1 — Build, dependency and configuration foundation for click analytics

- Allowed files: `pom.xml`, `src/main/resources/application*.yml`, `src/main/resources/application*.yaml`, `src/main/java/**/analytics/config/AnalyticsProperties.java`, `src/test/resources/application*.yml`, `src/test/java/**/analytics/config/AnalyticsPropertiesTest.java`, `docs/analytics.md`
- Data model slice: `AnalyticsProperties (salt, outbox.pollIntervalMs=500, outbox.batchSize=100, retry.maxAttempts=5, retry.baseBackoffMs=100, retry.maxBackoffMs=10000, retry.jitter=full, kafka.timeoutMs, retention.days=90)`
- Acceptance criteria: AC-5, AC-10
- Definition of done:
  - pom.xml adds spring-kafka, springdoc-openapi-starter-webmvc-ui, a resilience/retry utility or hand-rolled backoff (no new infra), and test deps (spring-kafka-test or testcontainers kafka + db) without changing existing module coordinates
  - pom.xml defines an `it` Maven profile that binds failsafe integration-test/verify AND springdoc-openapi-maven-plugin so `./mvnw -q -Pit verify` writes target/openapi.yaml (or target/openapi.json)
  - `./mvnw -q -DskipTests compile`, `./mvnw -q -Dtest=ArchitectureTest test` and `./mvnw -q test` still pass with the provisioned Maven wrapper untouched
  - application*.yml gains an `analytics.*` block with documented defaults (poll interval 500ms, batch 100, 5 attempts, base 100ms, cap 10s, full jitter, request timeout, retention 90 days) and the IP salt read from the existing secret/config mechanism as a placeholder (no literal secret committed)
  - AnalyticsProperties is a validated @ConfigurationProperties bean; AnalyticsPropertiesTest asserts defaults and validation bounds
  - docs/analytics.md records the resolved defaults for AMB-12, AMB-16, AMB-17 and AMB-18
- Risk notes: Touches the protected build file and application config; a wrong dependency version or profile binding breaks every downstream gate. The salt must never be committed as a literal value (security gate).
- Rollback: Revert pom.xml, the application*.yml analytics block and the new config class; no runtime behaviour depends on them yet.

### T2 — Schema migrations plus persistence model for click_outbox and click_stats

- Allowed files: `src/main/resources/db/migration/V*__click_analytics.sql`, `src/main/java/**/analytics/domain/**/*.java`, `src/main/java/**/analytics/repository/**/*.java`, `src/test/java/**/analytics/repository/**/*.java`, `docs/analytics.md`
- Data model slice: `click_outbox(id, idempotency_key UNIQUE, short_code, occurred_at TIMESTAMPTZ, hashed_ip, referrer_host NULL, status PENDING|PUBLISHED|FAILED, attempts, next_attempt_at, published_at, created_at)`, `click_stats(short_code PK, total_clicks, last_clicked_at, updated_at)`, `click_stats_daily(short_code, day DATE UTC, count, PK(short_code, day))`, `processed_click_event(idempotency_key PK, processed_at) for consumer dedupe`
- Acceptance criteria: AC-1, AC-6, AC-7, AC-10, AC-12
- Definition of done:
  - Additive forward-only migration script(s) create click_outbox, click_stats, click_stats_daily and processed_click_event with UTC timestamp columns and indexes on (status, next_attempt_at) and (occurred_at) for retention scans; no existing table is altered destructively
  - No column anywhere stores a raw IP address or other forbidden PII; only hashed_ip (SHA-256 hex) exists
  - JPA entities and repositories are added under the analytics package only; ClickOutboxRepository exposes a batch claim query using SELECT ... FOR UPDATE SKIP LOCKED with a LIMIT bound
  - Repository tests (in-memory/Testcontainers as the existing suite does) prove the migration applies, unique idempotency_key is enforced, and the SKIP LOCKED claim returns at most batchSize rows
  - `./mvnw -q test` and the ArchitectureTest pass
- Risk notes: Migration files live in a protected path; an incorrect or non-additive script can break startup for the existing shortener schema. Migration version numbering must not collide with existing scripts.
- Rollback: Drop the four new tables via a follow-up migration and delete the new entity/repository classes; existing tables are untouched.

### T3 — Record click events transactionally on successful redirect (hashing + referrer host)

- Allowed files: `src/main/java/**/analytics/recording/**/*.java`, `src/main/java/**/*RedirectController.java`, `src/main/java/**/*RedirectService.java`, `src/test/java/**/analytics/recording/**/*.java`, `src/test/java/**/redirect/**/*.java`
- Contract slice: `redirectToTargetUrl`
- Data model slice: `click_outbox insert (idempotency_key = random UUID per redirect, short_code, occurred_at UTC, hashed_ip, referrer_host)`
- Acceptance criteria: AC-1, AC-2, AC-3, AC-10, AC-11, AC-14
- Definition of done:
  - On a successful redirect the existing handler writes exactly one click_outbox row inside the same transaction as redirect handling, with a per-redirect random UUID idempotency key
  - Unknown/expired short codes produce no outbox row and no event
  - The redirect path performs no Kafka or network call and no blocking wait; recorder failures are caught, logged without raw IP, and never change the redirect status or Location header
  - IpHasher computes SHA-256 over (configured static salt + client IP) and returns hex; raw IP is never stored, logged or passed on
  - ReferrerHostExtractor returns the host component only and null for absent/unparsable Referer
  - Unit tests cover success, 404/expired, recorder-throws, missing/garbage Referer and hashing determinism; existing redirect contract tests pass unmodified
- Risk notes: Touches the hot redirect path of an existing service; any added latency or thrown exception directly regresses AC-3/AC-13 and the backward-compatibility criterion.
- Rollback: Remove the recorder call from the redirect handler and delete the recording package; redirect behaviour returns to the pre-change baseline.

### T4 — Outbox poller and Kafka publisher with bounded retries, backoff and jitter

- Allowed files: `src/main/java/**/analytics/outbox/**/*.java`, `src/main/java/**/analytics/kafka/ClickEventProducer.java`, `src/main/java/**/analytics/kafka/ClickEventMessage.java`, `src/main/java/**/analytics/support/RetryExecutor.java`, `src/test/java/**/analytics/outbox/**/*.java`, `src/test/java/**/analytics/kafka/**/*.java`
- Data model slice: `click_outbox status transitions PENDING -> PUBLISHED | FAILED`, `url.clicked message: key=short_code, value={short_code, occurred_at, hashed_ip, referrer_host}, header idempotency-key`
- Acceptance criteria: AC-3, AC-4, AC-5, AC-10
- Definition of done:
  - A @Scheduled in-process poller claims at most analytics.outbox.batchSize PENDING rows via SELECT ... FOR UPDATE SKIP LOCKED so concurrent instances never publish the same row twice
  - Each event is published to topic url.clicked keyed by short_code with the idempotency key in a message header; successful rows are marked PUBLISHED with published_at
  - RetryExecutor implements bounded attempts with exponential backoff plus full jitter and a per-attempt timeout from AnalyticsProperties; after exhaustion the row moves to FAILED, is logged (no raw IP) and a metric/counter is incremented, and it is never retried again
  - The poller never runs on a request thread and cannot block redirects
  - Unit tests cover batch claim bounds, key/header content, retry sequence with a deterministic jitter seed, timeout handling, exhaustion -> FAILED, and no-duplicate publish on a second poll
- Risk notes: Scheduling plus row locking can deadlock or starve under load; unbounded retries or missing timeouts would violate AC-5. Topic is provisioned by infrastructure, so tests must use an embedded/Testcontainers broker.
- Rollback: Disable the scheduler via the analytics property and delete the outbox/kafka publisher classes; rows simply stay PENDING.

### T5 — Idempotent analytics consumer aggregating url.clicked into click_stats

- Allowed files: `src/main/java/**/analytics/consumer/**/*.java`, `src/main/java/**/analytics/aggregation/**/*.java`, `src/test/java/**/analytics/consumer/**/*.java`, `src/test/java/**/analytics/aggregation/**/*.java`
- Data model slice: `click_stats(total_clicks, last_clicked_at, updated_at)`, `click_stats_daily(short_code, day UTC, count)`, `processed_click_event(idempotency_key)`
- Acceptance criteria: AC-5, AC-6, AC-7, AC-10
- Definition of done:
  - A Kafka listener on url.clicked deserializes the event and delegates to ClickStatsAggregator inside one transaction
  - Aggregation upserts click_stats (total_clicks, last_clicked_at, updated_at) and click_stats_daily using a UTC day bucket derived from occurred_at
  - Duplicate deliveries with the same idempotency key are detected via processed_click_event and are a no-op for totals, last_clicked_at and daily buckets
  - Consumer failures use the shared RetryExecutor policy (bounded attempts, backoff + jitter, timeout) and end in a logged/metered terminal state instead of infinite redelivery
  - No raw IP is logged or stored; only hashed_ip flows through
  - Tests cover single event, duplicate event, out-of-order events for last_clicked_at, UTC day bucketing across a midnight boundary, and retry exhaustion
- Risk notes: Consumer offset handling combined with transactional upserts can double-count if dedupe is applied after the write; day bucketing in a non-UTC JVM timezone is a common defect.
- Rollback: Remove the listener bean (or disable the analytics consumer property) and delete the consumer/aggregation classes; published events remain on the topic.

### T6 — 90-day retention purge for raw click events

- Allowed files: `src/main/java/**/analytics/retention/**/*.java`, `src/test/java/**/analytics/retention/**/*.java`, `docs/analytics.md`
- Data model slice: `click_outbox delete where occurred_at < now - retention.days`, `processed_click_event delete where processed_at < now - retention.days`
- Acceptance criteria: AC-12
- Definition of done:
  - An in-process scheduled job deletes raw click rows older than analytics.retention.days (default 90) in configurable bounded batches
  - click_stats and click_stats_daily aggregate rows are never touched by the job
  - The job logs the deleted count per run and never logs row payloads
  - Tests insert rows straddling the 90-day boundary and assert only the older raw rows are deleted while aggregates are unchanged, plus batch-size bounding
  - docs/analytics.md documents the retention job cadence and configuration
- Risk notes: A mis-scoped DELETE could remove aggregates or unpublished PENDING outbox rows; the query must exclude rows still awaiting publication.
- Rollback: Disable the retention schedule property and delete the retention package; already-deleted raw rows are not recoverable, which is the intended policy.

### T7 — Additive GET /api/v1/urls/{short_code}/stats endpoint and OpenAPI document

- Allowed files: `src/main/java/**/analytics/api/**/*.java`, `src/main/resources/openapi.yaml`, `src/test/java/**/analytics/api/**/*.java`, `docs/analytics.md`
- Contract slice: `getUrlClickStats`
- Data model slice: `ClickStatsResponse{total_clicks, last_clicked_at, as_of, clicks_by_day:[{date, count}]}`
- Acceptance criteria: AC-8, AC-9, AC-14, AC-15
- Definition of done:
  - GET /api/v1/urls/{short_code}/stats returns 200 with total_clicks, last_clicked_at, as_of (click_stats.updated_at, now when no aggregate exists) and clicks_by_day ordered ascending over the last 30 UTC days as a sparse list (days with >=1 click only)
  - An existing short code with zero clicks returns 200 with total_clicks=0, last_clicked_at=null and an empty clicks_by_day
  - An unknown short code returns 404 using the existing error representation
  - The endpoint is secured by the existing /api/v1 auth scheme with no change to that scheme, and no existing operation, field or status code is modified
  - src/main/resources/openapi.yaml is updated additively with operationId getUrlClickStats, its 200/401/404 responses and the new schemas, and matches the springdoc dump produced by `-Pit verify`
  - MockMvc/WebMvc tests cover 200 with data, 200 empty, 404, unauthenticated, and the 30-day window boundary
- Risk notes: The contract gate compares the committed document to the running app operation-by-operation and status-by-status; any drift (including security responses) fails the build. Field naming must be snake_case exactly as specified.
- Rollback: Remove the controller/response classes and revert the additive openapi.yaml block; all pre-existing operations are unchanged.

### T8 — Integration tests, latency gate and release artifacts

- Allowed files: `Dockerfile`, `.github/workflows/ci.yml`, `README.md`, `src/test/java/**/analytics/it/**/*IT.java`, `src/test/resources/**`, `docs/analytics.md`
- Contract slice: `redirectToTargetUrl`, `getUrlClickStats`
- Acceptance criteria: AC-1, AC-3, AC-4, AC-6, AC-8, AC-13, AC-14, AC-15
- Definition of done:
  - End-to-end IT: redirect -> outbox row -> poller publish -> consumer aggregate -> stats endpoint returns the click, including a duplicate delivery proving exactly-once aggregation
  - KafkaUnavailableRedirectIT proves the redirect keeps its status and Location header with the broker down and the event stays PENDING in the outbox
  - RedirectLatencyBudgetIT measures p95 of GET /{short_code} against a recorded baseline and fails when the increase exceeds 5%
  - `./mvnw -q -Pit verify` runs these ITs and emits target/openapi.yaml; the dump matches the committed src/main/resources/openapi.yaml
  - Dockerfile builds and runs the application with the Maven wrapper, and .github/workflows/ci.yml runs compile, spotless:check (advisory), ArchitectureTest, `./mvnw -q test` and `./mvnw -q -Pit verify` including the latency gate
  - README.md documents the analytics pipeline, configuration properties, privacy/retention guarantees and the new stats endpoint
  - No secrets are committed; the IP salt is supplied via environment/CI secret reference only
- Risk notes: Touches protected release paths (Dockerfile, workflow). Flaky container-based ITs or a noisy latency measurement could block the release gate; the latency test must use warmup and a fixed sample size.
- Rollback: Revert Dockerfile/workflow/README changes and delete the IT classes; the release gate reverts to the previous pipeline definition.

## 6. Security findings and risk register

### Security findings

| Id | Severity | Area | Description | Requirement |
|---|---|---|---|---|
| SEC-001 | high | authorization | GET /api/v1/urls/{short_code}/stats is specified only as 'reuses the existing /api/v1 auth scheme'. The design's ClickStatsQueryService performs an existence c… | The stats endpoint MUST apply exactly the same authorization predicate as the pre-existing getShortUrl operation, including any owner/tenant scoping, and MUST … |
| SEC-002 | high | pii / cryptography | hashed_ip is SHA-256 over a single static salt concatenated with the client IP. The IPv4 space is ~2^32, so once the salt is known (or brute-forced/leaked from… | Implement the digest as HMAC-SHA256(key=salt, message=normalised client IP) or SHA-256(salt \|\| 0x00 \|\| ip) with the salt being a >=32-byte high-entropy sec… |
| SEC-003 | high | input validation / untrusted deserialization | ClickEventConsumer deserialises JSON from the url.clicked topic. If the JSON deserialiser is configured with default/polymorphic typing or without trusted-pack… | Configure the Kafka JSON deserialiser with an explicit target type and TRUSTED_PACKAGES restricted to the analytics wire package, disable default/polymorphic t… |
| SEC-004 | high | secret handling / transport security | AnalyticsKafkaConfig introduces broker connectivity but the design does not state transport security or credential sourcing for producer and consumer, nor topi… | Producer and consumer MUST use TLS (SSL/SASL_SSL) with hostname verification enabled and credentials/keystore passwords resolved from the existing secret mecha… |
| SEC-005 | high | pii / retention | The retention purge intentionally excludes PENDING click_outbox rows. If the broker is unavailable or rows are stuck (attempts not yet exhausted, poller disabl… | Bound the lifetime of every raw row: PENDING rows older than a configured max-age MUST be transitioned to FAILED (logged and metered) so the 90-day purge can c… |
| SEC-006 | medium | input validation / client identity | The client IP used for hashing on an unauthenticated public endpoint is typically taken from X-Forwarded-For/Forwarded, which is attacker-controlled unless the… | Derive the client IP only via the framework's forwarded-headers support restricted to a configured trusted-proxy list (or the socket remote address when no tru… |
| SEC-007 | medium | input validation / stored untrusted data | referrer_host is derived from the attacker-controlled Referer header and persisted (VARCHAR(255)) and republished. Without normalisation, values may contain us… | ReferrerHostExtractor MUST parse with java.net.URI, take the host component only (dropping scheme, userinfo, port, path, query, fragment), lowercase it, strip … |
| SEC-008 | medium | availability / rate limiting | Every unauthenticated GET /{short_code} now performs an additional INSERT, so an attacker hammering one or many short codes converts a cheap read into sustaine… | Apply rate limiting / throttling: reuse or add per-client (trusted-IP-derived or token-based) rate limits on GET /{short_code} and on GET /api/v1/urls/{short_c… |
| SEC-009 | medium | availability / brownfield regression | ClickEventRecorder runs inside the redirect's transaction and 'swallows and logs' its own failures. In Spring/JPA, a failed statement or a swallowed exception … | Guarantee that no analytics failure can change the redirect outcome: either execute the outbox insert so that its failure cannot poison the caller's transactio… |
| SEC-010 | medium | logging hygiene | New components log retries, terminal failures, purge counts, and consumer errors. Attacker-controlled values (short_code, referrer_host, Referer, idempotency k… | Establish logging hygiene rules and enforce them: never log raw client IP, the salt, Kafka credentials, full event payloads, or Authorization headers; log shor… |
| SEC-011 | medium | sql / data integrity | The design specifies native SQL for the SKIP LOCKED claim, the ON CONFLICT upserts, and the batched retention deletes, with :batchSize/:cutoff/:shortCode place… | All native queries MUST use bound parameters exclusively (including LIMIT via parameter or a validated integer property) with no string interpolation of any re… |
| SEC-012 | medium | dependency policy | The change introduces new runtime dependencies (spring-kafka / kafka-clients, JSON serialisation, metrics) into a brownfield service. Unpinned, transitively up… | Add dependencies only through the existing Spring Boot/managed BOM with pinned, non-SNAPSHOT versions; run the project's SCA/vulnerability gate (e.g. dependenc… |
| SEC-013 | low | observability / resource exhaustion | AnalyticsMetrics exposes counters, timers, and an outbox backlog gauge. Tagging any metric with short_code, referrer_host, or idempotency key creates unbounded… | Metrics MUST use only bounded, low-cardinality tags (outcome, status, topic, job); no short_code, referrer_host, hashed_ip, idempotency key, or exception messa… |
| SEC-014 | low | replay / idempotency | The idempotency key is a random per-redirect UUID carried in a Kafka header while the consumer dedupes on the message body's idempotency_key. A mismatch betwee… | Define one authoritative source for the dedupe key (body field), validate that the header and body agree and reject the message to the terminal logged/metered … |
| SEC-015 | low | privacy governance | Salted hashed IPs remain personal data under GDPR-style regimes, click_stats aggregates are retained indefinitely, and salt rotation / historical re-hashing is… | Before release, obtain DPO acknowledgement (referencing US-4/AC-10/AC-12) that: hashed_ip is treated as pseudonymised personal data with 90-day retention, clic… |

Required controls:

- Input validation: validate short_code against ^[A-Za-z0-9_-]{1,64}$ at the controller boundary; validate every consumed ClickEventMessage field (short_code pattern, hashed_ip 64-hex or null, UUID key, known schema_version, referrer_host <=255 chars without control characters, occurred_at inside a configured skew/retention window); normalise and constrain referrer host (host-only, lowercase, no userinfo/port/path/query, allow-listed character set); parse client IP as InetAddress or treat as unavailable; validate all @ConfigurationProperties (positive, capped batch sizes, intervals, attempts, timeouts, retention days) with fail-fast startup binding.
- Rate limiting and backpressure: per-client throttling on the public redirect and on the new stats endpoint; hard caps on outbox claim batch size, consumer max.poll.records, retention purge batch size, and the dedicated analytics scheduler/executor pool; bounded per-attempt Kafka timeouts and bounded total attempts (no infinite producer retries); an outbox backlog ceiling with analytics-drop-before-redirect-degradation behaviour; separate/limited DB connection usage so analytics jobs cannot starve the redirect path.
- Secret handling: IP salt and all Kafka credentials/keystore passwords resolved at runtime from the existing secret mechanism; no secrets, salts, or bootstrap credentials in application.yml defaults, Flyway scripts, test fixtures, docker-compose, or CI logs; salt held in a non-printable wrapper with no getter/toString exposure; fail-closed startup when the salt or broker credentials are missing; TLS with hostname verification for broker connections.
- PII handling: raw client IP never stored, returned, logged, or passed outside IpHasher; HMAC-SHA256 (or salt-delimited SHA-256) with a >=32-byte secret salt; DB CHECK constraint enforcing hex-digest format on hashed_ip; stats API exposes no hashed_ip or referrer data; raw rows (click_outbox, processed_click_event) bounded to 90 days for all statuses including stuck PENDING; aggregates limited to non-identifying counters; documented DPO acceptance of the static-salt and indefinite-aggregate decisions.
- Logging hygiene: no raw IP, salt, credentials, Authorization header, or full event payload in any log or error field; control-character stripping/encoding for short_code, referrer_host, and exception text before interpolation; sanitised and truncated last_error; retention and poller jobs log counts and identifiers only; automated architecture/static test enforcing the 'no raw IP outside IpHasher' and 'no raw Referer in logs' rules; error responses only via the existing global problem+json handler so no internal detail leaks.
- Dependency policy: new dependencies only via the managed Boot/Kafka BOM with pinned non-SNAPSHOT versions; SCA/CVE gate failing the build on new High/Critical vulnerabilities; license check and explicit PR listing of added coordinates; test container/embedded-broker images pinned; ErrorHandlingDeserializer plus TRUSTED_PACKAGES and no default/polymorphic typing for JSON deserialisation.

High-impact actions expected: `Adding new runtime dependencies (spring-kafka/kafka-clients, JSON serialisation, metrics registry) to the existing build - dependency approval expected.`, `Creating and applying a forward-only Flyway migration that adds click_outbox, click_stats, click_stats_daily, processed_click_event plus indexes and CHECK constraints to the production database schema (expand-only, no ALTERs on existing tables) - schema change approval expected.`, `Provisioning a new long-lived secret (the >=32-byte IP hashing salt) in the existing secret store and wiring it as a required, fail-closed startup property - secret creation/handling approval expected.`, `Requesting infrastructure-owned Kafka assets: url.clicked topic access, a dedicated consumer group, produce/consume ACLs for the service principal, and TLS/SASL credentials - external infrastructure change approval expected.`, `Modifying the pre-existing RedirectService/RedirectController hot path (the public, unauthenticated redirect) to invoke the recorder inside the existing transaction - brownfield behaviour-change approval expected, gated by the unchanged contract test suite and the 5% p95 load-test gate.`, `Touching security configuration to bring GET /api/v1/urls/{short_code}/stats under the existing auth scheme and to add the owner/tenant authorization predicate - authorization model change approval expected (and an ambiguity answer if the existing scheme has no ownership concept).`, `Enabling forwarded-header handling / trusted-proxy configuration so the client IP used for hashing is not attacker-controlled - request-pipeline configuration approval expected.`, `Enabling Spring scheduling with a new dedicated bounded TaskScheduler/executor and two @Scheduled jobs (outbox poller, retention purge) that run continuously in every application instance, including a bulk DELETE job against production data - background-job and data-deletion approval expected.`, `Introducing rate limiting/throttling on the public redirect endpoint and the new stats endpoint, which can change observable behaviour for existing clients (429s) - contract-impact approval expected.`, `Adding the automated load-test gate and the committed openapi.yaml reconciliation to CI, including failing the build on p95 regression or contract drift - pipeline change approval expected.`, `Recording accepted risks with sign-off: non-rotatable static salt with residual IP re-identification risk, indefinite retention of click_stats aggregates, and eventual-consistency of the stats endpoint.`

### Risk register

| Id | Description | Likelihood | Severity | Mitigation | Detection |
|---|---|---|---|---|---|
| R-01 | Hot-path latency regression on GET /{short_code}: the additional transactional outbox INSERT (plus index maintenance on idx_click_outbox_claim, longer transact… | high | high | Single narrow INSERT with pre-computed values (hash and referrer host computed in-memory, no DNS/no reverse lookup, no extra round trips); reuse the already-op… | CI load-test gate comparing candidate p95 of GET /{short_code} against the recorded pre-change baseline and failing the build above +5% (AC-13); production per… |
| R-02 | Transaction-semantics conflict: AC-1 requires the outbox row in the same transaction as redirect handling, while AC-3/US-2 require the redirect to be unaffecte… | medium | high | Perform the outbox write via a plain JDBC/JdbcTemplate statement in the current transaction (no JPA flush of a broken persistence context), validate and clamp … | Integration test that forces the outbox INSERT to fail (constraint violation, table missing, statement timeout) and asserts the response is still the unchanged… |
| R-03 | Bounded retries cause permanent analytics data loss during a broker outage longer than the retry window: with 5 attempts / cap 10s / full jitter, a claimed row… | high | high | Classify failures: broker-unreachable / no-leader / timeout errors must reschedule the row as PENDING with backoff (attempt counter not consumed or a separate … | Chaos/integration test that stops the broker for longer than the retry window and asserts events are still published after recovery; alerts on any non-zero cli… |
| R-04 | Unbounded outbox growth and hot-path degradation during a prolonged broker or consumer outage: PENDING rows are deliberately excluded from the 90-day purge, so… | medium | high | Expose a backlog-size gauge and a documented shed threshold: beyond it, either disable click recording via the feature flag (redirect keeps working, analytics … | Backlog count and oldest-PENDING-age gauges with warning/critical alerts; database table/index size and autovacuum/bloat metrics; load test at peak rate with t… |
| R-05 | Raw client IP leakage into the outbox table, Kafka payload/headers, click_stats, application or access logs, MDC, or exception stack traces, breaching AC-10 an… | medium | high | Hash at the earliest boundary inside analytics.recording.IpHasher; no IP-typed or IP-named field on ClickOutboxEntry, ClickEventMessage or any DTO; enforce the… | Unit/integration tests regex-asserting persisted rows, produced Kafka records (value plus headers) and captured log output contain no IPv4/IPv6 literal; an Arc… |
| R-06 | Re-identification risk from a single static, never-rotated salt: SHA-256 over a 32-bit IPv4 space is exhaustively enumerable if the salt is ever disclosed (log… | low | high | Store the salt only in the existing secret mechanism with restricted access and no default/committed literal; fail fast at startup when it is absent or weak ra… | Startup validation test asserting the application refuses to boot with a missing/blank salt; secret-scanning gate over the repository and rendered config; peri… |
| R-07 | Duplicate or double-counted clicks: at-least-once Kafka delivery plus a crash between a successful publish and marking the outbox row PUBLISHED re-publishes th… | high | medium | Insert-first dedupe: write idempotency_key into processed_click_event inside the same transaction as the aggregate upserts and treat a primary-key conflict as … | Test replaying an identical event (and a concurrent duplicate from two consumer threads) and asserting total_clicks, last_clicked_at and day buckets are unchan… |
| R-08 | Late duplicate after retention: processed_click_event rows are purged at 90 days, so a redelivery or manual re-drive of an event older than the cutoff re-incre… | low | low | Keep the dedupe ledger retention greater than or equal to the maximum broker retention plus any re-drive window, and document that manual re-drives of events o… | Test asserting an event whose dedupe row was purged is rejected or reconciled rather than silently re-aggregated; alert on aggregate increments whose occurred_… |
| R-09 | Irrecoverable aggregates: the design keeps no durable raw click event table (outbox rows are purged and only the aggregate survives), so any aggregation bug, d… | medium | high | Resolve explicitly whether raw click rows are retained as a separate table (as the impact analysis assumes) or whether click_outbox is the raw store (as the da… | Design review checklist item reconciling AC-12 wording with the migration set; consistency check comparing sum(click_stats_daily.click_count) with click_stats.… |
| R-10 | SQL portability/dialect failure: the design uses PostgreSQL-specific constructs (FOR UPDATE SKIP LOCKED with LIMIT, ON CONFLICT, GREATEST over timestamptz, reg… | medium | high | Run all persistence and integration tests against the production engine via Testcontainers, not an in-memory substitute; keep the claim/upsert statements as na… | Testcontainers-backed integration tests exercising claim, upsert and purge statements; Flyway validate/checksum and migration dry-run gates in CI; schema-diff … |
| R-11 | Consumer stalls or rebalance loop: the shared blocking RetryExecutor runs on the KafkaListener thread, so retry sleeps plus per-attempt timeouts over a batch c… | medium | medium | Bound total in-listener retry time to a documented fraction of max.poll.interval.ms (attempts x per-attempt timeout + max backoff), tune max.poll.records, and … | Consumer lag, rebalance-count, processing-time and consumer-failed counters with alerts; integration test injecting a malformed and a permanently failing recor… |
| R-12 | Multi-instance scheduled job contention: every replica runs the @Scheduled poller and the retention job with no leader election, causing claim conflicts, lock … | medium | medium | Bounded batches claimed with ORDER BY id FOR UPDATE SKIP LOCKED and short claim transactions; single-threaded poller per instance with configurable interval/ba… | Multi-instance integration test asserting no event is published twice and no instance starves; DB lock-wait/deadlock metrics, claim-conflict and batch-duration… |
| R-13 | Backward-compatibility break on existing contracts: wiring the recorder into the read path or plugging the new stats controller into shared security/DTO/error … | medium | high | Change-by-addition only: new code in com.example.shortener.analytics.* invoked from one seam in the read handler, no edits to existing DTOs, handlers or the se… | Existing contract/integration suite (com.example.shortener.it) executed unmodified as a release gate; OpenAPI diff between the committed spec and the springdoc… |
| R-14 | Unanswered ambiguities materialised as defaults diverge from stakeholder intent: retry bounds (AMB-12), stats store (AMB-13), zero-click response (AMB-14), spa… | medium | medium | Treat the recorded defaults as provisional and obtain explicit confirmation before the contract and schema are frozen; keep every default behind configuration … | Governance checklist requiring each ambiguity to be answered or explicitly waived before the contract gate; contract tests pinned to the chosen zero-click and … |
| R-15 | Misleading eventual-consistency reporting: as_of falls back to now() when no click_stats row exists, so a short code whose clicks are still pending in the outb… | medium | low | Document eventual consistency in the OpenAPI description and define the no-aggregate as_of semantics explicitly (current instant, total_clicks=0, last_clicked_… | Contract tests for the unknown-code (404), existing-but-zero-clicks (200/0/null/empty) and published-but-unconsumed cases asserting as_of semantics; monitoring… |
| R-16 | Stats endpoint performance and hot-code skew: the 30-day window query over click_stats_daily, or a very popular short_code whose day-bucket row is updated on e… | low | medium | Bound the query to 30 days using idx_click_stats_daily_window (short_code, day DESC) and select only the needed columns; keep the per-day upsert as a single at… | Query-plan and latency test on the stats endpoint against a large click_stats_daily dataset; endpoint latency histogram plus DB row-lock wait metrics for click… |
| R-17 | New dependency risk from spring-kafka/kafka-clients (and Testcontainers): version conflicts with the existing managed BOM, slower startup, transitive CVEs, or … | medium | medium | Pin versions through the existing dependency-management BOM, keep test-only libraries in test scope, run a dependency-convergence check, and verify client/brok… | Build-time dependency convergence and SCA/CVE scan gates; startup health check on producer/consumer factory creation and topic metadata; Testcontainers Kafka i… |
| R-18 | Operational dependency on infrastructure: the url.clicked topic is provisioned outside this release (non-goal). If it is missing, misconfigured (wrong partitio… | medium | high | Make the topic a documented, verified deploy precondition with required partitions, retention (>= re-drive window) and ACLs; fail the deployment smoke test rat… | Post-deploy smoke check querying topic metadata and performing a canary publish/consume; producer error-by-type counters (authorization, unknown-topic) alertin… |
| R-19 | Migration and rollback hazards: adding four tables plus indexes to the live shortener schema can take locks or fail mid-way, and the documented rollback (DROP … | medium | high | Pure expand step with zero ALTERs on existing tables so the previous application binary and the new schema coexist; create indexes concurrently where the engin… | Migration dry-run, Flyway validate and duration/lock-wait metrics during deploy; post-deploy smoke test asserting redirect and POST /api/v1/urls behave unchang… |
| R-20 | Clock and time-zone correctness: occurred_at is taken from application clocks across replicas, so skew can place a click in the wrong UTC day bucket or (with G… | low | medium | Derive all day buckets with an explicit UTC conversion in SQL (occurred_at AT TIME ZONE 'UTC') and never from the JVM default zone; use timestamptz columns end… | Tests running with a non-UTC JVM default zone asserting identical bucketing; boundary tests at 23:59:59Z/00:00:00Z; alert on last_clicked_at values in the futu… |
| R-21 | The +5% p95 latency gate itself is fragile: no recorded pre-change baseline, noisy CI runners, or an unrepresentative load profile makes the gate either falsel… | medium | medium | Capture and version the baseline from the unmodified build on the same runner class and dataset before the change lands; run repeated iterations with warm-up a… | Gate report publishing baseline vs candidate p95 with run-to-run variance; trend dashboard of the gate metric across builds to expose noise; production p95 com… |

Trade-offs:

- Transactional outbox over direct publish from the request thread: buys redirect availability and latency independence from Kafka (AC-3, US-2) at the cost of one extra write on the hot path, a new table on the shared database, an additional moving part (poller), and end-to-end delivery latency of at least one poll interval.
- Bounded retries with terminal FAILED state over infinite retry: buys bounded resource use and no unbounded retry loops (AC-5) at the cost of possible permanent analytics loss during outages longer than the retry window, requiring an explicit operator re-drive procedure.
- Eventual consistency with an as_of timestamp over strongly consistent reads: keeps the read path cheap and the write path off the query path at the cost of stale totals immediately after a click and of client confusion that must be handled by documentation, not by code.
- click_stats/click_stats_daily in the shortener's own database (AMB-13 default) over a dedicated analytics store: avoids new protected infrastructure and cross-store consistency at the cost of coupling analytics write/read load, storage growth and purge activity to the database serving redirects.
- Normalised click_stats_daily table over a JSON clicks_by_day column: enables atomic per-day increments and indexed 30-day window queries at the cost of an extra table, an extra FK/upsert per event and a second write per click.
- Sparse ascending clicks_by_day and 200/0/null/empty for zero-click codes (AMB-14/AMB-15 defaults) over dense zero-filled days: smaller payload and no server-side calendar synthesis, but every client must zero-fill locally and cannot distinguish 'no data yet' from 'no clicks' without reading total_clicks.
- Random per-redirect UUID idempotency key (ADR-3) over a deterministic content hash: never collapses two genuine clicks sharing timestamp/IP hash/referrer, but cannot suppress duplicates created before the key is assigned and requires a durable dedupe ledger in the consumer.
- In-process poller with FOR UPDATE SKIP LOCKED over leader election or a separate worker service: no new coordination infrastructure or single point of failure, at the cost of every replica competing on the same table and sharing the application's thread and connection budget with request handling.
- In-process scheduled purge (AMB-16 default) over partition drop: no protected DDL or infrastructure ownership now, at the cost of repeated bounded DELETEs, bloat pressure on the shared database, and deferral of the cheaper partition-drop design to a later contract release.
- Single static, never-rotated salt (non-goal to rotate) over rotating salts or a keyed HMAC in an HSM: simple and keeps hashes comparable over time, but a salt disclosure makes the small IP space enumerable and there is no remediation path for historical hashes.
- Retaining aggregates forever while purging raw rows at 90 days: satisfies the retention policy and keeps long-term trends, but removes any ability to recompute aggregates, so an aggregation defect is permanent.
- Additive-only API under the existing /api/v1 prefix and auth scheme (ADR-14/ADR-16) over a cleaner /api/v2 shape: guarantees existing consumers keep working and the contract suite passes unmodified, at the cost of carrying pre-existing contract quirks forward unchanged.
- Blocking retry inside the Kafka listener over an async/dead-letter pipeline: avoids infrastructure-owned topic provisioning, at the cost of consumption throughput during retries and rebalance risk if total retry time approaches max.poll.interval.ms.

Failure scenarios:

- Kafka unreachable or slower than the per-attempt timeout: GET /{short_code} continues to return its unchanged status and Location header with no Kafka call on the request thread; outbox rows accumulate as PENDING with exponential backoff plus full jitter; stats responses go stale and as_of stops advancing; expected degraded mode is 'redirects fully healthy, analytics delayed', with alerts on backlog age. Safe-stop threshold: when the backlog exceeds the documented limit an operator disables click recording via the feature flag (redirect unaffected, clicks knowingly dropped) rather than letting the table grow without bound.
- Broker outage outlasting the retry budget: affected rows reach terminal FAILED with a truncated last_error and a metric; the system must not retry them forever. Expected behaviour is an alert on non-zero FAILED rows plus a documented operator re-drive (reset to PENDING) after recovery; until the AC-3/AC-5 boundary is clarified (R-03), these events are analytics loss, not silent success.
- Database unavailable or statement timeout during a redirect: the existing redirect behaviour governs the response (unchanged error contract); no partially written outbox row may exist because the row is committed in the redirect transaction. On recovery the poller resumes from PENDING rows; no compensating action is required.
- Outbox INSERT fails while the redirect resolution succeeded: the required behaviour is that the redirect response stays unchanged (302 + Location) and the click is counted as lost via an outbox-write-failure metric; the implementation must not leave the transaction rollback-only. If atomicity (AC-1) is deemed to outrank redirect availability (AC-3), the alternative behaviour must be explicitly approved - this is currently an unresolved conflict, not an implementation choice.
- Application crash between a successful Kafka publish and marking the row PUBLISHED: after restart the poller re-claims the row and republishes; the consumer's insert-first processed_click_event guard makes the second delivery a no-op, so click_stats, last_clicked_at and clicks_by_day still reflect the click exactly once (AC-6).
- Duplicate or out-of-order delivery: duplicates are dropped by the dedupe ledger and counted as duplicate-skipped; out-of-order events cannot move last_clicked_at backwards because it is updated with GREATEST, and day buckets are keyed by the event's own UTC day.
- Poison or unparsable url.clicked payload: the tolerant reader ignores unknown fields and defaults missing optional ones; a genuinely undeserialisable or permanently failing record is logged and metered as a terminal consumer failure and the offset advances so the partition is never blocked. No dead-letter topic exists in this release, so the terminal drop must be alerted on and accepted.
- Consumer retry time approaching max.poll.interval.ms: the listener must bound total retry time below the poll interval; if a rebalance loop is detected the expected safe-stop is to pause/stop the listener container (consumer lag grows, redirects and the stats endpoint keep serving stale aggregates) rather than churn partitions indefinitely.
- Two or more replicas polling concurrently: FOR UPDATE SKIP LOCKED with bounded batches guarantees each row is claimed by exactly one instance; contention shows up as claim-conflict and lock-wait metrics only. If lock waits threaten the redirect path, reduce batch size or increase the poll interval via configuration (no redeploy).
- Retention purge overruns or contends with live traffic: purge runs in bounded batches and must be safely interruptible; on overrun it stops at the batch boundary (safe-stop) leaving older rows for the next run, and it never deletes click_stats, click_stats_daily or PENDING outbox rows. Alerting on non-decreasing oldest-raw-row age detects a stuck purge.
- Stats requested for a short code with pending or unconsumed clicks: response is 200 with the aggregate as it currently stands and an as_of reflecting the last aggregate update (AC-15); it must never read the outbox or block on the consumer.
- Stats requested for an unknown short code: 404 problem+json from the global exception handler (AC-9); no aggregate row is created as a side effect.
- Migration failure mid-deploy: Flyway aborts, the release is stopped before the new binary serves traffic, and the previous artifact keeps running against the partially applied schema because all changes are additive (no ALTERs on existing tables). Remediation is a forward fix-up migration, never editing an applied script.
- Bad analytics release detected after deploy (latency regression, privacy defect, aggregation bug): primary rollback is redeploying the previous application artifact - the new tables simply stop receiving writes and existing contracts are unaffected; fastest mitigation without redeploy is the recording feature flag. Dropping the analytics tables is an exceptional, approved action only after confirming no PENDING rows and accepting permanent loss of click_stats, since aggregates cannot be recomputed.
- CI load-test gate reports p95 above baseline +5%: the build fails and the release is blocked (AC-13). If no valid recorded baseline exists, the gate must fail as inconclusive rather than pass by default.
- Salt missing, blank or unreadable at startup: the application fails fast and refuses to start the analytics module (safe-stop) instead of hashing with an empty salt; if the module can be started without recording, the documented behaviour is recording disabled with a critical alert, never plaintext or unsalted IP storage.

## 7. Human decisions

| Node | Action | Decision | By |
|---|---|---|---|
| approval_design | plan.approve | APPROVED | human |
| implementation | task.high_impact | APPROVED | human |
| implementation | task.high_impact | APPROVED | human |
| implementation | task.high_impact | APPROVED | human |
| implementation | task.high_impact | APPROVED | human |
| implementation | task.high_impact | APPROVED | human |
| implementation | task.high_impact | APPROVED | human |
| implementation | task.high_impact | APPROVED | human |
| unit_tests | task.high_impact | APPROVED | human |
| implementation | task.high_impact | APPROVED | human |

### Brief presented at `approval_design`

<details><summary>Full brief as shown to the approver</summary>

# Approval requested: approval_design (plan.approve)
Run brownfield-859b9d7f · scenario brownfield

#### What is being built
Extend the existing Java URL shortener with privacy-preserving click analytics: every successful GET /{short_code} redirect records a url.clicked event via a transactional outbox (never blocking the redirect), an in-process poller publishes those events to the Kafka topic url.clicked keyed by short_code, an analytics consumer aggregates them into click_stats, and a new additive endpoint GET /api/v1/urls/{short_code}/stats exposes total clicks, last clicked timestamp, and a 30-day daily breakdown with an as_of timestamp. Raw IPs are never persisted (salted SHA-256 only), raw click events are retained 90 days, and publishing/consuming use bounded retries with exponential backoff + jitter, timeouts, and idempotency keys. The existing redirect contract stays backward compatible with no more than a 5% p95 latency increase.

#### Changes to the current contract and structure
- Endpoints added: GET /api/v1/urls/{}, GET /api/v1/urls/{}/stats
- Endpoints restated (present today): GET /{}, POST /api/v1/urls
- Tables added (migration): click_outbox, click_stats, click_stats_daily, processed_click_event
- Packages added: com.example.shortener.analytics.config, com.example.shortener.analytics.api, com.example.shortener.analytics.recording, com.example.shortener.analytics.outbox, com.example.shortener.analytics.kafka, com.example.shortener.analytics.consumer, com.example.shortener.analytics.aggregation, com.example.shortener.analytics.retention, com.example.shortener.analytics.support, com.example.shortener.analytics.domain, com.example.shortener.analytics.repository, com.example.shortener.redirect

#### Design
- endpoint: GET /{short_code} (redirectToTargetUrl) -> 302, 404
- endpoint: POST /api/v1/urls (createShortUrl) -> 201, 400, 401, 409
- endpoint: GET /api/v1/urls/{short_code} (getShortUrl) -> 200, 401, 404
- endpoint: GET /api/v1/urls/{short_code}/stats (getUrlClickStats) -> 200, 401, 404
- table: click_outbox(id, idempotency_key, short_code, occurred_at, hashed_ip, referrer_host, status, attempts, next_attempt_at, published_at, last_error, created_at)
- table: click_stats(short_code, total_clicks, last_clicked_at, updated_at)
- table: click_stats_daily(short_code, day, click_count, updated_at)
- table: processed_click_event(idempotency_key, short_code, processed_at)
- migrations: 3; packages: com.example.shortener.analytics.config, com.example.shortener.analytics.api, com.example.shortener.analytics.recording, com.example.shortener.analytics.outbox, com.example.shortener.analytics.kafka, com.example.shortener.analytics.consumer, com.example.shortener.analytics.aggregation, com.example.shortener.analytics.retention, com.example.shortener.analytics.support, com.example.shortener.analytics.domain, com.example.shortener.analytics.repository, com.example.shortener.redirect
- rule: Layer order is api -> (application services) -> repository -> domain; no class may depend on a layer above it.
- rule: com.example.shortener.analytics.domain must not depend on any other application package (no Spring web, no Kafka, no repository types).
- rule: com.example.shortener.analytics.repository may depend only on ..analytics.domain; it must not depend on api, outbox, consumer, kafka, recording or retention.
- rule: com.example.shortener.analytics.api must not depend on ..analytics.outbox, ..analytics.consumer, ..analytics.kafka or ..analytics.retention: the stats endpoint reads aggregates only.
- rule: com.example.shortener.analytics.recording must not depend on ..analytics.kafka, ..analytics.outbox or any Kafka/producer type; the redirect path performs zero network I/O.
- rule: No package outside com.example.shortener.analytics.kafka and ..analytics.consumer and ..analytics.config may import org.apache.kafka.. or org.springframework.kafka...
- rule: com.example.shortener.redirect may depend on com.example.shortener.analytics.recording only; it must not reach into analytics.repository, analytics.domain or analytics.api.
- rule: Nothing in com.example.shortener.analytics may depend on com.example.shortener.redirect (analytics is a downstream consumer, not an upstream dependency): no cycles between top-level packages.
- rule: Controllers (@RestController) exist only in ..api packages; @Entity classes exist only in ..domain packages; @Repository / Spring Data interfaces only in ..repository packages.
- rule: @Scheduled methods may exist only in ..analytics.outbox and ..analytics.retention and must run on the dedicated analytics scheduler, never on a request thread.
- rule: No class may log, return or store a raw client IP: java.net.InetAddress / remote-address values may be referenced only inside ..analytics.recording.IpHasher and the redirect controller that reads the request.
- rule: Error responses are produced only by the existing global exception handler as application/problem+json; controllers must not build ad-hoc error bodies.

### Decisions (with rejected alternatives)
- ADR-1: Record clicks via a transactional outbox written in the redirect transaction and drained by an in-process poller; rejected publishing to Kafka directly from the request thread, which couples redirect latency and availability to the broker (violates AC-3 and the 5% p95 budget).
- ADR-2: Claim outbox batches with SELECT ... FOR UPDATE SKIP LOCKED bounded by batchSize; rejected a leader-election/single-publisher instance, which adds coordination infrastructure and a single point of failure for a workload that is already row-partitionable.
- ADR-3: Use a random per-redirect UUID as the idempotency key stored on the outbox row and carried in a Kafka header (AMB-17 default); rejected a deterministic hash of (short_code, occurred_at, hashed_ip, referrer_host), which collapses two genuine clicks that share the same millisecond, IP hash and referrer.
- ADR-4: Deduplicate in the consumer by inserting into processed_click_event before any aggregate write (insert-first guard); rejected checking-then-writing or relying on Kafka exactly-once semantics, both of which double-count under concurrent redelivery (AC-6).
- ADR-5: Keep click_stats and click_stats_daily in the same relational database as the shortener, owned by the analytics module (AMB-13 default); rejected a dedicated analytics datastore, which is new protected infrastructure and out of scope for this release.
- ADR-6: Store per-day aggregates in a click_stats_daily table keyed (short_code, day); rejected a JSON/JSONB clicks_by_day column on click_stats, which makes concurrent per-day increments a read-modify-write hot spot and blocks indexed window queries.
- ADR-7: Return clicks_by_day as a sparse ascending list over the last 30 UTC days and 200/0/null/empty for a zero-click code (AMB-14 and AMB-15 defaults); rejected dense zero-filled days (bigger payload, server-side calendar synthesis) and rejected 404 for zero clicks (conflates 'no data' with 'no such link').
- ADR-8: Expose freshness through an explicit as_of field taken from click_stats.updated_at; rejected presenting stats as strongly consistent by reading the outbox at query time, which would put analytics work on the read path and still not be exact.
- ADR-9: Implement one shared RetryExecutor with bounded attempts, exponential backoff, full jitter, per-attempt timeout and an injectable random source (defaults 5 / 100 ms / 10 s, configurable per AMB-12 and AMB-18); rejected Kafka's built-in infinite producer retries and a dead-letter topic, which either retry forever or require infrastructure-owned topic provisioning.
- ADR-10: Terminal failures move the outbox row to FAILED with a truncated last_error plus a metric; rejected deleting failed rows or leaving them PENDING, which respectively loses evidence and creates an unbounded retry loop (AC-5).
- ADR-11: Hash client IPs with SHA-256 over a single static salt supplied by the existing secret mechanism and never commit the salt; rejected storing truncated or encrypted IPs, which remain reversible/re-identifiable personal data (AC-10).
- ADR-12: Purge raw click rows with an in-process scheduled job in bounded batches, excluding PENDING outbox rows (AMB-16 default); rejected a DB partition-drop job, which needs protected DDL/infrastructure ownership now and is deferred to the contract phase.
- ADR-13: Version the url.clicked payload with an explicit schema_version field plus an event-version header and tolerant-reader consumers; rejected an unversioned payload or a schema registry, the first blocking safe evolution and the second adding infrastructure outside this release's scope.
- ADR-14: Ship the stats endpoint as a purely additive operation under the existing /api/v1 path prefix and existing auth scheme, with the committed src/main/resources/openapi.yaml as the single source of truth verified against the springdoc dump from -Pit verify; rejected a new /api/v2 prefix or changing the redirect response shape, which breaks existing consumers (AC-14).
- ADR-15: Errors are emitted as application/problem+json by the existing global exception handler; rejected per-controller ad-hoc error bodies, which drift from the committed contract and fail the contract gate.
- ADR-16: Existing operations (redirectToTargetUrl, createShortUrl, getShortUrl) are documented here as-is and must be reconciled byte-for-byte with what the running application exposes before the contract gate runs; rejected rewriting them to a 'cleaner' shape, which would be a breaking change requiring an api.contract.breaking_change approval.

#### Plan
| task | impact | title | files | needs approval for | outside policy |
|---|---|---|---|---|---|
| T1 | HIGH | Build, dependency and configuration foundation for click analytics | 7 | dependency.major_version, secrets.or_config | - |
| T2 | HIGH | Schema migrations plus persistence model for click_outbox and click_stats | 5 | schema.migration | - |
| T3 | MEDIUM | Record click events transactionally on successful redirect (hashing + referrer host) | 5 | - | - |
| T4 | MEDIUM | Outbox poller and Kafka publisher with bounded retries, backoff and jitter | 6 | - | - |
| T5 | MEDIUM | Idempotent analytics consumer aggregating url.clicked into click_stats | 4 | - | - |
| T6 | MEDIUM | 90-day retention purge for raw click events | 3 | - | - |
| T7 | MEDIUM | Additive GET /api/v1/urls/{short_code}/stats endpoint and OpenAPI document | 4 | - | - |
| T8 | HIGH | Integration tests, latency gate and release artifacts | 6 | infrastructure.change, release.config | - |

#### Security review
- SEC-001 [high] authorization: GET /api/v1/urls/{short_code}/stats is specified only as 'reuses the existing /api/v1 auth scheme'. The design's ClickStatsQueryService performs an existence check but no ownership/tenancy check, so any authenticated caller (or any holder of any valid token) can read the click history of any short code - an IDOR on link-owner data (US-1/US-3 name the 'link owner' as the data subject).
- SEC-002 [high] pii / cryptography: hashed_ip is SHA-256 over a single static salt concatenated with the client IP. The IPv4 space is ~2^32, so once the salt is known (or brute-forced/leaked from config, heap dump, or a backup) every hashed_ip is reversible by exhaustive precomputation; plain concatenation also invites length-extension/ordering mistakes. Salt rotation is an explicit non-goal, so the control must be strong at first write.
- SEC-003 [high] input validation / untrusted deserialization: ClickEventConsumer deserialises JSON from the url.clicked topic. If the JSON deserialiser is configured with default/polymorphic typing or without trusted-package restrictions, a crafted or replayed message from any principal with produce rights becomes a deserialization gadget; without field validation, a hostile or buggy producer can inject over-long short_code/referrer_host, non-hex hashed_ip, or occurred_at values far in the past/future that pollute click_stats_daily buckets and the 30-day window.
- SEC-004 [high] secret handling / transport security: AnalyticsKafkaConfig introduces broker connectivity but the design does not state transport security or credential sourcing for producer and consumer, nor topic ACLs. Plaintext PLAINTEXT bootstrap or credentials committed to application properties would expose click events (hashed IPs, referrer hosts, short codes) on the wire and allow any network peer to produce forged url.clicked events.
- SEC-005 [high] pii / retention: The retention purge intentionally excludes PENDING click_outbox rows. If the broker is unavailable or rows are stuck (attempts not yet exhausted, poller disabled), raw click rows containing hashed_ip and referrer_host can persist indefinitely, breaching the 90-day raw-event retention promise in US-4/AC-12. FAILED rows keep a last_error string that can absorb driver/broker messages containing host or network identifiers.
- SEC-006 [medium] input validation / client identity: The client IP used for hashing on an unauthenticated public endpoint is typically taken from X-Forwarded-For/Forwarded, which is attacker-controlled unless the proxy chain is trusted. An untrusted source lets a caller fabricate or pollute hashed_ip values and, worse, smuggle arbitrary text (including CRLF) into the hashing/logging path.
- SEC-007 [medium] input validation / stored untrusted data: referrer_host is derived from the attacker-controlled Referer header and persisted (VARCHAR(255)) and republished. Without normalisation, values may contain userinfo, punycode/IDN homographs, embedded credentials (user:pass@host), uppercase/trailing-dot variants that fragment aggregates, control characters enabling log injection, or oversize input causing insert failures on the redirect path.
- SEC-008 [medium] availability / rate limiting: Every unauthenticated GET /{short_code} now performs an additional INSERT, so an attacker hammering one or many short codes converts a cheap read into sustained write amplification: outbox growth, WAL/disk pressure, poller backlog, Kafka volume, and connection-pool contention that can breach the 5% p95 budget (AC-13) and degrade the existing redirect SLO. The new stats endpoint is also an unbounded read of a 30-day window.
- SEC-009 [medium] availability / brownfield regression: ClickEventRecorder runs inside the redirect's transaction and 'swallows and logs' its own failures. In Spring/JPA, a failed statement or a swallowed exception in a participating transaction marks it rollback-only, so the swallow does not protect the caller: the redirect can fail with 500 (or a UnexpectedRollbackException) on an analytics-only error, violating AC-3/AC-14 and turning a database hiccup into a user-facing outage on the existing contract.
- SEC-010 [medium] logging hygiene: New components log retries, terminal failures, purge counts, and consumer errors. Attacker-controlled values (short_code, referrer_host, Referer, idempotency key) reaching log statements unescaped permit log injection/forging of log lines, and any accidental logging of the client IP, the salt, the hashed_ip, or broker credentials breaches AC-10 and US-4.
- SEC-011 [medium] sql / data integrity: The design specifies native SQL for the SKIP LOCKED claim, the ON CONFLICT upserts, and the batched retention deletes, with :batchSize/:cutoff/:shortCode placeholders. Any string concatenation of batch size, cutoff, short code, or ordering into these statements is an injection vector reachable from consumer input (short_code) and configuration. Additionally, the hashed_ip format CHECK constraint listed in the table definition is missing from the actual migration body, so a malformed or non-hex value (e.g. a raw IP) could be persisted undetected.
- SEC-012 [medium] dependency policy: The change introduces new runtime dependencies (spring-kafka / kafka-clients, JSON serialisation, metrics) into a brownfield service. Unpinned, transitively upgraded, or vulnerable versions (notably Jackson and Kafka client CVEs) would be a supply-chain regression, and new test infrastructure (embedded Kafka / Testcontainers images) adds untrusted image pulls.
- SEC-013 [low] observability / resource exhaustion: AnalyticsMetrics exposes counters, timers, and an outbox backlog gauge. Tagging any metric with short_code, referrer_host, or idempotency key creates unbounded label cardinality driven by unauthenticated traffic - a memory-exhaustion DoS on the application and the metrics backend - and also publishes per-link usage data on a potentially less-protected endpoint.
- SEC-014 [low] replay / idempotency: The idempotency key is a random per-redirect UUID carried in a Kafka header while the consumer dedupes on the message body's idempotency_key. A mismatch between header and body, or a producer that re-uses/forges a key, lets an actor with produce rights suppress legitimate clicks (by pre-inserting a key) or bypass dedupe (by varying the key), and processed_click_event purge at 90 days re-opens a replay window for older events.
- SEC-015 [low] privacy governance: Salted hashed IPs remain personal data under GDPR-style regimes, click_stats aggregates are retained indefinitely, and salt rotation / historical re-hashing is an explicit non-goal. There is no stated handling for data-subject erasure or for the privacy assessment of the new processing purpose.
- expected high-impact actions: Adding new runtime dependencies (spring-kafka/kafka-clients, JSON serialisation, metrics registry) to the existing build - dependency approval expected.; Creating and applying a forward-only Flyway migration that adds click_outbox, click_stats, click_stats_daily, processed_click_event plus indexes and CHECK constraints to the production database schema (expand-only, no ALTERs on existing tables) - schema change approval expected.; Provisioning a new long-lived secret (the >=32-byte IP hashing salt) in the existing secret store and wiring it as a required, fail-closed startup property - secret creation/handling approval expected.; Requesting infrastructure-owned Kafka assets: url.clicked topic access, a dedicated consumer group, produce/consume ACLs for the service principal, and TLS/SASL credentials - external infrastructure change approval expected.; Modifying the pre-existing RedirectService/RedirectController hot path (the public, unauthenticated redirect) to invoke the recorder inside the existing transaction - brownfield behaviour-change approval expected, gated by the unchanged contract test suite and the 5% p95 load-test gate.; Touching security configuration to bring GET /api/v1/urls/{short_code}/stats under the existing auth scheme and to add the owner/tenant authorization predicate - authorization model change approval expected (and an ambiguity answer if the existing scheme has no ownership concept).; Enabling forwarded-header handling / trusted-proxy configuration so the client IP used for hashing is not attacker-controlled - request-pipeline configuration approval expected.; Enabling Spring scheduling with a new dedicated bounded TaskScheduler/executor and two @Scheduled jobs (outbox poller, retention purge) that run continuously in every application instance, including a bulk DELETE job against production data - background-job and data-deletion approval expected.; Introducing rate limiting/throttling on the public redirect endpoint and the new stats endpoint, which can change observable behaviour for existing clients (429s) - contract-impact approval expected.; Adding the automated load-test gate and the committed openapi.yaml reconciliation to CI, including failing the build on p95 regression or contract drift - pipeline change approval expected.; Recording accepted risks with sign-off: non-rotatable static salt with residual IP re-identification risk, indefinite retention of click_stats aggregates, and eventual-consistency of the stats endpoint.

#### Risks
- R-01 [high/high] Hot-path latency regression on GET /{short_code}: the additional transactional outbox INSERT (plus index maintenance on idx_click_outbox_claim, longer transaction hold, and Hikari connection contention with the poller, consumer and retention job) pushes p95 above the +5% budget (AC-13, US-2). Carries impact risk R-1.
- R-02 [medium/high] Transaction-semantics conflict: AC-1 requires the outbox row in the same transaction as redirect handling, while AC-3/US-2 require the redirect to be unaffected by analytics failures. If the INSERT fails inside a Spring/JPA transaction, catching and logging the exception does not undo the rollback-only marking or the poisoned persistence context, so the redirect can still return 500 instead of 302.
- R-03 [high/high] Bounded retries cause permanent analytics data loss during a broker outage longer than the retry window: with 5 attempts / cap 10s / full jitter, a claimed row exhausts attempts within roughly a minute and is moved to terminal FAILED, contradicting AC-3's promise that events 'remain pending in the outbox for later publication' during an outage.
- R-04 [medium/high] Unbounded outbox growth and hot-path degradation during a prolonged broker or consumer outage: PENDING rows are deliberately excluded from the 90-day purge, so table and claim-index bloat grows without limit, slowing the redirect INSERT and the claim query in the shared database.
- R-05 [medium/high] Raw client IP leakage into the outbox table, Kafka payload/headers, click_stats, application or access logs, MDC, or exception stack traces, breaching AC-10 and US-4. Carries impact risk R-5.
- R-06 [low/high] Re-identification risk from a single static, never-rotated salt: SHA-256 over a 32-bit IPv4 space is exhaustively enumerable if the salt is ever disclosed (log, heap dump, config repo, backup), turning hashed_ip into recoverable personal data despite the pseudonymisation claim.
- R-07 [high/medium] Duplicate or double-counted clicks: at-least-once Kafka delivery plus a crash between a successful publish and marking the outbox row PUBLISHED re-publishes the same event; a check-then-write consumer or a failed dedupe insert would then inflate click_stats (AC-6).
- R-08 [low/low] Late duplicate after retention: processed_click_event rows are purged at 90 days, so a redelivery or manual re-drive of an event older than the cutoff re-increments click_stats, which is never corrected because aggregates are retained forever.
- R-09 [medium/high] Irrecoverable aggregates: the design keeps no durable raw click event table (outbox rows are purged and only the aggregate survives), so any aggregation bug, dropped FAILED event or bad deployment produces click_stats values that can never be recomputed or corrected. The impact analysis also references a click_events table that does not exist in the data model, indicating an unresolved scope gap for AC-12's 'raw click event rows'.
- R-10 [medium/high] SQL portability/dialect failure: the design uses PostgreSQL-specific constructs (FOR UPDATE SKIP LOCKED with LIMIT, ON CONFLICT, GREATEST over timestamptz, regex CHECK with '~', GENERATED BY DEFAULT AS IDENTITY, COMMENT ON COLUMN). If tests or local runs use H2/another dialect, claim and upsert paths behave differently or fail only in production; the migration body also omits the hashed_ip regex CHECK listed in the table constraints.
- R-11 [medium/medium] Consumer stalls or rebalance loop: the shared blocking RetryExecutor runs on the KafkaListener thread, so retry sleeps plus per-attempt timeouts over a batch can exceed max.poll.interval.ms, causing partition revocation, repeated redelivery and no forward progress; a poison message could also block its partition indefinitely.
- R-12 [medium/medium] Multi-instance scheduled job contention: every replica runs the @Scheduled poller and the retention job with no leader election, causing claim conflicts, lock waits or deadlocks on click_outbox and concurrent large DELETEs in the database shared with the redirect hot path. Carries impact risk R-7 and R-9.
- R-13 [medium/high] Backward-compatibility break on existing contracts: wiring the recorder into the read path or plugging the new stats controller into shared security/DTO/error configuration changes status codes, the Location header, the problem+json error shape or auth behaviour for GET /{short_code}, POST /api/v1/urls or GET /api/v1/urls/{short_code} (AC-14, US-6). The committed OpenAPI also documents 302 and a placeholder bearerAuth scheme that may not match the running application (ADR-16).
- R-14 [medium/medium] Unanswered ambiguities materialised as defaults diverge from stakeholder intent: retry bounds (AMB-12), stats store (AMB-13), zero-click response (AMB-14), sparse vs dense clicks_by_day (AMB-15), purge trigger (AMB-16), idempotency key scope (AMB-17) and poller cadence (AMB-18). A different answer invalidates the data model and API contract after implementation.
- R-15 [medium/low] Misleading eventual-consistency reporting: as_of falls back to now() when no click_stats row exists, so a short code whose clicks are still pending in the outbox looks like a freshly updated zero-click aggregate; clients may also treat stats as real-time (AC-15, US-3).
- R-16 [low/medium] Stats endpoint performance and hot-code skew: the 30-day window query over click_stats_daily, or a very popular short_code whose day-bucket row is updated on every click, creates row contention with the consumer and slow reads under load.
- R-17 [medium/medium] New dependency risk from spring-kafka/kafka-clients (and Testcontainers): version conflicts with the existing managed BOM, slower startup, transitive CVEs, or a client/broker incompatibility that only manifests at runtime against the infrastructure-owned cluster. Carries impact risk R-3.
- R-18 [medium/high] Operational dependency on infrastructure: the url.clicked topic is provisioned outside this release (non-goal). If it is missing, misconfigured (wrong partition count, auto-create disabled, insufficient retention) or lacks producer/consumer ACLs, publishing fails everywhere at once and per-short_code ordering guarantees may not hold after a partition-count change.
- R-19 [medium/high] Migration and rollback hazards: adding four tables plus indexes to the live shortener schema can take locks or fail mid-way, and the documented rollback (DROP TABLE of the analytics tables) destroys in-flight PENDING outbox rows and all aggregates. Carries impact risk R-2.
- R-20 [low/medium] Clock and time-zone correctness: occurred_at is taken from application clocks across replicas, so skew can place a click in the wrong UTC day bucket or (with GREATEST) pin last_clicked_at to a future instant; using the JVM default zone anywhere for day bucketing breaks AC-7/AC-11 expectations.
- R-21 [medium/medium] The +5% p95 latency gate itself is fragile: no recorded pre-change baseline, noisy CI runners, or an unrepresentative load profile makes the gate either falsely red (blocking release) or falsely green (shipping a regression). AC-13 presumes a baseline that the brownfield workspace may not have.

Decide with `sdlc approve <run> <node>` or `sdlc reject <run> <node> --reason ...`.

</details>

## 8. Execution timeline

| Time (UTC) | Event | Node | Task | Status | Detail |
|---|---|---|---|---|---|
| 14:18:46 | RUN_STARTED |  |  |  |  |
| 14:18:46 | INPUT_REQUESTED | clarify |  | PENDING | 11 question(s) |
| 14:19:01 | INPUT_RECEIVED | clarify |  |  |  |
| 14:19:01 | RUN_STARTED |  |  |  |  |
| 14:19:01 | REPLAN_TRIGGERED | clarify |  |  |  |
| 14:28:19 | APPROVAL_REQUESTED | approval_design |  | PENDING | plan.approve |
| 15:06:30 | APPROVAL_GRANTED | approval_design |  | APPROVED |  |
| 15:06:30 | RUN_STARTED |  |  |  |  |
| 15:06:30 | APPROVAL_REQUESTED | implementation |  | PENDING | task.high_impact — T1: Build, dependency and configuration foundation for click analytics |
| 15:06:34 | APPROVAL_GRANTED | implementation |  | APPROVED |  |
| 15:06:34 | RUN_STARTED |  |  |  |  |
| 15:06:34 | POLICY_DECISION | implementation | T1 | APPROVED | task.high_impact |
| 15:14:30 | EXECUTOR_CALL |  | T1 | OK | turns 29 |
| 15:14:30 | POLICY_DECISION |  | T1 | OK | approved actions: dependency.major_version, secrets.or_config |
| 15:14:30 | APPROVAL_REQUESTED | implementation |  | PENDING | task.high_impact — T2: Schema migrations plus persistence model for click_outbox and click_stats |
| 15:16:37 | APPROVAL_GRANTED | implementation |  | APPROVED |  |
| 15:16:37 | RUN_STARTED |  |  |  |  |
| 15:16:37 | POLICY_DECISION | implementation | T2 | APPROVED | task.high_impact |
| 15:27:51 | EXECUTOR_CALL |  | T2 | OK | turns 39 |
| 15:27:51 | POLICY_DECISION |  | T2 | VIOLATION | src/main/java/com/example/shortener/analytics/domain/ClickOutboxEntry.java: task.allowed_files; src/main/java/com/example/shortener/analytics/domain/ClickStats… |
| 15:27:51 | ROLLED_BACK | implementation | T2 |  | policy violation at the write boundary |
| 15:28:54 | EXECUTOR_CALL |  | T2 | OK | turns 6 |
| 15:28:54 | POLICY_DECISION | implementation | T2 | SCOPE_REQUESTED | task.scope_change: src/main/java/com/example/shortener/analytics/domain/ClickOutboxEntry.java, src/main/java/com/example/shortener/analytics/domain/OutboxStatu… |
| 15:28:54 | APPROVAL_REQUESTED | implementation |  | PENDING | task.scope_change — T2: None |
| 15:29:49 | APPROVAL_GRANTED | implementation |  | APPROVED |  |
| 15:29:49 | RUN_STARTED |  |  |  |  |
| 15:29:49 | RUN_HALTED |  |  | HALTED | budget.exceeded:wall_clock |
| 16:54:34 | APPROVAL_GRANTED | implementation |  | APPROVED |  |
| 16:54:34 | RUN_RESUMED | implementation |  |  | after budget.exceeded:wall_clock |
| 16:54:34 | RUN_STARTED |  |  |  |  |
| 16:54:34 | POLICY_DECISION | implementation | T2 | SCOPE_APPROVED | task.scope_change: src/main/java/com/example/shortener/analytics/domain/ClickOutboxEntry.java, src/main/java/com/example/shortener/analytics/domain/OutboxStatu… |
| 16:54:35 | EXECUTOR_CALL |  | T2 | REUSED | turns -, T2.attempt1.patch |
| 16:54:35 | POLICY_DECISION |  | T2 | OK | approved actions: schema.migration |
| 17:06:41 | EXECUTOR_CALL |  | T3 | OK | turns 39 |
| 17:16:21 | EXECUTOR_CALL |  | T4 | OK | turns 31 |
| 17:26:14 | EXECUTOR_CALL |  | T5 | OK | turns 25 |
| 17:53:35 | EXECUTOR_CALL |  | T6 | OK | turns 22 |
| 18:00:57 | EXECUTOR_CALL |  | T7 | OK | turns 35 |
| 18:00:57 | POLICY_DECISION | implementation | T7 | SCOPE_REQUESTED | task.scope_change: src/test/java/com/example/shortener/it/OpenApiContractIT.java |
| 18:00:57 | APPROVAL_REQUESTED | implementation |  | PENDING | task.scope_change — T7: None |
| 18:01:23 | APPROVAL_GRANTED | implementation |  | APPROVED |  |
| 18:01:23 | RUN_STARTED |  |  |  |  |
| 18:01:23 | RUN_HALTED |  |  | HALTED | budget.exceeded:wall_clock |
| 18:01:43 | APPROVAL_GRANTED | implementation |  | APPROVED |  |
| 18:01:43 | RUN_RESUMED | implementation |  |  | after budget.exceeded:wall_clock |
| 18:01:43 | RUN_STARTED |  |  |  |  |
| 18:01:43 | POLICY_DECISION | implementation | T7 | SCOPE_APPROVED | task.scope_change: src/test/java/com/example/shortener/it/OpenApiContractIT.java |
| 18:46:53 | NODE_FAILED | implementation |  | BLOCKED | task T7 failed 3 times: task T7 errored: claude code timeout |
| 18:46:53 | RUN_HALTED |  |  | HALTED | implementation.blocked |
| 18:49:36 | APPROVAL_GRANTED | implementation |  | APPROVED |  |
| 18:49:36 | RUN_RESUMED | implementation |  |  | after implementation.blocked |
| 18:49:36 | RUN_STARTED |  |  |  |  |
| 18:51:21 | EXECUTOR_CALL |  | T7 | OK | turns 15 |
| 18:51:22 | POLICY_DECISION | implementation | T8 | APPROVED | task.high_impact |
| 19:00:08 | EXECUTOR_CALL |  | T8 | OK | turns 53 |
| 19:00:08 | POLICY_DECISION |  | T8 | VIOLATION | README.md: compliance.pii |
| 19:00:08 | ROLLED_BACK | implementation | T8 |  | policy violation at the write boundary |
| 19:02:45 | EXECUTOR_CALL |  | T8 | OK | turns 16 |
| 19:02:45 | POLICY_DECISION |  | T8 | OK | approved actions: infrastructure.change, release.config |
| 19:02:45 | GATE_RESULT |  | unit_tests | FAILED | scope: 1 finding(s), 0.221 s |
| 19:08:31 | RUN_RESUMED |  |  |  | after process.restart |
| 19:08:36 | RUN_STARTED |  |  |  |  |
| 19:08:36 | RUN_HALTED |  |  | HALTED | budget.exceeded:wall_clock |
| 19:09:15 | APPROVAL_GRANTED | unit_tests |  | APPROVED |  |
| 19:09:15 | RUN_RESUMED | unit_tests |  |  | after budget.exceeded:wall_clock |
| 19:09:15 | RUN_STARTED |  |  |  |  |
| 19:09:15 | GATE_RESULT |  | unit_tests | FAILED | scope: 2 finding(s), 0.483 s |
| 19:10:26 | GATE_RESULT |  | integration_tests | FAILED | integration: 1 finding(s), 70.745 s |
| 19:10:26 | GATE_RESULT |  | validation | WARNED | acceptance: 6 finding(s), 0.0 s |
| 19:10:26 | FALLBACK_TAKEN | validation |  |  | validation: 3 blocking findings |
| 19:10:58 | RUN_HALTED |  |  | HALTED | diagnose.unrecoverable |
| 19:19:36 | APPROVAL_GRANTED | implementation |  | APPROVED |  |
| 19:19:36 | RUN_RESUMED | implementation |  |  | after diagnose.unrecoverable |
| 19:19:36 | REPLAN_TRIGGERED | implementation |  |  | human.retry |
| 19:19:36 | POLICY_DECISION | implementation |  | APPROVAL_REVOKED | revoked: unit_tests |
| 19:19:36 | RUN_STARTED |  |  |  |  |
| 19:21:39 | EXECUTOR_CALL |  | T7 | OK | turns 12 |
| 19:21:40 | POLICY_DECISION | implementation | T8 | APPROVED | task.high_impact |
| 19:23:43 | EXECUTOR_CALL |  | T8 | OK | turns 8 |
| 19:23:44 | GATE_RESULT |  | unit_tests | FAILED | scope: 2 finding(s), 0.327 s |

## 9. Tasks as executed

| Task | Executor calls | Reused patches | Policy violations | Rollbacks |
|---|---|---|---|---|
| T1 | 1 | 0 | 0 | 0 |
| T2 | 3 | 1 | 1 | 1 |
| T3 | 1 | 0 | 0 | 0 |
| T4 | 1 | 0 | 0 | 0 |
| T5 | 1 | 0 | 0 | 0 |
| T6 | 1 | 0 | 0 | 0 |
| T7 | 3 | 0 | 0 | 0 |
| T8 | 3 | 0 | 1 | 1 |

## 10. Gates

### unit_result (v1, attempt 1)

| Gate | Kind | Status | Seconds | Findings |
|---|---|---|---|---|
| scope | required | FAILED | 0.483 | .github/workflows/ci.yml change_control.protected_path: requires human approval: release.config; Dockerfile change_control.protected_path: requires human appro… |

### integration_result (v1, attempt 1)

| Gate | Kind | Status | Seconds | Findings |
|---|---|---|---|---|
| integration | required | FAILED | 70.745 | gate.integration: exit 137 |

### validation_result (v1, attempt 1)

| Gate | Kind | Status | Seconds | Findings |
|---|---|---|---|---|
| scope | required | FAILED | 0.483 | .github/workflows/ci.yml change_control.protected_path: requires human approval: release.config; Dockerfile change_control.protected_path: requires human appro… |
| integration | required | FAILED | 70.745 | gate.integration: exit 137 |
| acceptance | advisory | WARNED | 0.0 | acceptance.AC-1: ClickEventRecorder + V2__click_analytics.sql create the row with idempotency_key/short_code/occurred_at/hashed_ip/referrer_host and RedirectCl… |

### Code review (v1): recommendation REVISE

| Criterion | Verdict | Evidence |
|---|---|---|
| AC-1 | FAIL | ClickEventRecorder + V2__click_analytics.sql create the row with idempotency_key/short_code/occurred_at/hashed_ip/referrer_host and RedirectClickRecordingIT/Re… |
| AC-2 | PASS | src/test/java/com/example/shortener/redirect/RedirectClickRecordingTest.java (unknown/expired cases) plus T3 note "404/410 never reach the recorder"; RedirectC… |
| AC-3 | FAIL | No-Kafka-on-request-path is evidenced by T3 note and RedirectClickRecordingTest, but the planned proof (T8 class <base>.analytics.it.KafkaUnavailableRedirectIT… |
| AC-4 | PASS | src/main/java/com/example/shortener/analytics/outbox/OutboxPoller.java + OutboxPublishService.java with ClickOutboxRepository.claimBatch (FOR UPDATE SKIP LOCKE… |
| AC-5 | PASS | src/main/java/com/example/shortener/analytics/support/RetryExecutor.java (bounded attempts, exponential backoff, none/equal/full jitter, per-attempt timeout) w… |
| AC-6 | PASS | ProcessedClickEvent + ClickStatsAggregator dedupe; src/test/java/com/example/shortener/analytics/aggregation/ClickStatsAggregatorIT.java and ClickStatsAggregat… |
| AC-7 | PASS | click_stats / click_stats_daily in V2__click_analytics.sql; ClickStatsAggregator UTC day bucketing verified in ClickStatsAggregatorTest (midnight-boundary/out-… |
| AC-8 | FAIL | T7 was not delivered: no commit for T7 and no src/main/java/com/example/shortener/analytics/api/** (ClickStatsController/ClickStatsResponse/ClickStatsQueryServ… |
| AC-9 | FAIL | No stats endpoint exists (no analytics/api package, no MockMvc test for 404) in the changeset. |
| AC-10 | PASS | src/main/java/com/example/shortener/analytics/recording/IpHasher.java (salt+SHA-256 hex) with IpHasherTest; migration stores only hashed_ip; T3/T4/T5/T6 notes … |
| AC-11 | PASS | src/main/java/com/example/shortener/analytics/recording/ReferrerHostExtractor.java with ReferrerHostExtractorTest (host-only, null for absent/unparsable). |
| AC-12 | PASS | src/main/java/com/example/shortener/analytics/retention/ClickRetentionJob.java; ClickRetentionJobTest (8/8) and ClickRetentionJobIT (3/3, Testcontainers PG) as… |
| AC-13 | FAIL | Planned RedirectLatencyBudgetIT (T8) and the CI gate (.github/workflows/ci.yml) are absent; no baseline or p95 comparison artifact in the changeset. |
| AC-14 | PASS | T3 note: full unit suite of 304 tests passes including unmodified RedirectControllerTest and ArchitectureTest, and `-Pit verify` with OpenApiContractIT passed;… |
| AC-15 | FAIL | as_of semantics live in ClickStatsQueryService/ClickStatsResponse (T7), which are not present in files_changed; no test covers published-but-unconsumed eventua… |

Concerns:

- Plan not completed: T7 (stats endpoint + additive openapi.yaml) and T8 (end-to-end IT, KafkaUnavailableRedirectIT, latency gate, Dockerfile/CI/README) have no commits. AC-8, AC-9, AC-13, AC-15 are unimplemented and AC-3/AC-6 lack their cross-cutting integration proof, so the release checklist (contract dump match, CI gates, docs) is unmet.
- AC-1 transactional boundary deviation: the outbox insert runs in ClickEventRecorder's own @Transactional write transaction rather than the transaction handling the redirect (T3 note). This is a defensible design for a read-only redirect path but it contradicts the literal AC text and is not covered by a test asserting atomicity; it should be escalated as an ambiguity/spec amendment rather than silently accepted.
- Retention scope risk (plan T6 risk_note): notes say click_outbox is purged by occurred_at older than retention.days with no mention of excluding rows still in PENDING/FAILED (unpublished) state. If a row is stuck unpublished for >90 days it would be deleted before publication, causing silent event loss. Needs an explicit status predicate and a test.
- Consumer failure handling: T5 note says after retry exhaustion the record is acked and marked EXHAUSTED with no DLQ. That is bounded (satisfies AC-5) but means permanent, silent aggregate loss; ensure the metered/logged terminal state is alertable and documented, and that offsets are only committed after the dedupe+upsert transaction commits (no test named for offset/commit ordering).
- Observability: T4 note states micrometer-core is absent and metrics are hand-rolled counters (OutboxMetrics/ConsumerMetrics). "metered" in AC-5 is therefore not exported to any monitoring backend; confirm this is acceptable or add the dependency in a build-owning task.
- Secret hygiene: application.yml carries a SHORTENER_ANALYTICS_IP_SALT placeholder with a "non-secret local fallback" default. A default salt value silently weakens AC-10 if the env var is missing in production; prefer fail-fast validation (no default) in AnalyticsProperties.
- PII test gap: there is no negative test scanning outbox rows, Kafka payloads or log output for raw IP substrings (AC-10 explicitly covers logs). Current evidence is implementation notes only.
- Layering/structure workaround: AnalyticsProperties is registered via a nested @Configuration because the application class was outside allowed_files (T1 note). Functional but a pattern deviation worth normalizing later; similarly OutboxSchedulingConfig introduces @EnableScheduling from a feature package.
- Hygiene: T6 note reports an unresolved spotless failure in the T5 file ClickStatsAggregatorTest.java (left untouched because of allowed_files), and the T4 note says the tree was left uncommitted although a T4 commit hash exists — reconcile these before merge.
- Plan/spec deviations to confirm with the human: AMB mapping in docs/analytics.md was "inferred" per T1 note, and the `it` Maven profile plus springdoc dump were reportedly unchanged/not fully exercised locally in T1, which the T7/T8 contract gate depends on.

## 11. Metrics (derived from the trace)

| Metric | Value |
|---|---|
| run_id | brownfield-859b9d7f |
| nodes | 12 |
| task_success_rate | 0.917 |
| retry_count | 2 |
| rollback_count | 2 |
| mttr_seconds | 15433.035 |
| e2e_latency_seconds | 17532.433 |
| tokens | 513480 |
| human_checkpoints | 5 |
| replans | 0 |
| halted | True |
| halt_reason | diagnose.unrecoverable |

## 12. Artifact lineage

| Artifact | Latest version | Produced by |
|---|---|---|
| approval_brief | 1 | intake |
| changeset | 4 | implementation |
| design | 1 | architecture |
| impact | 1 | impact |
| integration_result | 1 | integration_tests |
| plan | 1 | planning |
| repo_map | 1 | intake |
| requirement_text | 1 | intake |
| review | 1 | code_review |
| risk_register | 1 | risk_analysis |
| security_findings | 1 | security_review |
| spec | 2 | clarify |
| unit_result | 1 | unit_tests |
| validation_result | 1 | validation |

## 13. Assumptions and limitations

- Assumption: hashed_ip is computed with SHA-256 over a single static salt read from the existing secret/config mechanism; the salt is never rotated (AMB-1).
- Assumption: Outbox draining is performed by an in-process scheduled poller using SELECT ... FOR UPDATE SKIP LOCKED batching, safe for multiple application instances (AMB-2).
- Assumption: clicks_by_day covers the last 30 days and is returned as an ordered list of {date, count} objects (AMB-3).
- Assumption: All timestamps and day bucketing use UTC (AMB-4).
- Assumption: The 90-day retention applies to raw click events only; click_stats aggregates are retained indefinitely (AMB-5).
- Assumption: The latency budget is a maximum 5% p95 increase on GET /{short_code}, verified by an automated load-test gate in CI (AMB-6).
- Assumption: The new stats endpoint reuses whatever authentication/authorization scheme the existing /api/v1 endpoints already enforce (AMB-7).
- Assumption: referrer_host holds the host component only (no path or query) and is null when the Referer header is absent or unparsable (AMB-8).
- Assumption: Stats are eventually consistent and the response includes an as_of timestamp (AMB-9).
- Assumption: The Kafka topic url.clicked is provisioned by infrastructure; messages are keyed by short_code to preserve per-code ordering (AMB-10).
- Assumption: Only successful redirects generate click events; no bot filtering is performed (AMB-11).
- Assumption: The work is brownfield: it extends the existing Java URL shortener in the workspace and reuses its current persistence, configuration and build tooling.
- The run did not reach COMPLETED (status RUNNING, halt reason -); anything delivered from it was verified outside the run's gates, see the delivery section.
- The last recorded code review recommended REVISE; its concerns are listed under Gates.

## 14. Delivery and verification

The run's own gates never went green: the implementation node committed all eight tasks, but the
post-implementation `scope` gate kept failing on the Dockerfile and CI workflow because the approval tokens
T8 had earned were lost across two API restarts (context.json lagged the trace, then a replacement process
loaded the run while the old one was still draining). The integration gate's only recorded failure (exit 137)
was the operator killing its Maven build by mistake. With the user's instruction to skip the gates for this
run, the sandbox was delivered and verified directly on 2026-09-14:

| Check | Where | Result |
|---|---|---|
| `./mvnw test` | workspace/url-shortener | 384 tests, 0 failures |
| `./mvnw -Pit verify` (Testcontainers Postgres 17 + Kafka) | workspace/url-shortener | 58 tests, 0 failures on an idle machine; one load-induced Awaitility timeout in `ClickAnalyticsEndToEndIT.unknownShortCodeRecordsNothing` while the Docker image was building concurrently, not reproducible alone, paired, or in a second full run |
| Compose stack (`make shortener-up`: app :8081, Postgres :5433, Redis :6380, Kafka :9094) | docker-compose.yml | fresh containers, Flyway applied `V2__click_analytics` |
| Smoke: create link, 3 redirects, poll stats | scripts against :8081 | 3 `click_outbox` rows PUBLISHED, 3 `url.clicked` messages (hashed IP, referrer host), `click_stats` 3, `click_stats_daily` 3, `GET /api/v1/urls/{code}/stats` reports them within ~3 s |

Delivered by copying the run sandbox (without `.git` and `target/`) into `workspace/url-shortener`; Compose
gained Kafka in the `shortener` profile plus `SHORTENER_KAFKA_BOOTSTRAP_SERVERS` and a local-only
`SHORTENER_ANALYTICS_IP_SALT`. Orchestrator defects found by this run and fixed the same day: `**/` glob
matching, wall clock counting human waits, executor timeout leaving Maven/JVM orphans, prompt without a time
budget, PII scan flagging prose, scope gate ignoring human scope grants, context persistence only at call
end, no resume after a process restart (T12).
