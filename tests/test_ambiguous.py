"""T9: the ambiguous scenario, offline. >= 5 ambiguities pause the run; `answer` yields spec v2 and the
graph's expected downstream; a planted failing test makes validation fail -> diagnose -> re-plan (plan v2 with
previous_version 1) until policy.budgets.max_replans_per_run trips replan.limit_reached."""

from __future__ import annotations

from pathlib import Path

import pytest
from test_fake_llm import settings

from orchestrator.models.state import NodeStatus, RunStatus
from orchestrator.models.trace import Kind
from orchestrator.service import OrchestratorService
from orchestrator.trace.metrics import compute

SPEC_DOWNSTREAM = {
    "planning", "architecture", "impact", "security_review", "risk_analysis", "approval_design",
    "implementation",
    "unit_tests", "code_review", "integration_tests", "validation", "diagnose", "documentation",
    "release_readiness", "approval_release",
}  # fmt: skip
REPLAN_INVALIDATES = {
    "planning",
    "architecture",
    "impact",
    "security_review",
    "risk_analysis",
    "approval_design",
    "implementation",
    "unit_tests",
    "code_review",
    "integration_tests",
    "validation",
}  # fmt: skip  (documentation/release/approval_release never ran, so there is nothing to invalidate)


async def test_ambiguous_clarifies_then_replans_until_the_limit(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    svc = OrchestratorService(settings(tmp_path, target_stack="python"))
    live = await svc.start("ambiguous")
    run_id = live.state.run_id
    st = live.state

    # 1. >= 5 ambiguities -> the run waits for a human
    assert st.status == RunStatus.AWAITING_INPUT and st.nodes["clarify"] == NodeStatus.AWAITING_INPUT
    assert len(st.pending_questions) == 6 and live.ctx.version("spec") == 1
    spec_v1 = live.ctx.get("spec")
    assert svc.graph.invalidated_by_artifacts({"spec"}) == SPEC_DOWNSTREAM

    # 2. answers -> spec v2 (resolved ambiguities become assumptions); nothing downstream had run yet
    answers = {a.id: a.default_if_unanswered for a in spec_v1.ambiguities}
    live = await svc.answer(run_id, answers, "pdp")
    st = live.state
    spec_v2 = live.ctx.get("spec")
    assert live.ctx.version("spec") == 2 and spec_v2.version == 2 and spec_v2.ambiguities == []
    assert all(any(a.startswith(f"{aid}:") for a in spec_v2.assumptions) for aid in answers)
    events = svc.trace.events(run_id)
    replan = next(e for e in events if e.kind == Kind.REPLAN_TRIGGERED)
    assert replan.node_id == "clarify" and replan.payload["changed"] == ["spec"]
    assert [e for e in events if e.kind == Kind.NODE_INVALIDATED] == []
    assert (
        st.status == RunStatus.AWAITING_APPROVAL
        and st.nodes["approval_design"] == NodeStatus.AWAITING_APPROVAL
    )
    assert live.ctx.get("plan").spec_version == 2 and live.ctx.get("plan").previous_version is None

    # 3. implementation plants a failing unit test -> unit_tests records it, validation rolls it up and fails
    live = await svc.approve(run_id, "approval_design", "pdp")
    live = await svc.approve(run_id, "implementation", "pdp")  # the HIGH release task
    st = live.state
    events = svc.trace.events(run_id)
    # unit_tests recorded its failure and PASSED (the roll-up decides); by now the re-plan has invalidated it
    assert any(e.kind == Kind.NODE_PASSED and e.node_id == "unit_tests" for e in events)
    assert st.nodes["unit_tests"] == NodeStatus.INVALIDATED
    unit = live.ctx.get("unit_result")
    assert not unit.passed and [g.gate_id for g in unit.gates if g.status.value == "FAILED"] == ["unit"]
    assert not live.ctx.get("validation_result").passed
    assert Kind.FALLBACK_TAKEN in {e.kind for e in events}

    # 4. diagnose -> replan: plan v2 points at v1, and exactly the nodes that had run are invalidated
    routes = [e for e in events if e.kind == Kind.REPLAN_TRIGGERED and e.node_id == "diagnose"]
    assert routes and routes[0].payload == {"changed": ["plan"], "route": "replan"}
    first_replan_ts = routes[0].ts
    invalidated = {e.node_id for e in events if e.kind == Kind.NODE_INVALIDATED and e.ts >= first_replan_ts}
    assert invalidated >= REPLAN_INVALIDATES and "diagnose" not in invalidated
    assert live.ctx.get("plan").version >= 2
    plan_v2 = next(a for a in live.ctx.artifacts.values() if a.name == "plan")
    assert plan_v2.version >= 2
    assert st.plan_version >= 2

    # a re-planned design is a new design: the earlier approval was revoked, so the run paused again
    revoked = [e for e in events if e.kind == Kind.POLICY_DECISION and e.status == "APPROVAL_REVOKED"]
    assert (
        revoked
        and "approval_design" in revoked[0].payload["revoked"]
        and "task:T4" in revoked[0].payload["revoked"]
    )
    assert (
        st.status == RunStatus.AWAITING_APPROVAL
        and st.nodes["approval_design"] == NodeStatus.AWAITING_APPROVAL
    )

    # 5. keep approving the re-planned design until the re-plan budget trips
    limit = svc.policy.budgets.max_replans_per_run
    rounds = 0
    while st.status == RunStatus.AWAITING_APPROVAL and rounds < 10:
        live = await svc.approve(run_id, "approval_design", "pdp")
        st = live.state
        rounds += 1
    assert st.status == RunStatus.HALTED and st.halt_reason == "replan.limit_reached", (
        st.status,
        st.halt_reason,
    )
    plans = sorted(
        int(p.stem.split(".v")[1]) for p in (tmp_path / "runs" / run_id / "artifacts").glob("plan.v*.json")
    )
    assert plans == list(
        range(1, limit + 2)
    )  # v1 + one per accepted re-plan (the (limit+1)-th halts before planning)
    final_plan = live.ctx.get("plan")
    assert final_plan.version == limit + 1 and final_plan.previous_version == limit
    assert st.budget.replans == limit + 1
    m = compute(run_id, events := svc.trace.events(run_id))
    assert m.replans == limit  # re-plans that happened; the (limit+1)-th attempt halted before it was routed
    assert m.halted and m.halt_reason == "replan.limit_reached"
    assert (
        sum(1 for e in events if e.kind == Kind.APPROVAL_REQUESTED) == 2 + limit
    )  # design x(1+limit) + HIGH task
