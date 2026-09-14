.PHONY: install test lint run demo approve metrics shortener-up shortener-down shortener-psql shortener-redis shortener-logs
install: ; pip install -e ".[dev]"
test:    ; pytest -q
lint:    ; ruff check src tests && mypy src
run:     ; uvicorn orchestrator.main:app --reload --port 8080
demo:    ; sdlc run --scenario greenfield --replay
approve: ; sdlc approve $(RUN) $(NODE)
metrics: ; sdlc metrics $(RUN)
# --- the delivered url-shortener with its own Postgres (localhost:5433) and Redis (localhost:6380) ---
shortener-up:    ; docker compose --profile shortener up -d --build
shortener-down:  ; docker compose --profile shortener down
shortener-logs:  ; docker compose --profile shortener logs -f shortener-app
shortener-psql:  ; docker compose --profile shortener exec shortener-postgres psql -U shortener -d shortener
shortener-redis: ; docker compose --profile shortener exec shortener-redis redis-cli
