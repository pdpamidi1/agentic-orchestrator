"""
FakeClient: an LLMClient that returns canned artifacts per output schema, no network, zero cost.

Selected with `SDLC_LLM=fake`. It exists so the whole loop (agents -> gates -> approvals -> executor) can be
exercised offline and deterministically, before any prompt has been recorded. The canned artifacts describe
the greenfield URL-shortener scenario and satisfy every agent schema in `agents/catalog.py`; a test pins that.
Overrides let a scenario or a test replace any artifact by schema name (e.g. a Spec with ambiguities).
"""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

from pydantic import BaseModel

from .client import Usage

CANNED: dict[str, dict[str, Any]] = {
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
        ],
        "rationale": "schema first, then the two endpoints in parallel",
    },
    "Design": {
        "run_id": "fake",
        "spec_version": 1,
        "api": {
            "openapi_yaml": "openapi: 3.1.0\ninfo: {title: shortener, version: '1.0'}\npaths: {}\n",
            "operations": [
                {
                    "method": "POST",
                    "path": "/api/v1/urls",
                    "operation_id": "createShortUrl",
                    "responses": {201: "ShortUrl", 400: "Problem", 409: "Problem"},
                },
                {
                    "method": "GET",
                    "path": "/{short_code}",
                    "operation_id": "redirect",
                    "responses": {302: "redirect", 404: "Problem", 410: "Problem"},
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
    "Impact": {
        "run_id": "fake",
        "impacted_packages": [],
        "new_packages": ["shortener.api", "shortener.service", "shortener.repo"],
    },
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
    "Diagnosis": {
        "root_cause": "fake diagnosis: gate failed on the previous attempt",
        "decision": "retry",
        "feedback": {"hint": "address the blocking findings"},
    },
    "Docs": {
        "readme_md": "# shortener\n\nFake docs.\n",
        "changelog_md": "## 0.1.0\n- initial\n",
        "adr_md": ["# ADR-1 base62 counter codes\n"],
        "runbook_md": "# Runbook\n\nHealth: GET /healthz\n",
    },
}


class FakeClient:
    """Canned structured output keyed by schema name. Unknown schema -> ValueError (an agent Retry)."""

    def __init__(self, overrides: Mapping[str, Mapping[str, Any]] | None = None) -> None:
        self.canned: dict[str, dict[str, Any]] = {k: dict(v) for k, v in CANNED.items()}
        for name, data in (overrides or {}).items():
            self.canned[name] = dict(data)
        self.calls: list[str] = []

    async def structured[T: BaseModel](
        self, system: str, prompt: str, schema: type[T], *, max_repairs: int = 2
    ) -> tuple[T, Usage]:
        self.calls.append(schema.__name__)
        data = self.canned.get(schema.__name__)
        if data is None:
            raise ValueError(f"FakeClient has no canned artifact for schema {schema.__name__}")
        return schema.model_validate(data), Usage()
