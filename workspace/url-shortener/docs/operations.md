# Operations runbook

Operational behaviour of `url-shortener` that is not obvious from the API contract. The
service has two external dependencies: PostgreSQL (system of record for every mapping, always
required) and Redis (short-code counter and redirect cache, degradable). This document covers
what happens when Redis is unavailable and the properties of the short-code counter that
operators should know about.

## 1. Redis outage: degradation, not failure

Redis is treated as an accelerator on both surfaces. Every Redis failure (connection refused,
timeout after `spring.data.redis.timeout` = 2s, authentication or protocol error) is handled
inside the one class that owns Redis on that surface. Controllers and services never catch
infrastructure exceptions, and clients never see a Redis-related 5xx.

### 1.1 Read surface (`GET /{short_code}`)

Owner: `RedisUrlCache`.

| Situation                              | Behaviour                                                                                  |
|----------------------------------------|--------------------------------------------------------------------------------------------|
| `GET shortener:url:{code}` fails       | Treated as a cache miss; the mapping is loaded from PostgreSQL and the redirect is served.  |
| `SET shortener:url:{code}` fails       | Treated as a no-op; the redirect is still served from the database result.                 |
| Mapping unknown / expired              | Unchanged: `404` / `410` from the database lookup, exactly as with a healthy cache.         |

Client-visible effect: redirects keep working with `302`, `Location` and `Cache-Control:
private` (AC-13). Latency rises by the database round trip plus, while Redis is unreachable,
up to the 2s Redis timeout per request. Database load rises to the full redirect rate because
no lookup is absorbed by the cache. Size the PostgreSQL connection pool for that case.

Recovery is automatic: the next request after Redis is back writes through again. Entries are
written with TTL `min(expires_at - now, shortener.cache-ttl)` (default 24h), so nothing stale
survives an expiry, and an expired mapping is never cached.

### 1.2 Write surface (`POST /api/v1/urls`)

Owners: `RedisBatchCounterSource` (throws) and `ShortCodeAllocator` (decides the fallback).

| Situation                                                    | Behaviour                                                                                  |
|--------------------------------------------------------------|--------------------------------------------------------------------------------------------|
| `INCRBY shortener:counter` fails while reserving a new batch | The allocation falls back to `nextval('url_code_seq')` in PostgreSQL. Response `201` with `code_source: "db_sequence"` (AC-11). |
| Local batch still has unused values                          | No Redis call is needed; codes keep coming from the batch with `code_source: "redis"`.     |
| Redis counter moved backwards (flush/restore of an old dump)  | The batch is rejected as unsafe (values would collide with persisted codes) and the request falls back to the sequence; see 2.3. |
| PostgreSQL unavailable                                       | Not degradable. The request fails with `500` problem+json; nothing is persisted.           |

The fallback decision is per allocation: every request first tries Redis again, so the service
returns to `code_source: "redis"` on its own as soon as Redis answers. There is no circuit
breaker, which means that during a long outage every write pays the Redis timeout (up to 2s)
before falling back. If that is unacceptable for an extended incident, point
`SHORTENER_REDIS_HOST` at a healthy replica or restart the write instances with a shorter
`spring.data.redis.timeout`.

### 1.3 What to watch

* `WARN ... Redis cache degraded: GET|SET shortener:url:... failed with <ExceptionClass>; serving redirect from database`
  (read surface, one line per failed command).
* `WARN ... Primary short-code counter (redis) unavailable: <ExceptionClass>; falling back to db_sequence`
  (write surface, one line per failed allocation).
* The share of new rows with `code_source = 'db_sequence'`:
  `SELECT code_source, count(*) FROM urls WHERE created_at > now() - interval '15 minutes' GROUP BY 1;`

Both warnings deliberately log only the exception class. Driver messages can embed the Redis
URI and therefore credentials; the full exception is available at `DEBUG` on the owning class
if needed.

### 1.4 Custom aliases

`custom_alias` requests do not draw from any counter. They are unaffected by a Redis outage and
still answer `409` when the alias is taken, because uniqueness is enforced by the primary key
in PostgreSQL, not by the cache.

## 2. Short-code counter: accepted gaps (AMB-10)

Generated codes are base62 encodings of `counter_value + shortener.counter-seed-offset`
(default 62^5, so codes have at least six characters). The counter itself is designed for
uniqueness and throughput, not for density. **Gaps in the code sequence are accepted by
design** (ambiguity AMB-10, resolved with its default: "gaps are acceptable, standard
approach"). Nothing reclaims unused values, and nothing should: every code that was ever handed
out may be persisted, so reusing a value could collide with a stored mapping.

### 2.1 Where gaps come from

| Cause                                   | Size of the gap                                                | Frequency                              |
|-----------------------------------------|----------------------------------------------------------------|----------------------------------------|
| Write instance restart or crash         | Up to `shortener.counter-batch-size` - 1 values (default 999), the unused remainder of its current batch. | Every restart / deploy of a write instance. |
| Scale-out                               | Each new instance reserves its own batch, so `n` instances hold up to `n * batch-size` values in flight. | Continuous while running.              |
| Request fails after allocation          | Exactly one value (for example a `409` race on a generated code, or a rolled-back insert). | Rare.                                  |
| Fallback to `url_code_seq`              | The Redis batch is untouched; the sequence value is consumed even if the insert later fails (`CACHE 1`, so at most one lost per restart). | Only during Redis outages.             |

With the default batch size, a deploy of ten write replicas can skip up to about ten thousand
values. At 62^6 possible six-character codes this is irrelevant for capacity; the only
observable effect is that consecutive short codes are not consecutive numbers, which is also
desirable (codes are harder to enumerate).

### 2.2 Tuning

* `shortener.counter-batch-size` trades Redis round trips against gap size. `1000` means one
  `INCRBY` per thousand codes per instance. Lower it (for example `100`) if you restart write
  instances very often and want smaller gaps; raise it if Redis latency dominates write
  latency. The integration tests run with `1` so that every allocation is observable.
* The Redis counter key is `shortener:counter`. It must live in a persistent Redis (AOF or
  RDB) that is **not** subject to eviction; configure `maxmemory-policy noeviction` or keep the
  key in a dedicated logical database.

### 2.3 Never reset the counter

* Do not `DEL`, `SET` or restore `shortener:counter` from an old dump. A value lower than one
  that was already handed out makes the allocator refuse the batch (`IllegalStateException`
  "moved backwards"), which downgrades every write to the sequence fallback until the key is
  ahead of the highest issued value again. Only ever move the key **forward**, for example
  `SET shortener:counter <value higher than any issued>` after a data loss.
* The two counters are independent ranges that are both offset by the same seed: Redis starts
  at 1, the sequence at 62^3 = 238,328. A value handed out by the sequence during an outage can
  therefore be handed out again by Redis once the Redis counter has passed 238,328. The primary
  key on `urls.short_code` turns such a collision into a `409` for the later request, and the
  client simply retries. If fallback usage is ever heavy, move the Redis counter forward past
  the sequence's `last_value` (`SELECT last_value FROM url_code_seq;`) so the ranges no longer
  meet.

## 3. Deployment surfaces

`SPRING_PROFILES_ACTIVE=read` instances register only the redirect controller and never touch
`shortener:counter`; `write` instances register only the creation controller; the default
profile serves both. Unregistered routes answer `404` problem+json (AC-14). Because the two
surfaces share PostgreSQL and Redis, a write is visible to every read instance immediately
(cache is read-through, populated on first lookup), and there is no replication lag to
consider.

## 4. Checklist for a Redis incident

1. Confirm the symptom: `WARN ... Redis cache degraded` / `... falling back to db_sequence`
   in the logs, `code_source = 'db_sequence'` on new rows.
2. Check PostgreSQL headroom: connections and CPU carry the full read load during the outage.
3. Restore Redis **with its persisted data**, or, if the data is lost, set
   `shortener:counter` to a value above the highest value ever issued before letting write
   instances reconnect (see 2.3). The URL cache needs no repair; it refills on demand.
4. Watch the `code_source` mix return to `redis`. No restart of the application is required.
