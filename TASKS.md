# TASKS.md — ordered backlog for Claude Code

Work top to bottom. Each item is one commit with tests. Stop and ask when an item needs a policy or design change.
Status legend: [ ] todo · [~] in progress · [x] done

## Day 1 — make the loop real
- [x] **T1 Bootstrap**: `make install && make test && make lint`. Fix anything the starter left broken (the engine tests in
      `tests/` are the spec for `engine/`). Do not weaken a test to make it pass; fix the engine.
- [x] **T2 Stub agents (offline loop)**: add `llm/fake.py` (`FakeClient`) returning canned artifacts per schema, and a
      `SDLC_LLM=fake` setting. `sdlc run --scenario greenfield` must reach `approval_design` with no network.
- [x] **T3 Sandbox executor for the fake loop**: `executors/fake.py` writes a trivial file + test into the sandbox and commits.
      Prove: implementation -> gates -> validation -> release_ready -> approval_release end to end with `SDLC_LLM=fake`.
- [x] **T4 Gates**: implement `openapi_diff` (python: import app and call `app.openapi()`; java: parse committed
      `openapi.yaml` vs springdoc dump produced by the integration gate) and the `architecture` gate for python via
      import-linter contract generated from `Design.classes.layering_rules`. Tests with fixture repos under `tests/fixtures/`.
- [x] **T5 Planted failure**: in the fake executor, make attempt 1 of `implementation` violate `scope` (write `.env`),
      assert the run shows `POLICY_DECISION=VIOLATION`, a revert, and attempt 2 passing. This is the demo's best minute.
- [~] **T6 Live agents** (prep committed; recording blocked: no ANTHROPIC_API_KEY on this machine): run `sdlc run --scenario greenfield --record` with a real key; fix prompts until every schema
      validates within `max_repairs`. Commit `runs/cache/llm/*.json`.
- [~] **T7 Claude Code executor** (prep committed: recording executor, replay-safe cache keys, fake-binary executor tests, offline golden replay; the live Claude Code run needs T6's key): run `implementation` live via `executors/claude_code_cli.py`; record patches to
      `runs/cache/changesets/greenfield/`. Verify `--replay` reproduces the run without a key.

## Day 2 — scenarios, metrics, docs
- [x] **T8 Brownfield** (offline record + replay proven; live recording waits for T6's key): `repo_map` producer (walk `src/`, package graph, endpoints, tables) put into context at intake;
      `impact` node runs; two high-impact task approvals fire (`schema.migration`, `dependency.major_version`);
      compliance gate blocks a raw-IP field on attempt 1. Record + replay.
- [ ] **T9 Ambiguous**: spec with >= 5 ambiguities; `sdlc answer` produces spec v2; assert `NODE_INVALIDATED` set equals
      the expected downstream; `plan.previous_version == 1`; one deliberate second re-plan hits `replan.limit_reached`.
- [ ] **T10 Postgres store + trace sink**: `store/pg_store.py` and `trace/pg_sink.py` (SQLAlchemy async), migrations in
      `sql/`, `sql/views.sql` metrics must equal `trace/metrics.py` on the same run (test both).
- [ ] **T11 Run report**: `reports/run_report.py` renders `runs/<id>/run_report.md`: requirement -> plan -> tasks ->
      gates -> approvals -> metrics -> lineage table -> risks/assumptions/limitations. Wire into `release_readiness`.
- [ ] **T12 Resume**: rebuild `RunContext` from `runs/<id>/artifacts` so `approve`/`answer` work after an API restart.
- [ ] **T13 Kafka trace mirror** (profile `kafka`): `trace/kafka_sink.py` publishing `TraceEvent`s to `sdlc.trace.events`.
- [ ] **T14 Docs**: `docs/architecture.md` decisions table with rejected alternatives; README setup; three scenario
      walkthroughs with links to committed run artifacts; limitations and trade-offs.
- [ ] **T15 CI**: GitHub Actions: lint, test, and a `--replay` golden run for all three scenarios diffed against committed metrics.

## Stretch
- [ ] Container-isolated executor (docker run with the sandbox mounted) instead of process + path policy
- [ ] Task-level (not node-level) invalidation using `Plan.invalidated_task_ids`
- [ ] Approvals web page (FastAPI + htmx) showing pending approvals with the summary artifacts
- [ ] Agent evals: nightly replay of golden runs, alert on metric drift
