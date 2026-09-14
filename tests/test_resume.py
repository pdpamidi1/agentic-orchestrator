"""TASKS T12 (resume from store) and the pause-aware wall-clock budget.

A run must survive an API restart: the service rebuilds ``LiveRun`` from ``runs/<id>`` (state, typed
artifacts, ``context.json``) and approving it in the new process continues exactly as the old one would.
Runs saved before ``context.json`` existed are reconstructed from the trace. Time spent waiting for a human
never counts against ``max_wall_clock_minutes``.
"""

from __future__ import annotations

from datetime import timedelta
from pathlib import Path

import pytest
from test_fake_llm import settings
from test_runner import make_handlers, no_sleep, spec

from orchestrator.engine.runner import Runner
from orchestrator.models.plan import Plan
from orchestrator.models.spec import Spec
from orchestrator.models.state import NodeStatus, RunStatus
from orchestrator.models.trace import Kind
from orchestrator.service import OrchestratorService


async def test_run_resumes_in_a_fresh_process(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    first = OrchestratorService(settings(tmp_path, target_stack="python"))
    control = await first.start("greenfield")  # same scenario, driven entirely in-process
    live = await first.start("greenfield")
    run_id = live.state.run_id
    assert (tmp_path / "runs" / run_id / "context.json").exists()

    # a new API process: empty registry
    second = OrchestratorService(settings(tmp_path, target_stack="python"))
    assert run_id not in second.runs and await second.live("never-started") is None
    back = await second.live(run_id)
    assert back is not None and run_id in second.runs
    assert back.state.status == RunStatus.AWAITING_APPROVAL
    assert isinstance(back.ctx.get("spec"), Spec) and isinstance(back.ctx.get("plan"), Plan)
    assert back.ctx.get("requirement_text") == live.ctx.get("requirement_text")
    assert back.ctx.artifacts["spec"].produced_by == "requirement"
    assert back.ctx.lineage_snapshot() == live.ctx.lineage_snapshot()
    assert back.ctx.approvals == live.ctx.approvals and back.ctx.replay is live.ctx.replay
    assert back.ctx.get("approval_brief")["node"] == "approval_design"

    # approving in the new process continues the run exactly like the same process would have
    expected = await first.approve(control.state.run_id, "approval_design", "pdp")
    resumed = await second.approve(run_id, "approval_design", "pdp")
    assert resumed.state.nodes == expected.state.nodes and resumed.state.status == expected.state.status
    assert resumed.state.nodes["approval_design"] == NodeStatus.PASSED
    kinds = [e.kind for e in second.trace.events(run_id)]
    assert kinds.count(Kind.RUN_STARTED) == 2 and Kind.APPROVAL_GRANTED in kinds


async def test_legacy_run_is_rebuilt_from_the_trace(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """A run saved before ``context.json`` existed: approvals, producers and record flag come from events."""
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    first = OrchestratorService(settings(tmp_path, target_stack="python"))
    live = await first.start("greenfield", record=True)
    run_id = live.state.run_id
    live = await first.approve(run_id, "approval_design", "pdp")
    live = await first.approve(run_id, "implementation", "pdp")  # the HIGH task -> task:<id> token
    (tmp_path / "runs" / run_id / "context.json").unlink()

    back = await OrchestratorService(settings(tmp_path, target_stack="python")).live(run_id)
    assert back is not None
    assert back.record is True and back.ctx.replay is False
    assert back.ctx.approvals == live.ctx.approvals
    assert any(a.startswith("task:") for a in back.ctx.approvals)
    assert {n: a.produced_by for n, a in back.ctx.artifacts.items()} == {
        n: a.produced_by for n, a in live.ctx.artifacts.items()
    }
    assert back.ctx.get("changeset").commits == live.ctx.get("changeset").commits
    assert back.ctx.lineage_snapshot() == live.ctx.lineage_snapshot()


async def test_wall_clock_budget_excludes_human_waits(graph, ctx, state, store):  # type: ignore[no-untyped-def]
    hs = make_handlers({"a": [spec_ok()], "b": [plan_ok()], "impl": [impl_ok()]})
    r = Runner(graph, hs, store, sleep=no_sleep)
    st = await r.run(ctx, state)
    assert st.status == RunStatus.AWAITING_APPROVAL and st.budget.paused_at is not None
    # the human took two hours to answer (policy allows 60 active minutes)
    st.budget.started_at -= timedelta(hours=2)
    st.budget.paused_at -= timedelta(hours=2)
    st = await r.approve(ctx, st, "approve", "pdp")
    assert st.status != RunStatus.HALTED and st.budget.paused_seconds > 7000
    assert st.budget.elapsed_minutes() < 60
    st.budget.paused_seconds = 0  # without the credit the same run is over budget
    assert r._budget_trip(st, ctx) == "budget.exceeded:wall_clock"


async def test_resume_after_wall_clock_halt_restarts_the_clock(graph, ctx, state, store):  # type: ignore[no-untyped-def]
    hs = make_handlers({"a": [spec_ok()], "b": [plan_ok()], "impl": [impl_ok()]})
    r = Runner(graph, hs, store, sleep=no_sleep)
    st = await r.run(ctx, state)
    st.budget.started_at -= timedelta(hours=2)  # genuinely over budget on active time
    st.budget.paused_at = None
    st = await r.approve(ctx, st, "approve", "pdp")
    assert st.status == RunStatus.HALTED and st.halt_reason == "budget.exceeded:wall_clock"
    assert st.budget.paused_at is not None  # the review after a safe-stop is human time too
    st = await r.approve(ctx, st, "approve", "pdp")  # reviewer: go on
    assert st.status != RunStatus.HALTED and st.halt_reason is None
    assert st.budget.elapsed_minutes() < 1 and st.budget.paused_seconds == 0
    resumed = [e for e in ctx.trace.events(ctx.run_id) if e.kind == Kind.RUN_RESUMED]
    assert resumed and resumed[-1].payload["after"] == "budget.exceeded:wall_clock"


def spec_ok():  # type: ignore[no-untyped-def]
    from orchestrator.engine.outcomes import Success

    return Success({"spec": spec()})


def plan_ok():  # type: ignore[no-untyped-def]
    from orchestrator.engine.outcomes import Success

    return Success({"plan": "p1"})


def impl_ok():  # type: ignore[no-untyped-def]
    from orchestrator.engine.outcomes import Success

    return Success({"changeset": "cs"})
