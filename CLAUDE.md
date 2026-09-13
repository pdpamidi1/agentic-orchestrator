# CLAUDE.md — agentic-orchestrator

You are working in the **orchestrator**: a Python 3.12 / FastAPI service that turns a requirement into a reviewable
engineering outcome by executing a governed DAG of SDLC stages (`workflow.yaml`) under `policy.yaml`, over a target
codebase in a sandbox (Java 25 / Spring Boot 4 or Python 3 / FastAPI). Treat every change as production work.

## Golden rules
1. **The graph is data.** Stages, edges, gates, fallbacks and invalidation live in `workflow.yaml`. Never hard-code a
   stage in Python. New stage => new node in YAML + (maybe) a handler in `engine/handlers.py`.
2. **Specs before code.** Agents implement from typed artifacts (`Spec`, `Design`, `Plan.TaskSpec`), never from the
   raw requirement. If a spec is missing or ambiguous, the correct output is an `Ambiguity`, not a guess.
3. **Policy is law.** `policy.yaml` decides what may be touched, run, and merged. Don't edit it to make a task pass.
   The engine enforces it (`engine/policy_engine.py`) and records every decision as a `POLICY_DECISION` event.
4. **Gates decide, not agents.** A node is done when its gates pass. `ValidationResult.passed` is the truth.
5. **Never bypass a human checkpoint.** `AWAITING_APPROVAL`/`AWAITING_INPUT` only advance through the API/CLI.
   Replay mode may auto-approve; live mode never does.
6. **Trace everything, store metrics nowhere.** Every decision point emits a `TraceEvent`. Metrics are computed from
   events (`trace/metrics.py`, `sql/views.sql`). Do not add counters.
7. **Every production change ships with a test.** `make test` must be green before you finish a task.

## Commands
| Purpose | Command |
|---|---|
| Install | `make install` (`pip install -e ".[dev]"`) |
| Tests | `make test` (`pytest -q`) |
| Lint + types | `make lint` (`ruff check`, `mypy src`) |
| API | `make run` (uvicorn on :8080) |
| Demo, no API key | `sdlc run --scenario greenfield --replay` |
| Live + record cache | `sdlc run --scenario greenfield --record` |
| Approve / answer | `sdlc approve <run> <node>` · `sdlc answer <run> '{"q1":"..."}'` |
| Metrics / graph | `sdlc metrics <run>` · `sdlc graph` |

## Layout (`src/orchestrator/`)
- `models/`     Pydantic artifacts (frozen): spec, design, plan, impact, validation, trace, state, policy
- `engine/`     `graph.py` (DAG + readiness), `runner.py` (execution, gates, retries, rollback, invalidation, safe-stop),
                `handlers.py` (node kinds), `gates.py` (validation), `policy_engine.py`, `conditions.py`, `context.py`, `outcomes.py`
- `agents/`     `base.py` (prompt + schema), `catalog.py` (requirements, planner, architecture, impact, security, risk, reviewer, diagnoser, documenter)
- `executors/`  `claude_code_cli.py` (primary), `replay.py` (recorded patches), `base.py`
- `llm/`        structured-output client: Anthropic (tool-forced JSON), Replay, Recording
- `sandbox/`    `git.py` (branch per run, commit per task, revert), `process.py` (policy-checked commands)
- `store/`      `file_store.py` (runs/<id>/state.json + artifacts); Postgres store is a TASKS item
- `trace/`      sinks (jsonl, memory) and `metrics.py`
- `api/`, `main.py`, `cli.py`, `service.py` (composition root), `config.py`
- `prompts/`    one Markdown template per agent; placeholders are artifact names from `Agent.reads`

Dependency direction: `api -> service -> engine -> {agents, executors, sandbox, trace, store}`; `models` is a leaf.
`tests/test_architecture.py` (TASKS item) will enforce this with import-linter.

## Conventions
- Python 3.12, `from __future__ import annotations`, full type hints, mypy strict.
- Artifacts are frozen Pydantic models; new versions are new objects (lineage), never mutation.
- Outcomes are dataclasses matched with `match` in `runner._apply`; add a new outcome only with a new `case`.
- Async everywhere on the hot path; subprocesses via `asyncio.create_subprocess_*` with timeouts.
- No global mutable state except the prototype's in-memory `svc.runs` registry (documented limitation).
- Prompts live in `prompts/*.md`, never inline. Every LLM call is `structured()` with a Pydantic schema.
- Errors inside a handler are an attempt failure (Retry), never a crash of the run.
- Logging via structlog with `run_id`, `node_id`, `attempt` bound; never log prompt bodies at INFO.

## Definition of done for any task in this repo
- [ ] Refers to a `TASKS.md` item or an ADR in `docs/adr/`
- [ ] `make test && make lint` green
- [ ] New decision point emits a `TraceEvent`; new policy rule has a `POLICY_DECISION`
- [ ] Behaviour change => `docs/architecture.md` updated in the same commit
- [ ] No new dependency without an ADR

## Never
- Edit `policy.yaml`, `.claude/settings.json`, `.env`
- Add a node to `workflow.yaml` without `depends_on` and (for agents/executors) `produces` + `invalidated_by`
- Call an LLM without a schema
- Auto-approve anything in a live run
- Persist a metric outside `trace_event` / `trace.jsonl`
