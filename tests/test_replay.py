"""T7 prep: record once, replay forever, no key. Cache keys ignore run ids; patches round-trip; and a
recorded fake run replays end to end (the offline golden run)."""

from __future__ import annotations

from pathlib import Path
from typing import Any

import pytest
from test_fake_llm import settings

from orchestrator.engine.context import RunContext
from orchestrator.executors.base import Done
from orchestrator.executors.fake import FakeExecutor
from orchestrator.executors.recording import RecordingExecutor
from orchestrator.executors.replay import ReplayExecutor
from orchestrator.llm.client import RecordingClient, ReplayClient, _key
from orchestrator.llm.fake import CANNED, FakeClient
from orchestrator.models import Design, Plan, Spec
from orchestrator.models.state import NodeStatus, RunStatus
from orchestrator.models.trace import Kind
from orchestrator.sandbox.git import GitSandbox
from orchestrator.service import OrchestratorService
from orchestrator.trace.metrics import compute


async def test_cache_key_ignores_run_ids(tmp_path: Path) -> None:
    a = 'Run: greenfield-0123abcd.\n{"run_id": "greenfield-0123abcd", "summary": "s"}'
    b = 'Run: greenfield-ffff9999.\n{"run_id": "greenfield-ffff9999", "summary": "s"}'
    assert _key("sys", a, Spec) == _key("sys", b, Spec)
    assert _key("sys", a, Spec) != _key("sys", a.replace('"s"', '"other"'), Spec)
    spec, _ = await RecordingClient(FakeClient(), tmp_path).structured("sys", a, Spec)
    replayed, _ = await ReplayClient(tmp_path).structured("sys", b, Spec)
    assert replayed == spec


async def test_recorded_patches_replay_into_an_identical_tree(tmp_path: Path, ctx: RunContext) -> None:
    plan, design = Plan.model_validate(CANNED["Plan"]), Design.model_validate(CANNED["Design"])
    cache = tmp_path / "changesets"
    git = GitSandbox(ctx.sandbox)
    await git.ensure_repo()
    rec = RecordingExecutor(FakeExecutor(plant=None), cache)
    for t in plan.tasks[:2]:
        assert isinstance(await rec.execute(ctx, t, design, {"attempt": 1}), Done)
    assert sorted(p.name for p in (cache / ctx.scenario).glob("*")) == [
        "T1.attempt2.patch",
        "T1.patch",
        "T2.attempt2.patch",
        "T2.patch",
    ]
    recorded = sorted(str(p.relative_to(ctx.sandbox)) for p in ctx.sandbox.rglob("*.py"))

    other = RunContext(
        run_id="r2", scenario=ctx.scenario, policy=ctx.policy, sandbox=tmp_path / "sb2", trace=ctx.trace
    )
    await GitSandbox(other.sandbox).ensure_repo()
    replay = ReplayExecutor(cache)
    for t in plan.tasks[:2]:
        r = await replay.execute(other, t, design, {"attempt": 1})
        assert isinstance(r, Done) and r.commit_sha
    assert sorted(str(p.relative_to(other.sandbox)) for p in other.sandbox.rglob("*.py")) == recorded
    assert (other.sandbox / "src/shortener/t1.py").read_text() == (
        ctx.sandbox / "src/shortener/t1.py"
    ).read_text()
    missing = await replay.execute(other, plan.tasks[3], design, None)
    assert not isinstance(missing, Done) and "no recorded patch" in missing.reason  # type: ignore[union-attr]


async def test_offline_golden_run_records_then_replays_to_completion(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    cache = tmp_path / "runs" / "cache"

    rec = OrchestratorService(settings(tmp_path, target_stack="python"))
    live = await rec.start("greenfield", record=True)
    live = await rec.approve(live.state.run_id, "approval_design", "pdp")
    live = await rec.approve(live.state.run_id, "implementation", "pdp")
    assert live.state.nodes["approval_release"] == NodeStatus.AWAITING_APPROVAL
    assert len(list((cache / "llm").glob("*.json"))) == 7  # every agent once, incl. after approvals
    patches = sorted(p.name for p in (cache / "changesets" / "greenfield").glob("*.attempt*.patch"))
    assert patches == [
        "T1.attempt1.patch",
        "T1.attempt2.patch",
        "T2.attempt2.patch",
        "T3.attempt2.patch",
        "T4.attempt2.patch",
    ]
    assert (
        ".env" in (cache / "changesets" / "greenfield" / "T1.attempt1.patch").read_text()
    )  # the planted failure is recorded

    rep = OrchestratorService(settings(tmp_path, llm="auto", target_stack="python"))  # no key -> replay
    replayed = await rep.start("greenfield", replay=True)
    st = replayed.state
    assert st.status == RunStatus.COMPLETED, (st.status, st.halt_reason, st.nodes)
    assert set(st.nodes.values()) == {NodeStatus.PASSED, NodeStatus.SKIPPED}
    events = rep.trace.events(st.run_id)
    kinds = {e.kind for e in events}
    assert Kind.APPROVAL_REQUESTED not in kinds  # replay auto-approves every checkpoint...
    auto = [e for e in events if e.kind == Kind.POLICY_DECISION and e.status == "AUTO_APPROVED"]
    assert {e.node_id for e in auto} == {"approval_design", "approval_release"}  # ...and records that it did
    assert any(
        e.kind == Kind.POLICY_DECISION and e.status == "VIOLATION" for e in events
    )  # planted failure replays too
    m = compute(st.run_id, events)
    assert (m.retry_count, m.rollback_count, m.replans, m.llm_cost_usd, m.human_checkpoints) == (
        1,
        1,
        0,
        0.0,
        0,
    )
    assert m.e2e_latency_seconds is not None and st.nodes["validation"] == NodeStatus.PASSED


async def test_recording_executor_reuses_a_recorded_task(tmp_path: Path, ctx: RunContext) -> None:
    class MustNotRun:
        async def execute(self, *a: Any, **k: Any) -> Done:
            raise AssertionError("live executor called although a recorded patch exists")

    plan, design = Plan.model_validate(CANNED["Plan"]), Design.model_validate(CANNED["Design"])
    cache = tmp_path / "changesets"
    await GitSandbox(ctx.sandbox).ensure_repo()
    first = RecordingExecutor(FakeExecutor(plant=None), cache)
    assert isinstance(await first.execute(ctx, plan.tasks[0], design, None), Done)

    other = RunContext(
        run_id="r2", scenario=ctx.scenario, policy=ctx.policy, sandbox=tmp_path / "sb2", trace=ctx.trace
    )
    await GitSandbox(other.sandbox).ensure_repo()
    reused = await RecordingExecutor(MustNotRun(), cache).execute(other, plan.tasks[0], design, None)
    assert isinstance(reused, Done) and "replayed" in reused.notes
    assert (other.sandbox / "src/shortener/t1.py").exists()
    ev = [e for e in ctx.trace.events("r2") if e.kind == Kind.EXECUTOR_CALL]
    assert ev and ev[-1].status == "REUSED" and ev[-1].payload["patch"] == "T1.attempt1.patch"
