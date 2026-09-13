.PHONY: install test lint run demo approve metrics
install: ; pip install -e ".[dev]"
test:    ; pytest -q
lint:    ; ruff check src tests && mypy src
run:     ; uvicorn orchestrator.main:app --reload --port 8080
demo:    ; sdlc run --scenario greenfield --replay
approve: ; sdlc approve $(RUN) $(NODE)
metrics: ; sdlc metrics $(RUN)
