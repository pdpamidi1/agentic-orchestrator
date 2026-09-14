"""Typed view of ``policy.yaml``: what agents may touch, run and merge, and how much they may spend.

Loaded once per process by ``load_policy`` (``service.py``) and carried on ``RunContext.policy``; enforced
by ``engine/policy_engine.py`` (paths, sizes, scans, commands), ``engine/runner.py`` (budgets, retries,
approvals, replay auto-approve) and ``engine/gates.py`` (gate commands). The engine never edits policy;
every decision it takes from it is recorded as a ``POLICY_DECISION`` event.

Unlike artifacts these models use ``extra="allow"``: the YAML may carry sections the code does not yet read
(compliance, observability) without failing to load. They are still frozen.
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, ConfigDict


class _Model(BaseModel):
    """Base for policy sections: immutable, but tolerant of extra YAML keys (forward compatibility)."""

    model_config = ConfigDict(frozen=True, extra="allow")


class Autonomy(_Model):
    """How much the agents may do unattended.

    ``high_impact_actions`` are the action names that always pause with ``AWAITING_APPROVAL``
    (``plan.approve``, ``schema.migration``, ``api.contract.breaking_change``, ``release.merge``, ...).
    ``auto_approve_in_replay`` lets replay runs pass those checkpoints unattended; live runs never do.
    """

    default_level: str
    high_impact_actions: list[str]
    auto_approve_in_replay: bool = True


class Budgets(_Model):
    """Hard limits per run; exceeding tokens, cost or wall clock safe-stops with ``budget.exceeded:<what>``.

    ``max_attempts_per_task`` bounds retries; ``max_replans_per_run`` bounds ``diagnose -> replan`` cycles
    (``replan.limit_reached``); ``parallelism`` caps the ready batch the runner executes at once.
    """

    max_tokens_per_run: int
    max_cost_usd_per_run: float
    max_wall_clock_minutes: int
    max_attempts_per_task: int
    max_replans_per_run: int
    node_timeout_seconds: int
    parallelism: int


class Retries(_Model):
    """Retry shape. The runner uses exponential backoff between ``initial_delay_seconds`` and
    ``max_delay_seconds``; ``on_exhausted`` ending in ``halt`` safe-stops instead of taking the fallback.
    ``retry_on``/``never_retry_on`` are declarative labels not evaluated by the prototype."""

    backoff: str = "exponential"
    initial_delay_seconds: float = 3
    max_delay_seconds: float = 60
    retry_on: list[str] = []
    never_retry_on: list[str] = []
    on_exhausted: str = "fallback_or_rollback"


class ChangeControl(_Model):
    """Which paths an executor may write and how large a task's change may be.

    ``allowed_paths`` = free to change; ``protected_paths`` = need a high-impact approval;
    ``forbidden_paths`` = never (a write is a VIOLATION, reverted). ``require_tests_for`` lists production
    prefixes whose changes must come with a test change (glob patterns).
    """

    allowed_paths: list[str]
    protected_paths: list[str]
    forbidden_paths: list[str]
    max_files_changed_per_task: int
    max_lines_changed_per_task: int
    require_tests_for: list[str] = []


class Sandbox(_Model):
    """Process/git sandbox rules: run branches are ``<branch_prefix><run_id>``; commands are allowlisted."""

    branch_prefix: str = "run/"
    allowed_commands: list[str]
    forbidden_commands: list[str]


class Security(_Model):
    """Patterns the write-boundary and ``security`` gate scan for, plus the dependency allowlist per stack.

    ``banned_code_patterns`` is ``{stack: [regex, ...]}``, ``dependency_allowlist`` ``{stack: [name, ...]}``;
    these dicts are loaded from YAML, not produced by an LLM, so free-form maps are fine here.
    """

    secret_patterns: list[str]
    banned_code_patterns: dict[str, list[str]]
    dependency_allowlist: dict[str, list[str]]


class GateDef(_Model):
    """One validation gate: whether a FAILED status blocks, the shell command to run, optional coverage floor
    on changed files. Resolved per stack by ``Policy.gate``."""

    required: bool
    cmd: str
    coverage_floor_changed: float | None = None


class ClaudeCode(_Model):
    """Settings handed to the Claude Code CLI executor: permission mode, turn cap, tool allow/deny lists and
    the system prompt file. Tool permissions are policy, not prompt."""

    permission_mode: str
    max_turns: int
    allowed_tools: list[str]
    disallowed_tools: list[str]
    system_prompt_file: str


class Policy(_Model):
    """Root of ``policy.yaml``.

    ``gates`` stays a raw mapping because each entry may be flat (``{required, cmd}``) or keyed by target
    stack (``{java: {...}, python: {...}}``); ``gate`` normalises it. ``compliance`` and ``observability``
    are read by the compliance scan and documentation only, hence untyped.
    """

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
        """Gate definitions may be flat or keyed by target stack.

        Raises ``KeyError`` for an unknown gate id or a stack the gate does not define, and a Pydantic
        ``ValidationError`` if the entry lacks ``required``/``cmd``.
        """
        raw = self.gates[gate_id]
        if "cmd" in raw:
            return GateDef.model_validate(raw)
        return GateDef.model_validate(raw[stack])


def load_policy(path: str = "policy.yaml") -> Policy:
    """Parse and validate ``policy.yaml`` into a ``Policy``. Missing required sections raise at load time,
    before any run starts. ``yaml`` is imported lazily so ``models`` stays import-light."""
    import yaml

    with open(path, encoding="utf-8") as f:
        return Policy.model_validate(yaml.safe_load(f))
