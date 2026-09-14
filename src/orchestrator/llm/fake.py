"""
FakeClient: an LLMClient that returns canned artifacts per output schema, no network, zero cost.

Selected with `SDLC_LLM=fake`. It exists so the whole loop (agents -> gates -> approvals -> executor) can be
exercised offline and deterministically, before any prompt has been recorded. The canned artifacts describe
the greenfield URL-shortener scenario and satisfy every agent schema in `agents/catalog.py`; a test pins that.
Overrides let a scenario or a test replace any artifact by schema name (e.g. a Spec with ambiguities).

Where it sits: `service.py#_mode` builds `FakeClient(scenario=...)` when `SDLC_LLM=fake` and pairs it with
`executors/fake.FakeExecutor(plant=PLANTED[scenario])`; under `--record` the pair is wrapped by the
recording client/executor, which is how the offline golden run (`--replay` with no key) is produced.

Invariants:
- Live checkpoint semantics: nothing here auto-approves; the canned artifacts merely drive the graph to
  the same pauses a live run would hit (HIGH tasks, six ambiguities in the `ambiguous` scenario).
- Every canned artifact validates against its schema in `agents/catalog.py`/`models/`; the agents whose
  `post()` stamps lineage (requirements, planner, architecture) overwrite `run_id: "fake"` and the
  version fields exactly as for a live reply.
- Deterministic and free: `Usage()` is always zero; `FakeClient.calls` records the schema names requested
  in order so tests can assert which agents ran.

Data layout: `CANNED` is the greenfield baseline (one entry per schema name); `BROWNFIELD_*` and
`AMBIGUOUS_SPEC` derive from it; `SCENARIOS` maps a scenario name to its overrides; `PLANTED` maps a
scenario name to the failure the fake executor plants. No files are written, no trace events emitted.
"""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

from pydantic import BaseModel

from .client import Usage

# Greenfield baseline, keyed by output schema name. `run_id: "fake"` and version fields are placeholders
# that each agent's `post()` overwrites with the real run id and lineage.
CANNED: dict[str, dict[str, Any]] = {
    # Spec: three stories, four acceptance criteria (AC1-AC4), no ambiguities -> the `clarify` input node
    # does not pause and the greenfield run proceeds straight to planning.
    "Spec": {
        "run_id": "fake",
        "summary": "URL shortener: create short links, redirect with caching, expire links.",
        "stories": [
            {
                "id": "US1",
                "as_a": "API client",
                "i_want": "to POST a long URL and get a short URL back",
                "so_that": "I can share a compact link",
            },
            {
                "id": "US2",
                "as_a": "visitor",
                "i_want": "to open a short URL and be redirected",
                "so_that": "I reach the original page",
            },
            {
                "id": "US3",
                "as_a": "API client",
                "i_want": "to set an expiration date on a short URL",
                "so_that": "stale links stop working",
            },
        ],
        "acceptance_criteria": [
            {
                "id": "AC1",
                "given": "a valid long_url",
                "when": "POST /api/v1/urls",
                "then": "201 with {short_url}",
            },
            {
                "id": "AC2",
                "given": "an invalid long_url",
                "when": "POST /api/v1/urls",
                "then": "400 problem+json",
            },
            {
                "id": "AC3",
                "given": "an existing short code",
                "when": "GET /{short_code}",
                "then": "302 to the long URL with Cache-Control: private",
            },
            {
                "id": "AC4",
                "given": "an expired short code",
                "when": "GET /{short_code}",
                "then": "410",
            },
        ],
        "non_goals": ["authentication", "analytics"],
        "ambiguities": [],
        "assumptions": ["base62 codes from a counter; Redis optional with DB sequence fallback"],
    },
    # Plan: T1 (schema) -> T2 ∥ T3 (the two endpoints) -> T4. T4 is impact_level HIGH and owns only the
    # release artifacts (README, openapi.yaml, Dockerfile, CI), two of them protected paths, so the
    # implementation node pauses once for a `task.high_impact` approval and the fake executor's
    # release-file branch is exercised. allowed_files `src/**`/`tests/**` receive the fake module + test.
    "Plan": {
        "run_id": "fake",
        "spec_version": 1,
        "tasks": [
            {
                "id": "T1",
                "title": "Data model and migration for urls table",
                "allowed_files": ["src/**", "tests/**"],
                "data_model_slice": ["urls"],
                "acceptance_criteria_ids": ["AC1"],
                "definition_of_done": ["migration applies", "repository test passes"],
            },
            {
                "id": "T2",
                "title": "Create short URL endpoint",
                "depends_on": ["T1"],
                "allowed_files": ["src/**", "tests/**"],
                "contract_slice": ["createShortUrl"],
                "class_structure": ["shortener.api.UrlController", "shortener.service.ShortenerService"],
                "acceptance_criteria_ids": ["AC1", "AC2"],
                "definition_of_done": ["201/400/409 covered by tests"],
            },
            {
                "id": "T3",
                "title": "Redirect endpoint with cache and expiry",
                "depends_on": ["T1"],
                "allowed_files": ["src/**", "tests/**"],
                "contract_slice": ["redirect"],
                "class_structure": ["shortener.api.RedirectController"],
                "acceptance_criteria_ids": ["AC3", "AC4"],
                "definition_of_done": ["302/404/410 covered by tests"],
            },
            {
                "id": "T4",
                "title": "Release artifacts: README, OpenAPI document, Dockerfile, CI workflow",
                "depends_on": ["T2", "T3"],
                "impact_level": "HIGH",
                "allowed_files": ["README.md", "openapi.yaml", "Dockerfile", ".github/workflows/ci.yml"],
                "definition_of_done": ["release checklist passes"],
                "risk_notes": "protected: Dockerfile (infrastructure.change), CI workflow (release.config)",
            },
        ],
        "rationale": "schema first, the two endpoints in parallel, then release artifacts under approval",
    },
    # Design: the API contract (openapi_yaml + typed operations with response codes) is what the fake
    # executor turns into `app.py`/`openapi.yaml` and what the `contract` gate diffs; the three packages
    # and the layering rule become the generated import-linter architecture test.
    "Design": {
        "run_id": "fake",
        "spec_version": 1,
        "api": {
            "openapi_yaml": (
                "openapi: 3.1.0\n"
                "info: {title: shortener, version: '1.0'}\n"
                "paths:\n"
                "  /api/v1/urls:\n"
                "    post:\n"
                "      operationId: createShortUrl\n"
                "      responses: {'201': {description: ShortUrl}, '400': {description: Problem},"
                " '409': {description: Problem}}\n"
                "  /{short_code}:\n"
                "    get:\n"
                "      operationId: redirect\n"
                "      responses: {'302': {description: redirect}, '404': {description: Problem},"
                " '410': {description: Problem}}\n"
            ),
            "operations": [
                {
                    "method": "POST",
                    "path": "/api/v1/urls",
                    "operation_id": "createShortUrl",
                    "responses": [
                        {"status": 201, "description": "ShortUrl"},
                        {"status": 400, "description": "Problem"},
                        {"status": 409, "description": "Problem"},
                    ],
                },
                {
                    "method": "GET",
                    "path": "/{short_code}",
                    "operation_id": "redirect",
                    "responses": [
                        {"status": 302, "description": "redirect"},
                        {"status": 404, "description": "Problem"},
                        {"status": 410, "description": "Problem"},
                    ],
                },
            ],
        },
        "data": {
            "tables": [
                {
                    "name": "urls",
                    "columns": [
                        {"name": "short_code", "type": "varchar(16)"},
                        {"name": "long_url", "type": "text"},
                        {"name": "created_at", "type": "timestamptz"},
                        {"name": "expires_at", "type": "timestamptz", "nullable": True},
                        {"name": "created_by", "type": "varchar(64)", "nullable": True},
                        {"name": "code_source", "type": "varchar(16)"},
                    ],
                    "constraints": ["PRIMARY KEY (short_code)"],
                }
            ],
            "migrations": [],
            "evolution_notes": "expand/contract; never drop columns in the same release",
        },
        "classes": {
            "packages": [
                {
                    "name": "shortener.api",
                    "classes": [
                        {"fqcn": "shortener.api.UrlController", "responsibility": "POST /api/v1/urls"},
                        {"fqcn": "shortener.api.RedirectController", "responsibility": "GET /{short_code}"},
                    ],
                    "may_depend_on": ["shortener.service"],
                },
                {
                    "name": "shortener.service",
                    "classes": [
                        {"fqcn": "shortener.service.ShortenerService", "responsibility": "codes + expiry"}
                    ],
                    "may_depend_on": ["shortener.repo"],
                },
                {
                    "name": "shortener.repo",
                    "classes": [{"fqcn": "shortener.repo.UrlRepository", "responsibility": "urls table"}],
                },
            ],
            "layering_rules": ["shortener.api -> shortener.service -> shortener.repo"],
        },
        "decisions": ["base62 counter over random codes (collision-free, sortable); rejected: UUID prefix"],
    },
    # Impact: greenfield has nothing to impact, only new packages (the impact node runs only under
    # `when: workspace.has_code` in workflow.yaml, but the schema is served whenever it is asked for).
    "Impact": {
        "run_id": "fake",
        "impacted_packages": [],
        "new_packages": ["shortener.api", "shortener.service", "shortener.repo"],
    },
    # SecurityFindings: one medium finding and no pre-announced high-impact actions, so the security
    # review passes without adding approvals to the run.
    "SecurityFindings": {
        "findings": [
            {
                "id": "S1",
                "severity": "medium",
                "area": "input",
                "description": "long_url must be validated (scheme, length) to avoid open-redirect abuse",
                "requirement": "AC2",
            }
        ],
        "required_controls": ["URL validation", "rate limit on create"],
        "high_impact_actions_expected": [],
    },
    # RiskRegister: a single medium risk; exercises the risk node in parallel with security review.
    "RiskRegister": {
        "risks": [
            {
                "id": "R1",
                "description": "Redis unavailable during code allocation",
                "likelihood": "medium",
                "severity": "medium",
                "mitigation": "fall back to DB sequence; record code_source",
                "detection": "code_source=db ratio alert",
            }
        ],
        "trade_offs": ["counter codes are guessable; acceptable without auth (non-goal)"],
        "failure_scenarios": ["cache returns a stale long_url after expiry -> TTL bounded by expires_at"],
    },
    # Review: every criterion PASS and APPROVE, so the advisory code review never blocks validation.
    "Review": {
        "criteria": [
            {"id": "AC1", "verdict": "PASS", "evidence": "fake"},
            {"id": "AC2", "verdict": "PASS", "evidence": "fake"},
            {"id": "AC3", "verdict": "PASS", "evidence": "fake"},
            {"id": "AC4", "verdict": "PASS", "evidence": "fake"},
        ],
        "concerns": [],
        "recommendation": "APPROVE",
    },
    # Diagnosis: decision "retry" -> a failed validation re-runs implementation with this feedback
    # (the `ambiguous` scenario overrides it with "replan").
    "Diagnosis": {
        "root_cause": "fake diagnosis: gate failed on the previous attempt",
        "decision": "retry",
        "feedback": [{"target": "implementation", "instruction": "address the blocking findings"}],
    },
    # Docs: minimal Markdown for each document the documenter must return; enough for release readiness.
    "Docs": {
        "readme_md": "# shortener\n\nFake docs.\n",
        "changelog_md": "## 0.1.0\n- initial\n",
        "adr_md": ["# ADR-1 base62 counter codes\n"],
        "runbook_md": "# Runbook\n\nHealth: GET /healthz\n",
    },
}


# Brownfield scenario: click analytics added to an existing shortener workspace (`SDLC_WORKSPACE`).
# Spec: AC3 (only a salted hash of the IP may be persisted) is what the planted "pii" failure violates.
BROWNFIELD_SPEC: dict[str, Any] = {
    "run_id": "fake",
    "summary": "Click analytics for the existing URL shortener: publish click events, aggregate stats.",
    "stories": [
        {
            "id": "US1",
            "as_a": "owner",
            "i_want": "click counts per short code",
            "so_that": "I see what works",
        },
        {
            "id": "US2",
            "as_a": "compliance officer",
            "i_want": "no raw IPs stored",
            "so_that": "we stay lawful",
        },
    ],
    "acceptance_criteria": [
        {
            "id": "AC1",
            "given": "a redirect",
            "when": "GET /{short_code}",
            "then": "a url.clicked event is published",
        },
        {
            "id": "AC2",
            "given": "clicks",
            "when": "GET /api/v1/urls/{short_code}/stats",
            "then": "200 aggregate",
        },
        {
            "id": "AC3",
            "given": "a click",
            "when": "it is persisted",
            "then": "only a salted SHA-256 of the IP",
        },
        {"id": "AC4", "given": "an unknown code", "when": "GET .../stats", "then": "404"},
    ],
    "non_goals": ["dashboards", "auth"],
    "ambiguities": [],
    "assumptions": ["Kafka topic url.clicked; 90-day retention on click_events"],
}
# Plan: B1 (migration under `alembic/**`) and B2 (`pyproject.toml` dependency) are both HIGH and touch
# protected paths, so implementation pauses twice for approval before B3 -> B4 run. Their allowed_files
# are concrete paths, which drives the fake executor's `_stub` branch (append to an existing pyproject).
BROWNFIELD_PLAN: dict[str, Any] = {
    "run_id": "fake",
    "spec_version": 1,
    "tasks": [
        {
            "id": "B1",
            "title": "Migration: click_events and click_stats tables",
            "impact_level": "HIGH",
            "allowed_files": ["alembic/versions/002_click_events.py", "tests/**"],
            "data_model_slice": ["click_events", "click_stats"],
            "acceptance_criteria_ids": ["AC3"],
            "definition_of_done": ["migration applies", "no raw IP column"],
            "risk_notes": "protected: alembic/** (schema.migration)",
        },
        {
            "id": "B2",
            "title": "Dependency: aiokafka producer/consumer",
            "impact_level": "HIGH",
            "allowed_files": ["pyproject.toml"],
            "definition_of_done": ["dependency declared"],
            "risk_notes": "protected: pyproject.toml (dependency.major_version)",
        },
        {
            "id": "B3",
            "title": "Publish url.clicked on redirect with salted IP hash",
            "depends_on": ["B1", "B2"],
            "allowed_files": ["src/**", "tests/**"],
            "contract_slice": ["redirect"],
            "class_structure": ["shortener.analytics.ClickPublisher"],
            "acceptance_criteria_ids": ["AC1", "AC3"],
            "definition_of_done": ["event published; redirect latency unchanged"],
        },
        {
            "id": "B4",
            "title": "Stats endpoint over click_stats",
            "depends_on": ["B3"],
            "allowed_files": ["src/**", "tests/**", "openapi.yaml"],
            "contract_slice": ["getStats"],
            "class_structure": ["shortener.api.StatsController"],
            "acceptance_criteria_ids": ["AC2", "AC4"],
            "definition_of_done": ["200/404 covered by tests"],
        },
    ],
    "rationale": "schema and dependency first (both need approval), then publisher, then the read model",
}
# Design: the greenfield Design plus the `getStats` operation and the two analytics tables, so the
# contract gate sees an additive (non-breaking) change and the migration list is non-empty.
BROWNFIELD_DESIGN: dict[str, Any] = {
    **CANNED["Design"],
    "api": {
        "openapi_yaml": CANNED["Design"]["api"]["openapi_yaml"],
        "operations": [
            *CANNED["Design"]["api"]["operations"],
            {
                "method": "GET",
                "path": "/api/v1/urls/{short_code}/stats",
                "operation_id": "getStats",
                "responses": [
                    {"status": 200, "description": "ClickStats"},
                    {"status": 404, "description": "Problem"},
                ],
            },
        ],
    },
    "data": {
        "tables": [
            *CANNED["Design"]["data"]["tables"],
            {
                "name": "click_events",
                "columns": [
                    {"name": "id", "type": "bigserial"},
                    {"name": "short_code", "type": "varchar(16)"},
                    {"name": "clicked_at", "type": "timestamptz"},
                    {"name": "ip_hash", "type": "char(64)", "notes": "salted sha256; raw IP never stored"},
                ],
            },
            {
                "name": "click_stats",
                "columns": [
                    {"name": "short_code", "type": "varchar(16)"},
                    {"name": "total_clicks", "type": "bigint"},
                    {"name": "last_clicked_at", "type": "timestamptz", "nullable": True},
                ],
                "constraints": ["PRIMARY KEY (short_code)"],
            },
        ],
        "migrations": ["002_click_events"],
        "evolution_notes": "additive only; click_events retained 90 days",
    },
}
# Impact: what the impact node (brownfield-only) reports from the repo_map: touched and new packages,
# endpoints, the Kafka data flow and a hot-path latency risk.
BROWNFIELD_IMPACT: dict[str, Any] = {
    "run_id": "fake",
    "impacted_packages": ["shortener.api", "shortener.repo"],
    "new_packages": ["shortener.analytics"],
    "impacted_endpoints": ["GET /{short_code}", "GET /api/v1/urls/{short_code}/stats"],
    "data_flows": ["redirect -> url.clicked (kafka) -> analytics consumer -> click_stats"],
    "risks": [
        {
            "id": "R1",
            "description": "publishing on the redirect hot path adds latency",
            "likelihood": "medium",
            "severity": "high",
            "mitigation": "fire-and-forget with bounded queue",
            "detection": "p99 redirect latency",
        }
    ],
}


def _amb(i: int, question: str, options: list[str]) -> dict[str, Any]:
    """Build one `Ambiguity` dict (`AMB-<i>`); the first option doubles as the default if unanswered."""
    return {"id": f"AMB-{i}", "question": question, "options": options, "default_if_unanswered": options[0]}


# Ambiguous scenario: the greenfield Spec with a vague summary and six open questions, so the `clarify`
# input node pauses (`AWAITING_INPUT`) until `sdlc answer` supplies answers; the requirements agent then
# folds them into assumptions and produces spec v2.
AMBIGUOUS_SPEC: dict[str, Any] = {
    **CANNED["Spec"],
    "summary": "Make the URL shortener more reliable and production-ready (vague: needs clarification).",
    "ambiguities": [
        _amb(1, "What does 'reliable' mean here?", ["99.9% availability", "no data loss", "both"]),
        _amb(2, "Which failure of Redis must be tolerated?", ["read path only", "read and write", "none"]),
        _amb(3, "Is a rate limit part of production-ready?", ["yes, 100 req/min per IP", "no"]),
        _amb(4, "Should redirects be cached at the edge?", ["Cache-Control: private", "public, 60s"]),
        _amb(5, "Which observability is required?", ["metrics + structured logs", "metrics only", "none"]),
        _amb(6, "Is a Dockerfile in scope?", ["yes", "no, deployment is out of scope"]),
    ],
}
# per-scenario overrides on top of the greenfield canned set
SCENARIOS: dict[str, dict[str, dict[str, Any]]] = {
    "brownfield": {
        "Spec": BROWNFIELD_SPEC,
        "Plan": BROWNFIELD_PLAN,
        "Design": BROWNFIELD_DESIGN,
        "Impact": BROWNFIELD_IMPACT,
    },
    "ambiguous": {
        "Spec": AMBIGUOUS_SPEC,
        # every diagnosis says "re-plan": the run must stop at policy.budgets.max_replans_per_run
        "Diagnosis": {
            "root_cause": "fake diagnosis: the plan cannot satisfy the acceptance test that keeps failing",
            "decision": "replan",
            "feedback": [
                {"target": "planner", "instruction": "split the failing criterion into its own task"}
            ],
        },
    },
}
# which failure the fake executor plants on its first attempt:
# scope (.env), pii (raw IP persisted) or test (a failing unit test that survives every re-plan)
PLANTED: dict[str, str] = {"greenfield": "scope", "brownfield": "pii", "ambiguous": "test"}


class FakeClient:
    """Canned structured output keyed by schema name, with per-scenario overrides.
    Unknown schema -> ValueError (an agent Retry).

    Lifecycle: one instance per run. `canned` is a shallow copy of `CANNED` with `SCENARIOS[scenario]`
    applied first and explicit `overrides` last (an override wins over the scenario). `calls` grows by one
    schema name per `structured()` call and is only read by tests.
    """

    def __init__(
        self, overrides: Mapping[str, Mapping[str, Any]] | None = None, scenario: str | None = None
    ) -> None:
        """Assemble the artifact table: greenfield baseline, then scenario overrides, then `overrides`.

        An unknown `scenario` (or None) applies no scenario overrides.
        """
        self.canned: dict[str, dict[str, Any]] = {k: dict(v) for k, v in CANNED.items()}
        for name, data in {**SCENARIOS.get(scenario or "", {}), **(overrides or {})}.items():
            self.canned[name] = dict(data)
        self.calls: list[str] = []

    async def structured[T: BaseModel](
        self, system: str, prompt: str, schema: type[T], *, max_repairs: int = 2
    ) -> tuple[T, Usage]:
        """Return the canned artifact for `schema.__name__` validated into `schema`, with zero `Usage`.

        `system`, `prompt` and `max_repairs` are ignored (the reply does not depend on the prompt).
        Raises `ValueError` when no artifact is canned for the schema; pydantic's `ValidationError` (also a
        `ValueError`) if an override does not fit the schema. Both become an agent `Retry`.
        """
        self.calls.append(schema.__name__)
        data = self.canned.get(schema.__name__)
        if data is None:
            raise ValueError(f"FakeClient has no canned artifact for schema {schema.__name__}")
        return schema.model_validate(data), Usage()
