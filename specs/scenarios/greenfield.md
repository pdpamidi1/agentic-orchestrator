Build a URL shortener service (Java 25 / Spring Boot 4 by default; Python 3 / FastAPI if SDLC_TARGET_STACK=python).
- POST /api/v1/urls accepts long_url (required), custom_alias (optional), expiration_date (optional ISO-8601) and returns
  {short_url} with 201; invalid URL -> 400 (problem+json); alias taken /  resource already exists -> 409.
- GET /{short_code} -> 302 to the long URL with Cache-Control: private; unknown -> 404; expired -> 410.
- Ensure uniqueness for the short codes by making Short codes as base62 of a globally unique counter in Redis (batched INCRBY 1000); fall back to a DB sequence if
  Redis is unavailable and record code_source.
- Postgres `urls` is the source of truth: short_code PK, long_url, created_at, expires_at, created_by, code_source. Flyway/Alembic.
- Read path checks a Redis cache (short_code -> long_url, TTL <= expiry) before the DB.
- Write path and read path deployable as separate instances that can be scaled independently via profiles/config.
- Global exception handler, OpenAPI document committed, unit + integration tests with Testcontainers, Dockerfile, README.
Non-goals: authentication, analytics (next scenario).
