# agentic-orchestrator — governed agentic SDLC engine (Python / FastAPI)

> Agents propose; gates verify; humans approve.

Turns a requirement into a reviewable engineering outcome by executing an explicit, YAML-defined DAG of SDLC stages
(requirements → clarify → planning → architecture → security ∥ risk → approval → implementation → tests ∥ review →
validation → diagnose/retry/re-plan → documentation → release readiness → approval) with policy guardrails, bounded
retries, rollback, safe-stop, audit trace, derived metrics and persisted state.

- Architecture and decisions: [`docs/architecture.md`](docs/architecture.md)
- The graph: [`workflow.yaml`](workflow.yaml) · Governance: [`policy.yaml`](policy.yaml)
- Team practice for AI-assisted work: [`CLAUDE.md`](CLAUDE.md), [`.claude/commands/`](.claude/commands), [`TASKS.md`](TASKS.md)

## Quick start
```bash
make install
make test                                   # engine, policy, metrics tests
make run                                    # API on :8080
sdlc run --scenario greenfield --replay     # no API key; uses runs/cache
SDLC_LLM=fake SDLC_TARGET_STACK=python make run   # offline loop: canned agents + fake executor; then:
sdlc run --scenario greenfield              #   pauses at approval_design like a live run
sdlc approve <run_id> approval_design       #   runs tasks, pauses on the HIGH release task
sdlc approve <run_id> implementation        #   gates -> validation -> release_readiness -> approval_release
sdlc approve <run_id> approval_design
sdlc metrics <run_id>
sdlc graph | pbcopy                         # mermaid of the DAG
```
Live: `cp .env.example .env`, set `ANTHROPIC_API_KEY`, then `sdlc run --scenario greenfield --record`.

## Layout
```
workflow.yaml  policy.yaml  CLAUDE.md  TASKS.md  docker-compose.yml
src/orchestrator/{models,engine,agents,executors,llm,sandbox,store,trace,api,prompts}  cli.py  service.py  main.py
tests/           engine, policy engine, metrics
specs/scenarios/ greenfield.md brownfield.md ambiguous.md   specs/templates/
runs/<id>/       state.json  artifacts/*.v*.json  trace.jsonl  approvals.jsonl  sandbox/  rejected/
sql/views.sql    Postgres schema + metric views (must match trace/metrics.py)
```

## Testing approach
Engine is tested without any LLM: readiness/join/fallback edges, retries with structured feedback, fallback → diagnose →
retry/replan routes, replan limit safe-stop, budget safe-stop, input pause + spec v2 invalidation, approval pause/resume.
Policy engine is tested on path classification, scope, secret/banned/PII scans, command allowlist. Metrics are pinned by
a fixture trace. The contract and architecture gates run against fixture repos under `tests/fixtures/`. Golden replay
runs in CI (T15).

## Limitations
See `docs/architecture.md` — sandbox is process+policy (not a container), node-level invalidation, in-memory live-run
registry until T12, acceptance review advisory by design.
