Add click analytics to the existing URL shortener in the workspace.
- Every successful redirect publishes url.clicked {short_code, occurred_at, hashed_ip, referrer_host} to Kafka topic url.clicked
  through an outbox table (redirect must never block on Kafka).
- An analytics consumer aggregates into click_stats {short_code, total_clicks, last_clicked_at, clicks_by_day}.
- GET /api/v1/urls/{short_code}/stats returns the aggregate; unknown -> 404.
- Raw IP must never be persisted (salted SHA-256 only). Retain click events for 90 days.
- Integrate bounded retries using exponential backoff with jitter, timeouts, and idempotency keys.
- No regression to GET /{short_code} latency; existing contract must remain backward compatible (additive only).
