# url-shortener

A small, production-shaped URL shortener: Spring Boot 4 on Java 25, PostgreSQL for the
mappings, Redis for the short-code counter and the redirect cache.

* `POST /api/v1/urls` turns an absolute `http`/`https` URL into a short link.
* `GET /{short_code}` redirects to the stored long URL.

The API contract is the committed OpenAPI document at
[`src/main/resources/openapi.yaml`](src/main/resources/openapi.yaml). It is compared
operation-by-operation and status-code-by-status-code against the running application by
`OpenApiContractIT`, so the file and the code cannot drift. A running instance also serves the
live document at `/v3/api-docs` (JSON), `/v3/api-docs.yaml` and Swagger UI at
`/swagger-ui.html`.

## Endpoints

### `POST /api/v1/urls` — `createShortUrl`

Request body (`application/json`):

| Field             | Required | Rules                                                                 |
|-------------------|----------|-----------------------------------------------------------------------|
| `long_url`        | yes      | absolute `http://` or `https://` URL, not blank, at most 2048 characters |
| `custom_alias`    | no       | `[0-9a-zA-Z]`, 3 to 32 characters; used verbatim as the short code    |
| `expiration_date` | no       | RFC 3339 date-time in the future                                      |

Identical long URLs are never deduplicated: every call creates a new mapping.

| Status | Meaning                                                                                        |
|--------|------------------------------------------------------------------------------------------------|
| `201`  | Created. Body: `short_url`, `short_code`, `long_url`, `expires_at` (nullable), `code_source`.   |
| `400`  | Invalid `long_url`, `custom_alias` or `expiration_date`, or unreadable JSON. Nothing persisted. |
| `409`  | `custom_alias` (or a generated code) already taken. The existing mapping is unchanged.          |
| `500`  | Unhandled server error.                                                                        |

```bash
curl -s -X POST http://localhost:8080/api/v1/urls \
  -H 'Content-Type: application/json' \
  -d '{"long_url":"https://example.com/docs?page=1","custom_alias":"docs","expiration_date":"2030-01-01T00:00:00Z"}'
```

```json
{
  "short_url": "http://localhost:8080/docs",
  "short_code": "docs",
  "long_url": "https://example.com/docs?page=1",
  "expires_at": "2030-01-01T00:00:00Z",
  "code_source": "redis"
}
```

### `GET /{short_code}` — `redirectToLongUrl`

| Status | Meaning                                                                          |
|--------|----------------------------------------------------------------------------------|
| `302`  | `Location` is the stored `long_url`; `Cache-Control: private` on every redirect. |
| `404`  | Short code unknown in both cache and database.                                   |
| `410`  | Short code expired (`expires_at` in the past).                                   |
| `500`  | Unhandled server error.                                                          |

```bash
curl -si http://localhost:8080/docs | head -n 5
```

### Error format

Every non-2xx response, including unhandled `500`s, is rendered by one global exception
handler as RFC 9457 `application/problem+json` with `type`, `title`, `status`, `detail` and
`instance` (the request path). Internal details such as exception class names or stack
traces never appear in a body.

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "Short code 'nope' is unknown",
  "instance": "/nope"
}
```

## `code_source` semantics

`code_source` says which counter produced a generated short code and is stored with the
mapping (column `urls.code_source`):

| Value         | Origin                                                                                  |
|---------------|-----------------------------------------------------------------------------------------|
| `redis`       | Normal path. The instance reserved a batch of values with one Redis `INCRBY` and handed one out. |
| `db_sequence` | Redis was unreachable when the code was allocated; the value came from the PostgreSQL sequence `url_code_seq`. |

Both counters are base62-encoded after adding a configurable seed offset, so generated codes are
always at least six characters and never restart from `a`. A `custom_alias` is stored as
supplied; its `code_source` records the counter that would otherwise have been used and carries
no further meaning. Clients can treat `db_sequence` as a signal that the write side ran
degraded at that moment. See [`docs/operations.md`](docs/operations.md) for the outage
behaviour and the accepted counter gaps.

## Deployment model: Spring profiles

The two HTTP surfaces are independently registrable, so the read path can be scaled without
the write path (and vice versa). The surface is chosen with `SPRING_PROFILES_ACTIVE`:

| Profile        | Registers                                        | Not served                          |
|----------------|--------------------------------------------------|-------------------------------------|
| _(none)_ / `default` | both surfaces                               | —                                   |
| `read`         | `GET /{short_code}` only                         | `POST /api/v1/urls` answers `404`   |
| `write`        | `POST /api/v1/urls` only                         | `GET /{short_code}` answers `404`   |

`read` instances never reserve counter batches from Redis. A typical production layout is many
`read` replicas behind the public hostname and a few `write` replicas behind the API path.
Both talk to the same PostgreSQL database and the same Redis. `ProfileSeparationIT` verifies
the three combinations.

## Configuration

All settings are ordinary Spring properties and can be set as environment variables. Only
local-development defaults live in `application.yml`; real credentials come from the
environment (or your secret manager) and are never part of the image or the repository.

| Property                        | Environment variable         | Default                   | Purpose                                                           |
|---------------------------------|------------------------------|---------------------------|-------------------------------------------------------------------|
| `shortener.base-url`            | `SHORTENER_BASE_URL`         | `http://localhost:8080`   | Public base URL used to build `short_url` (trailing slash ignored). |
| `shortener.counter-batch-size`  | `SHORTENER_COUNTER_BATCH_SIZE` | `1000`                  | Counter values reserved per Redis `INCRBY`; unused remainder is lost on restart. |
| `shortener.cache-ttl`           | `SHORTENER_CACHE_TTL`        | `24h`                     | TTL of cached lookups for never-expiring links, and the upper bound for all others. |
| `shortener.counter-seed-offset` | `SHORTENER_COUNTER_SEED_OFFSET` | `916132832` (62^5)     | Added to the raw counter before base62 encoding; keeps generated codes at six or more characters. |
| `spring.datasource.url`         | `SHORTENER_DB_URL`           | local PostgreSQL          | JDBC URL of the `shortener` database.                             |
| `spring.datasource.username`    | `SHORTENER_DB_USERNAME`      | local dev value           | Database user.                                                    |
| `spring.datasource.password`    | `SHORTENER_DB_PASSWORD`      | local dev value           | Database credential; supply from the environment.                 |
| `spring.data.redis.host`        | `SHORTENER_REDIS_HOST`       | `localhost`               | Redis host.                                                       |
| `spring.data.redis.port`        | `SHORTENER_REDIS_PORT`       | `6379`                    | Redis port.                                                       |
| `server.port`                   | `SERVER_PORT`                | `8080`                    | HTTP port.                                                        |
| `spring.profiles.active`        | `SPRING_PROFILES_ACTIVE`     | _(none)_                  | Deployment surface, see above.                                    |

Flyway runs the migrations in `src/main/resources/db/migration` on start-up; the schema is
`validate`d against the JPA model, never generated.

## Running locally

Prerequisites: JDK 25, Docker (for the databases and the integration tests). Maven is
provided by the wrapper.

1. Start PostgreSQL and Redis, for example with plain containers:

   ```bash
   # local development only: trust authentication, no credential to manage
   docker run -d --name shortener-db -p 5432:5432 \
     -e POSTGRES_DB=shortener -e POSTGRES_USER=shortener -e POSTGRES_HOST_AUTH_METHOD=trust \
     postgres:17-alpine
   docker run -d --name shortener-redis -p 6379:6379 redis:7-alpine
   ```

   The defaults in `application.yml` match this setup. Any other PostgreSQL 17 and Redis 7
   work too: export the `SHORTENER_DB_*` and `SHORTENER_REDIS_*` variables from your shell
   or secret manager before starting the application.

2. Run the application (both surfaces):

   ```bash
   ./mvnw spring-boot:run
   # read-only or write-only surface:
   SPRING_PROFILES_ACTIVE=read  ./mvnw spring-boot:run
   SPRING_PROFILES_ACTIVE=write ./mvnw spring-boot:run
   ```

3. Build and test:

   | Command                                        | What it does                                                   |
   |------------------------------------------------|----------------------------------------------------------------|
   | `./mvnw -q -DskipTests compile`                | compile                                                        |
   | `./mvnw -q spotless:check`                     | google-java-format check (`spotless:apply` fixes)              |
   | `./mvnw -q -Dtest=ArchitectureTest test`       | layering rules (`src/test/java/sdlc/ArchitectureTest.java`)    |
   | `./mvnw -q test`                               | unit tests (`*Test`), no Docker needed                         |
   | `./mvnw -q -Pit verify`                        | integration tests (`*IT`) against Testcontainers PostgreSQL and Redis, and the springdoc dump to `target/openapi.yaml` / `target/openapi.json` |

   The same commands, in the same order, form the CI pipeline in
   [`.github/workflows/ci.yml`](.github/workflows/ci.yml).

## Running with Docker

The [`Dockerfile`](Dockerfile) is a two-stage build: a JDK 25 stage runs
`./mvnw -DskipTests package`, a slim JRE 25 stage runs the fat jar as the unprivileged
`shortener` user on port 8080. The image contains no configuration secrets.

```bash
docker build -t url-shortener .

# combined surface, talking to the containers from "Running locally"
docker run --rm -p 8080:8080 \
  -e SHORTENER_DB_URL=jdbc:postgresql://host.docker.internal:5432/shortener \
  -e SHORTENER_DB_USERNAME=shortener \
  -e SHORTENER_DB_PASSWORD \
  -e SHORTENER_REDIS_HOST=host.docker.internal \
  -e SHORTENER_BASE_URL=http://localhost:8080 \
  url-shortener

# dedicated surfaces
docker run --rm -p 8081:8080 -e SPRING_PROFILES_ACTIVE=read  ... url-shortener
docker run --rm -p 8082:8080 -e SPRING_PROFILES_ACTIVE=write ... url-shortener
```

`-e SHORTENER_DB_PASSWORD` without a value forwards the variable from the calling shell, so the
credential never appears on the command line or in the image. `JAVA_OPTS` (default
`-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError`) can be overridden the same way.

## Project layout

```
src/main/java/com/example/shortener/
  api/dto      request / response records (JSON names of the contract)
  api/error    domain exceptions + GlobalExceptionHandler (problem+json)
  codegen      Base62Codec, RedisBatchCounterSource, DbSequenceCounterSource, ShortCodeAllocator
  config       ShortenerProperties (shortener.* namespace)
  domain       UrlMapping entity, UrlMappingRepository, CodeSource
  read         RedirectController, UrlReadService, RedisUrlCache      (@Profile("!write"))
  write        UrlWriteController, UrlWriteService                    (@Profile("!read"))
src/main/resources/
  openapi.yaml               committed API contract
  db/migration               Flyway migrations
  application*.yml           shared / read / write configuration
src/test/java/.../it         Testcontainers integration tests (*IT)
src/test/java/sdlc           orchestrator-provisioned ArchitectureTest
docs/operations.md           runbook: Redis outage behaviour, counter gaps
```

Layering is enforced by `ArchitectureTest`: `domain` and `config` are leaves, `read` and
`write` never depend on each other, only the two Redis boundary classes touch Spring Data
Redis, and no controller builds an error body.
