"""T3: the offline loop end to end. FakeExecutor commits per task; the python gates pass on its output."""

from __future__ import annotations

from pathlib import Path

import pytest
from test_fake_llm import settings

from orchestrator.engine.context import RunContext
from orchestrator.executors.base import Done
from orchestrator.executors.fake import FakeExecutor
from orchestrator.llm.fake import CANNED
from orchestrator.models import Design, Plan
from orchestrator.models.state import NodeStatus, RunStatus
from orchestrator.models.trace import Kind
from orchestrator.sandbox.git import GitSandbox
from orchestrator.service import OrchestratorService
from orchestrator.trace.metrics import compute


async def test_fake_executor_writes_module_test_and_release_files(ctx: RunContext) -> None:
    plan, design = Plan.model_validate(CANNED["Plan"]), Design.model_validate(CANNED["Design"])
    git = GitSandbox(ctx.sandbox)
    await git.ensure_repo()
    ex = FakeExecutor(plant=None)

    r1 = await ex.execute(ctx, plan.tasks[0], design, None)
    assert isinstance(r1, Done) and r1.commit_sha
    assert "src/shortener/t1.py" in r1.files_changed and "tests/unit/test_t1.py" in r1.tests_added
    assert "tests/conftest.py" in r1.files_changed  # scaffolding once
    assert not any(f in r1.files_changed for f in ("Dockerfile", "README.md"))  # not in allowed_files

    r2 = await ex.execute(ctx, plan.tasks[1], design, None)
    assert isinstance(r2, Done)
    assert "tests/conftest.py" not in r2.files_changed and "src/shortener/t2.py" in r2.files_changed

    release = next(t for t in plan.tasks if t.requires_approval)
    r4 = await ex.execute(ctx, release, design, None)
    assert isinstance(r4, Done)
    assert set(r4.files_changed) == {"README.md", "openapi.yaml", "Dockerfile", ".github/workflows/ci.yml"}
    assert (ctx.sandbox / "openapi.yaml").read_text() == design.api.openapi_yaml  # contract from the Design
    _, log = await git._git("log", "--oneline")
    assert len(log.splitlines()) == 4  # initial + one commit per task


async def test_fake_executor_plants_a_forbidden_write_on_the_first_attempt_only(ctx: RunContext) -> None:
    plan, design = Plan.model_validate(CANNED["Plan"]), Design.model_validate(CANNED["Design"])
    git = GitSandbox(ctx.sandbox)
    await git.ensure_repo()
    r1 = await FakeExecutor().execute(ctx, plan.tasks[0], design, None)
    assert isinstance(r1, Done) and ".env" in r1.files_changed
    await git.revert(r1.commit_sha)  # what the handler does after the policy engine says VIOLATION
    assert not (ctx.sandbox / ".env").exists() and not (ctx.sandbox / "src").exists()
    r2 = await FakeExecutor().execute(ctx, plan.tasks[0], design, {"attempt": 1, "reason": "violated"})
    assert (
        isinstance(r2, Done) and ".env" not in r2.files_changed and "src/shortener/t1.py" in r2.files_changed
    )
    calls = [e for e in ctx.trace.events("r1") if e.kind == Kind.EXECUTOR_CALL]
    assert [(e.attempt, e.payload["planted_violation"]) for e in calls] == [(1, True), (2, False)]


async def test_sandbox_excludes_tool_output_from_the_diff(tmp_path: Path) -> None:
    git = GitSandbox(tmp_path / "sb")
    await git.ensure_repo()
    (tmp_path / "sb" / "src" / "__pycache__").mkdir(parents=True)
    (tmp_path / "sb" / "src" / "__pycache__" / "x.cpython-313.pyc").write_bytes(b"\x00")
    (tmp_path / "sb" / ".coverage").write_bytes(b"\x00")
    (tmp_path / "sb" / "src" / "real.py").write_text("x = 1\n")
    assert await git.changed_files() == ["src/real.py"]


async def test_greenfield_runs_to_approval_release_offline(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    svc = OrchestratorService(settings(tmp_path, target_stack="python"))
    live = await svc.start("greenfield")
    run_id = live.state.run_id
    assert live.state.nodes["approval_design"] == NodeStatus.AWAITING_APPROVAL

    live = await svc.approve(run_id, "approval_design", "pdp")
    st = live.state
    assert (
        st.status == RunStatus.AWAITING_APPROVAL
        and st.nodes["implementation"] == NodeStatus.AWAITING_APPROVAL
    )
    assert set(live.ctx.get("changeset").commits) == {"T1", "T2", "T3"}  # paused on the HIGH release task

    # the planted failure: attempt 1 wrote .env -> VIOLATION -> revert -> attempt 2 clean
    events = svc.trace.events(run_id)
    violation = next(e for e in events if e.kind == Kind.POLICY_DECISION and e.status == "VIOLATION")
    assert violation.task_id == "T1"
    assert {f["rule"] for f in violation.payload["findings"]} >= {"change_control.forbidden_path"}
    assert {f["file"] for f in violation.payload["findings"] if f["file"]} == {".env"}
    reverted = [e for e in events if e.kind == Kind.ROLLED_BACK]
    assert len(reverted) == 1 and reverted[0].task_id == "T1"
    impl_attempts = [
        e.attempt for e in events if e.kind == Kind.ATTEMPT_STARTED and e.node_id == "implementation"
    ]
    assert impl_attempts == [1, 2]
    failed = next(e for e in events if e.kind == Kind.ATTEMPT_FAILED and e.node_id == "implementation")
    assert failed.attempt == 1
    assert not (live.ctx.sandbox / ".env").exists()
    _, log = await GitSandbox(live.ctx.sandbox)._git("log", "--oneline")
    assert any(line.split(" ", 1)[1].startswith("Revert") for line in log.splitlines())

    live = await svc.approve(run_id, "implementation", "pdp")
    st = live.state
    assert st.status == RunStatus.AWAITING_APPROVAL, (st.status, st.halt_reason)
    assert st.nodes["approval_release"] == NodeStatus.AWAITING_APPROVAL
    for n in (
        "implementation",
        "unit_tests",
        "code_review",
        "integration_tests",
        "validation",
        "documentation",
        "release_readiness",
    ):
        assert st.nodes[n] == NodeStatus.PASSED, (n, st.nodes[n])
    assert st.nodes["diagnose"] == NodeStatus.SKIPPED
    assert live.ctx.get("validation_result").passed
    assert live.ctx.version("changeset") == 1  # no phantom version after the approval pause
    assert set(live.ctx.get("changeset").commits) == {"T1", "T2", "T3", "T4"}
    assert (live.ctx.sandbox / "Dockerfile").exists() and (
        live.ctx.sandbox / ".github/workflows/ci.yml"
    ).exists()

    events = svc.trace.events(run_id)
    decisions = [e for e in events if e.kind == Kind.POLICY_DECISION]
    assert {e.status for e in decisions} == {"VIOLATION", "OK", "APPROVED"}
    t4 = next(e for e in decisions if e.task_id == "T4" and e.status == "OK")
    assert t4.payload["approved_actions"] == ["infrastructure.change", "release.config"]
    gates = {(e.actor, e.status) for e in events if e.kind == Kind.GATE_RESULT}
    assert {
        ("compile", "PASSED"),
        ("unit", "PASSED"),
        ("integration", "PASSED"),
        ("release", "PASSED"),
    } <= gates
    assert all(s != "FAILED" for _, s in gates)
    m = compute(run_id, events)
    assert m.replans == 0 and m.human_checkpoints == 3
    assert m.retry_count == 1 and m.rollback_count == 1 and m.task_success_rate == 1.0
    assert m.mttr_seconds is not None and m.mttr_seconds >= 0
