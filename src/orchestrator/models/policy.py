from __future__ import annotations

from typing import Any

from pydantic import BaseModel, ConfigDict


class _Model(BaseModel):
    model_config = ConfigDict(frozen=True, extra="allow")


class Autonomy(_Model):
    default_level: str
    high_impact_actions: list[str]
    auto_approve_in_replay: bool = True


class Budgets(_Model):
    max_tokens_per_run: int
    max_cost_usd_per_run: float
    max_wall_clock_minutes: int
    max_attempts_per_task: int
    max_replans_per_run: int
    node_timeout_seconds: int
    parallelism: int


class Retries(_Model):
    backoff: str = "exponential"
    initial_delay_seconds: float = 3
    max_delay_seconds: float = 60
    retry_on: list[str] = []
    never_retry_on: list[str] = []
    on_exhausted: str = "fallback_or_rollback"


class ChangeControl(_Model):
    allowed_paths: list[str]
    protected_paths: list[str]
    forbidden_paths: list[str]
    max_files_changed_per_task: int
    max_lines_changed_per_task: int
    require_tests_for: list[str] = []


class Sandbox(_Model):
    branch_prefix: str = "run/"
    allowed_commands: list[str]
    forbidden_commands: list[str]


class Security(_Model):
    secret_patterns: list[str]
    banned_code_patterns: dict[str, list[str]]
    dependency_allowlist: dict[str, list[str]]


class GateDef(_Model):
    required: bool
    cmd: str
    coverage_floor_changed: float | None = None


class ClaudeCode(_Model):
    permission_mode: str
    max_turns: int
    allowed_tools: list[str]
    disallowed_tools: list[str]
    system_prompt_file: str


class Policy(_Model):
    version: int
    autonomy: Autonomy
    budgets: Budgets
    retries: Retries
    change_control: ChangeControl
    sandbox: Sandbox
    security: Security
    compliance: dict[str, Any] = {}
    gates: dict[str, Any]
    observability: dict[str, Any] = {}
    claude_code: ClaudeCode

    def gate(self, gate_id: str, stack: str) -> GateDef:
        """Gate definitions may be flat or keyed by target stack."""
        raw = self.gates[gate_id]
        if "cmd" in raw:
            return GateDef.model_validate(raw)
        return GateDef.model_validate(raw[stack])


def load_policy(path: str = "policy.yaml") -> Policy:
    import yaml

    with open(path, encoding="utf-8") as f:
        return Policy.model_validate(yaml.safe_load(f))
