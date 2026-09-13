from __future__ import annotations

from pathlib import Path

import pytest

from orchestrator.engine.context import RunContext
from orchestrator.engine.graph import Graph
from orchestrator.models import RunState
from orchestrator.models.policy import Policy
from orchestrator.trace.sink import InMemorySink

POLICY = {
    "version": 1,
    "autonomy": {
        "default_level": "propose_and_execute",
        "high_impact_actions": ["plan.approve", "release.merge"],
        "auto_approve_in_replay": True,
    },
    "budgets": {
        "max_tokens_per_run": 1000,
        "max_cost_usd_per_run": 1.0,
        "max_wall_clock_minutes": 60,
        "max_attempts_per_task": 3,
        "max_replans_per_run": 1,
        "node_timeout_seconds": 5,
        "parallelism": 4,
    },
    "retries": {"initial_delay_seconds": 0, "max_delay_seconds": 0, "on_exhausted": "fallback_or_rollback"},
    "change_control": {
        "allowed_paths": ["src/**", "tests/**"],
        "protected_paths": ["**/db/migration/**", "pom.xml"],
        "forbidden_paths": [".env*", ".git/**"],
        "max_files_changed_per_task": 5,
        "max_lines_changed_per_task": 100,
        "require_tests_for": ["src/**"],
    },
    "sandbox": {"allowed_commands": ["pytest", "git"], "forbidden_commands": ["rm -rf", "curl"]},
    "security": {
        "secret_patterns": ["AKIA[0-9A-Z]{16}"],
        "banned_code_patterns": {"python": ["\\beval\\("]},
        "dependency_allowlist": {"python": ["fastapi"]},
    },
    "compliance": {"pii": {"forbidden_in_persistence": ["raw_ip_address"]}},
    "gates": {"scope": {"required": True, "cmd": "internal:diff_scope"}},
    "claude_code": {
        "permission_mode": "acceptEdits",
        "max_turns": 5,
        "allowed_tools": [],
        "disallowed_tools": [],
        "system_prompt_file": "x",
    },
}

WORKFLOW = """
version: 1
entry: a
nodes:
  a: {kind: agent, agent: x, depends_on: [], produces: [spec]}
  clarify: {kind: input, depends_on: [a], when: "spec.ambiguities is non-empty", produces: [spec]}
  b: {kind: agent, agent: x, depends_on: [clarify], produces: [plan], invalidated_by: [spec]}
  c1: {kind: agent, agent: x, depends_on: [b], parallel_group: g, invalidated_by: [plan]}
  c2: {kind: agent, agent: x, depends_on: [b], parallel_group: g, invalidated_by: [plan]}
  approve: {kind: approval, high_impact: plan.approve, depends_on: [c1, c2]}
  impl: {kind: executor, depends_on: [approve], produces: [changeset], invalidated_by: [plan]}
  validate: {kind: gate, gates: [scope], depends_on: [impl], fallback: diagnose, invalidated_by: [changeset]}
  diagnose:
    kind: agent
    agent: diagnoser
    depends_on: [validate]
    on_result:
      retry: {invalidate: [changeset]}
      replan: {invalidate: [plan]}
      halt: {safe_stop: diagnose.unrecoverable}
  release: {kind: approval, high_impact: release.merge, depends_on: [validate]}
"""


@pytest.fixture
def policy() -> Policy:
    return Policy.model_validate(POLICY)


@pytest.fixture
def graph(tmp_path: Path) -> Graph:
    p = tmp_path / "workflow.yaml"
    p.write_text(WORKFLOW)
    return Graph.load(p)


@pytest.fixture
def ctx(policy: Policy, tmp_path: Path) -> RunContext:
    return RunContext(
        run_id="r1",
        scenario="test",
        policy=policy,
        sandbox=tmp_path / "sb",
        trace=InMemorySink(),
        target_stack="python",
        replay=False,
    )


@pytest.fixture
def state(graph: Graph) -> RunState:
    return RunState(run_id="r1", scenario="test", nodes=graph.initial_statuses())


class MemStore:
    def __init__(self) -> None:
        self.saved: list[RunState] = []

    async def save(self, state: RunState) -> None:
        self.saved.append(state.model_copy(deep=True))

    async def load(self, run_id: str) -> RunState:
        return self.saved[-1]


@pytest.fixture
def store() -> MemStore:
    return MemStore()
