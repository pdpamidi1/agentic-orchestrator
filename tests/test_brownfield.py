"""T8: brownfield end to end, offline. Seeded workspace -> repo_map -> impact runs -> two HIGH task approvals
(schema.migration, dependency.major_version) -> compliance catches a raw-IP field on attempt 1 -> release
approval; then the recorded run replays to completion."""

from __future__ import annotations

from pathlib import Path

import pytest
from test_fake_llm import settings

from orchestrator.models.state import NodeStatus, RunStatus
from orchestrator.models.trace import Kind
from orchestrator.service import OrchestratorService
from orchestrator.trace.metrics import compute

FIXTURE = Path(__file__).resolve().parent / "fixtures" / "brownfield_ws"


async def test_brownfield_offline_loop_then_replay(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    svc = OrchestratorService(settings(tmp_path, target_stack="python", workspace=FIXTURE))
    live = await svc.start("brownfield", record=True, workspace=FIXTURE)
    run_id = live.state.run_id
    st = live.state

    # intake: the workspace is seeded and mapped; the impact node ran on the map
    rm = live.ctx.get("repo_map")
    assert rm is not None and rm.tables == ["urls"] and rm.imports["shortener.api"] == ["shortener.service"]
    assert (live.ctx.sandbox / "src" / "shortener" / "api" / "__init__.py").exists()
    assert st.nodes["impact"] == NodeStatus.PASSED and live.ctx.get("impact").new_packages == [
        "shortener.analytics"
    ]
    assert (
        st.status == RunStatus.AWAITING_APPROVAL
        and st.nodes["approval_design"] == NodeStatus.AWAITING_APPROVAL
    )
    assert "shortener.analytics" in str(live.ctx.get("impact")) and "repo_map" in {
        e.payload.get("artifact") for e in svc.trace.events(run_id) if e.kind == Kind.ARTIFACT_WRITTEN
    }

    # two high-impact task approvals, in plan order
    live = await svc.approve(run_id, "approval_design", "pdp")
    requests = [e for e in svc.trace.events(run_id) if e.kind == Kind.APPROVAL_REQUESTED]
    assert (
        requests[-1].payload["action"] == "task.high_impact"
        and requests[-1].payload["summary"]["task"] == "B1"
    )
    live = await svc.approve(run_id, "implementation", "pdp")
    requests = [e for e in svc.trace.events(run_id) if e.kind == Kind.APPROVAL_REQUESTED]
    assert requests[-1].payload["summary"]["task"] == "B2"
    live = await svc.approve(run_id, "implementation", "pdp")
    st = live.state
    assert st.status == RunStatus.AWAITING_APPROVAL, (st.status, st.halt_reason, st.nodes)
    assert st.nodes["approval_release"] == NodeStatus.AWAITING_APPROVAL
    assert set(st.nodes.values()) == {NodeStatus.PASSED, NodeStatus.SKIPPED, NodeStatus.AWAITING_APPROVAL}

    events = svc.trace.events(run_id)
    decisions = [e for e in events if e.kind == Kind.POLICY_DECISION]
    approved = {e.task_id: e.payload["approved_actions"] for e in decisions if e.status == "OK" and e.task_id}
    assert approved["B1"] == ["schema.migration"] and approved["B2"] == ["dependency.major_version"]
    assert approved["B3"] == [] and approved["B4"] == []
    violations = [e for e in decisions if e.status == "VIOLATION"]
    assert len(violations) == 1 and violations[0].task_id == "B1"
    assert violations[0].payload["rules"] == [
        "compliance.pii"
    ]  # only the raw IP: the migration path was approved
    assert {f["file"] for f in violations[0].payload["findings"]} == {"alembic/versions/002_click_events.py"}
    assert sum(1 for e in events if e.kind == Kind.ROLLED_BACK) == 1
    assert "raw_ip_address" not in (live.ctx.sandbox / "alembic/versions/002_click_events.py").read_text()
    toml = (live.ctx.sandbox / "pyproject.toml").read_text()
    assert toml.startswith("[project]") and toml.count("appended by the fake executor") == 1  # extended, once
    gates = {(e.actor, e.status) for e in events if e.kind == Kind.GATE_RESULT}
    assert {
        ("scope", "PASSED"),
        ("security", "PASSED"),
        ("contract", "PASSED"),
        ("release", "PASSED"),
    } <= gates
    m = compute(run_id, events)
    assert (m.human_checkpoints, m.retry_count, m.rollback_count, m.replans) == (
        4,
        1,
        1,
        0,
    )  # design, B1, B2, release

    # the recorded run replays to completion with no key, approvals auto-granted
    rep = OrchestratorService(settings(tmp_path, llm="auto", target_stack="python", workspace=FIXTURE))
    replayed = await rep.start("brownfield", replay=True, workspace=FIXTURE)
    assert replayed.state.status == RunStatus.COMPLETED, (replayed.state.halt_reason, replayed.state.nodes)
    rm2 = compute(replayed.state.run_id, rep.trace.events(replayed.state.run_id))
    assert (rm2.retry_count, rm2.rollback_count, rm2.llm_cost_usd, rm2.human_checkpoints) == (1, 1, 0.0, 0)
