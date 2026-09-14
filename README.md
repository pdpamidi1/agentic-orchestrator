# agentic-orchestrator — governed agentic SDLC engine (Python / FastAPI)

> Agents propose; gates verify; humans approve.

Turns a requirement into a reviewable engineering outcome by executing an explicit, YAML-defined DAG of SDLC stages
(requirements → clarify → planning → architecture → security ∥ risk → approval → implementation → tests ∥ review →
validation → diagnose/retry/re-plan → documentation → release readiness → approval) with policy guardrails, bounded
retries, rollback, safe-stop, audit trace, derived metrics and persisted state.

- Architecture and decisions: [`docs/architecture.md`](docs/architecture.md)
- The graph: [`workflow.yaml`](workflow.yaml) · Governance: [`policy.yaml`](policy.yaml)
- Team practice for AI-assisted work: [`CLAUDE.md`](CLAUDE.md), [`.claude/commands/`](.claude/commands), [`TASKS.md`](TASKS.md)

## Setting up on a new machine

```bash
# prerequisites: git, Python 3.12+, Docker Engine; for java target projects also JDK 25 (Maven comes via ./mvnw);
# for live runs an Anthropic API key and the Claude Code CLI (`claude`) on PATH
git clone https://github.com/<your-org>/agentic-orchestrator.git && cd agentic-orchestrator
python3 -m venv .venv && . .venv/bin/activate
make install                                 # pip install -e ".[dev]"
make test && make lint                       # ~2.5 min: engine, gates, fake end-to-end runs

# offline demo (no key): canned agents + fake executor, python target stack
SDLC_LLM=fake SDLC_TARGET_STACK=python make run &        # API on :8080
sdlc run --scenario greenfield                           # pauses at approval_design
sdlc approve <run_id> approval_design                    # planted .env violation -> revert -> retry; pauses on the HIGH task
sdlc approve <run_id> implementation                     # gates -> validation -> release_readiness -> approval_release
sdlc approve <run_id> approval_release && sdlc metrics <run_id>

# replay the recorded java run (no key): agents and task patches come from runs/cache
sdlc run --scenario greenfield --replay

# live: cp .env.example .env, set ANTHROPIC_API_KEY (and, only if the key is not workspace-scoped,
# ANTHROPIC_WORKSPACE_ID=wrkspc_...), then
make run &
sdlc run --scenario greenfield --record                  # answers via `sdlc answer`, approvals via `sdlc approve`
sdlc deliver <run_id>                                    # after approval_release: copies the project to workspace/url-shortener
```

The delivered url-shortener runs with `make shortener-up` (see below) and has its own
[quick start](workspace/url-shortener/README.md) and [design document](workspace/url-shortener/docs/DESIGN.md).

## Quick start
```bash
make install
make test                                   # engine, policy, metrics tests
make run                                    # API on :8080
sdlc run --scenario greenfield --replay     # no API key; uses runs/cache
SDLC_LLM=fake SDLC_TARGET_STACK=python make run   # offline loop: canned agents + fake executor; then:
sdlc run --scenario greenfield              #   pauses at approval_design like a live run
sdlc brief <run_id> approval_design         #   the design/plan/cost/changes brief a human decides on (also printed by run/answer)
sdlc report <run_id>                        #   runs/<id>/run_report.md: requirement, spec, design, plan, decisions, timeline, gates, metrics
sdlc approve <run_id> approval_design       #   attempt 1 writes .env -> VIOLATION + revert; attempt 2 clean;
                                            #   then pauses on the HIGH release task
sdlc resume <run_id>                        #   after an API restart: continue a run that was RUNNING when its
                                            #   process died (paused/halted runs: approve or answer, as before)
sdlc approve <run_id> implementation        #   gates -> validation -> release_readiness -> approval_release
sdlc approve <run_id> approval_design
sdlc metrics <run_id>
sdlc graph | pbcopy                         # mermaid of the DAG
```
Live: `cp .env.example .env`, set `ANTHROPIC_API_KEY`, then `sdlc run --scenario greenfield --record`.
Brownfield offline: `sdlc run --scenario brownfield --workspace tests/fixtures/brownfield_ws`; the impact
node runs on the repo map, the migration and dependency tasks each pause for approval, and the compliance scan
catches a raw-IP field on attempt 1.
Ambiguous offline: `sdlc run --scenario ambiguous` pauses with 6 questions; `sdlc answer <run_id> '{"AMB-1": "..."}'`
produces spec v2; a planted failing test then drives diagnose -> re-plan (plan v2, v3) until `replan.limit_reached`.
Offline golden run: start the API with `SDLC_LLM=fake SDLC_TARGET_STACK=python`, run with `--record` and approve
twice; the run is now in `runs/cache/` and `sdlc run --scenario greenfield --replay` completes it with no key.

## Running the delivered url-shortener
The first delivered project lives in [`workspace/url-shortener`](workspace/url-shortener) (run `greenfield-a37c3044`,
Java 25 / Spring Boot 4 / Maven, see its README for endpoints and profiles).
`sdlc deliver <run_id>` copies a COMPLETED run into `workspace/url-shortener` (a standalone Spring Boot / Maven
project: `./mvnw test`, `./mvnw -Pit verify`). To run it against real Postgres and Redis and look at the data:
```bash
make shortener-up                 # postgres :5433, redis :6380, kafka (:9094 from the host), app :8081
                                  # (builds workspace/url-shortener/Dockerfile; Kafka carries url.clicked events)
curl -s -X POST localhost:8081/api/v1/urls -H 'content-type: application/json' \
     -d '{"long_url":"https://example.com/a/very/long/path"}'
curl -si localhost:8081/<short_code>            # 302 with Location
make shortener-psql               # then: select short_code, long_url, code_source, expires_at from urls;
make shortener-redis              # then: keys *   /   get <key>
make shortener-up-all             # same stack (alias kept from before Kafka joined the profile)
curl -s localhost:8081/api/v1/urls/<short_code>/stats   # click analytics: totals + per-day counts (brownfield)
make shortener-kafka              # tail the url.clicked topic
make shortener-down
```
The orchestrator's own `postgres`/`redis` services (5432/6379, for the store and trace sink) stay separate.

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
See `docs/architecture.md` — sandbox is process+policy (not a container), node-level invalidation, live runs are
reloaded from `runs/<id>` after an API restart (T12; Postgres store is T10), acceptance review advisory by design.
