"""Executor handler: tasks in one parallel group share a working tree, so they must run one at a time and each
result is judged before the next starts; revert is conflict-safe."""

from __future__ import annotations

import asyncio
from pathlib import Path
from typing import Any

import pytest

from orchestrator.engine.context import RunContext
from orchestrator.engine.graph import Graph
from orchestrator.engine.handlers import build_handlers
from orchestrator.engine.outcomes import Blocked, Retry, Success
from orchestrator.executors.base import Done
from orchestrator.llm.fake import CANNED, FakeClient
from orchestrator.models import Design, Plan, TaskSpec, load_policy
from orchestrator.models.trace import Kind
from orchestrator.sandbox.git import GitSandbox
from orchestrator.trace.sink import InMemorySink

REPO = Path(__file__).resolve().parent.parent
POLICY = load_policy(str(REPO / "policy.yaml"))


class OverlapDetector:
    """Writes one file per task, slowly, and records how many tasks were inside execute() at once."""

    def __init__(self, violate: str | None = None) -> None:
        self.active = 0
        self.max_active = 0
        self.order: list[str] = []
        self.violate = violate

    async def execute(
        self, ctx: RunContext, task: TaskSpec, design: Design, feedback: dict[str, Any] | None
    ) -> Done:
        self.active += 1
        self.max_active = max(self.max_active, self.active)
        self.order.append(task.id)
        await asyncio.sleep(0.05)
        git = GitSandbox(ctx.sandbox)
        rel = ".env" if task.id == self.violate else f"src/{task.id.lower()}.py"
        (ctx.sandbox / rel).parent.mkdir(parents=True, exist_ok=True)
        (ctx.sandbox / rel).write_text(f"# {task.id}\n")
        (ctx.sandbox / "tests").mkdir(exist_ok=True)
        (ctx.sandbox / "tests" / f"test_{task.id.lower()}.py").write_text("def test_x() -> None:\n    pass\n")
        changed = await git.changed_files(await git.head())
        sha = await git.commit_task(task.id, task.title, ctx.run_id)
        self.active -= 1
        return Done(files_changed=changed, tests_added=[], commit_sha=sha)


def plan_with_group() -> Plan:
    tasks = [
        {"id": f"G{i}", "title": f"task {i}", "parallel_group": "g", "allowed_files": ["src/**", "tests/**"],
         "definition_of_done": ["done"]}
        for i in (1, 2, 3)
    ]  # fmt: skip
    return Plan.model_validate({"run_id": "r1", "spec_version": 1, "tasks": tasks})


async def run_handler(tmp_path: Path, ex: OverlapDetector) -> tuple[Any, RunContext]:
    ctx = RunContext(
        run_id="r1",
        scenario="t",
        policy=POLICY,
        sandbox=tmp_path / "sb",
        trace=InMemorySink(),
        target_stack="python",
    )
    ctx.put("plan", plan_with_group(), "planning")
    ctx.put("design", Design.model_validate(CANNED["Design"]), "architecture")
    graph = Graph.load(REPO / "workflow.yaml")
    handlers = build_handlers(FakeClient(), ex, graph)
    return await handlers["executor"](graph.nodes["implementation"], ctx), ctx


async def test_parallel_group_members_never_overlap_in_the_shared_tree(tmp_path: Path) -> None:
    ex = OverlapDetector()
    out, ctx = await run_handler(tmp_path, ex)
    assert isinstance(out, Success) and ex.max_active == 1 and ex.order == ["G1", "G2", "G3"]
    assert set(out.artifacts["changeset"].commits) == {"G1", "G2", "G3"}
    decisions = [e for e in ctx.trace.events("r1") if e.kind == Kind.POLICY_DECISION]
    assert [e.status for e in decisions] == ["OK", "OK", "OK"]


async def test_a_violation_stops_the_group_before_the_next_task_runs(tmp_path: Path) -> None:
    ex = OverlapDetector(violate="G2")
    out, ctx = await run_handler(tmp_path, ex)
    assert isinstance(out, Retry) and "G2" in out.reason
    assert ex.order == ["G1", "G2"]  # G3 never started
    git = GitSandbox(ctx.sandbox)
    _, log = await git._git("log", "--oneline")
    assert (
        "Revert" in log and not (ctx.sandbox / ".env").exists() and (ctx.sandbox / "src" / "g1.py").exists()
    )
    assert await git.changed_files() == []  # clean tree after the revert


async def test_revert_is_conflict_safe(tmp_path: Path) -> None:
    git = GitSandbox(tmp_path / "sb")
    await git.ensure_repo()
    (tmp_path / "sb" / "a.txt").write_text("one\n")
    a = await git.commit_task("A", "a", "r")
    (tmp_path / "sb" / "a.txt").write_text("two\n")
    b = await git.commit_task("B", "b", "r")
    with pytest.raises(RuntimeError, match="not HEAD"):
        await git.revert(a)  # B changed the same file: conflict, aborted, tree untouched
    assert await git.changed_files() == [] and (tmp_path / "sb" / "a.txt").read_text() == "two\n"
    await git.revert(b)  # HEAD: plain revert
    assert (tmp_path / "sb" / "a.txt").read_text() == "one\n" and await git.head() != b


class AlwaysViolates(OverlapDetector):
    """Every task writes .env: the same task must be retried per task and then block the node."""

    def __init__(self) -> None:
        super().__init__(violate="G1")


async def test_a_task_that_keeps_failing_blocks_after_its_own_allowance(tmp_path: Path) -> None:
    ex = AlwaysViolates()
    ctx = RunContext(
        run_id="r1",
        scenario="t",
        policy=POLICY,
        sandbox=tmp_path / "sb",
        trace=InMemorySink(),
        target_stack="python",
    )
    ctx.put("plan", plan_with_group(), "planning")
    ctx.put("design", Design.model_validate(CANNED["Design"]), "architecture")
    graph = Graph.load(REPO / "workflow.yaml")
    handler = build_handlers(FakeClient(), ex, graph)["executor"]
    node = graph.nodes["implementation"]
    allowance = POLICY.budgets.max_attempts_per_task
    outcomes = []
    for _ in range(allowance):
        out = await handler(node, ctx)
        outcomes.append(out)
        if isinstance(out, Retry):  # what the runner does between attempts
            ctx.feedback[node.id] = dict(out.feedback, reason=out.reason, attempt=len(outcomes))
    assert all(isinstance(o, Retry) for o in outcomes[:-1]) and isinstance(outcomes[-1], Blocked)
    assert outcomes[0].feedback["task_attempts"] == {"G1": 1} and "G1" in outcomes[-1].reason
    assert ex.order == ["G1"] * allowance  # only the failing task was retried; G2/G3 never started


class BlocksThenNeedsScope:
    """G2 reports BLOCKED naming an extra file; once granted it writes it and succeeds."""

    def __init__(self) -> None:
        self.seen_allowed: list[list[str]] = []

    async def execute(
        self, ctx: RunContext, task: TaskSpec, design: Design, feedback: dict[str, Any] | None
    ) -> Any:
        from orchestrator.executors.base import BlockedTask

        git = GitSandbox(ctx.sandbox)
        self.seen_allowed.append(list(task.allowed_files))
        files = [f"src/{task.id.lower()}.py", f"tests/test_{task.id.lower()}.py"]
        if task.id == "G2":
            if "src/shared/util.py" not in task.allowed_files:
                return BlockedTask("needs src/shared/util.py (helper used by G2) and .env to configure it")
            files.append("src/shared/util.py")
        for rel in files:
            (ctx.sandbox / rel).parent.mkdir(parents=True, exist_ok=True)
            (ctx.sandbox / rel).write_text(f"# {task.id}\n")
        changed = await git.changed_files(await git.head())
        sha = await git.commit_task(task.id, task.title, ctx.run_id)
        return Done(files_changed=changed, tests_added=[], commit_sha=sha)


async def test_blocked_task_asks_for_scope_and_continues_once_approved(tmp_path: Path) -> None:
    from orchestrator.engine.outcomes import NeedsApproval

    ex = BlocksThenNeedsScope()
    ctx = RunContext(
        run_id="r1",
        scenario="t",
        policy=POLICY,
        sandbox=tmp_path / "sb",
        trace=InMemorySink(),
        target_stack="python",
    )
    concrete = [  # like a real plan: exact files per task, so a helper elsewhere is genuinely out of scope
        {"id": f"G{i}", "title": f"task {i}", "parallel_group": "g", "definition_of_done": ["done"],
         "allowed_files": [f"src/g{i}.py", f"tests/test_g{i}.py"]}
        for i in (1, 2, 3)
    ]  # fmt: skip
    ctx.put("plan", Plan.model_validate({"run_id": "r1", "spec_version": 1, "tasks": concrete}), "planning")
    ctx.put("design", Design.model_validate(CANNED["Design"]), "architecture")
    graph = Graph.load(REPO / "workflow.yaml")
    handler = build_handlers(FakeClient(), ex, graph)["executor"]
    node = graph.nodes["implementation"]

    out = await handler(node, ctx)
    assert isinstance(out, NeedsApproval) and out.action == "task.scope_change", out
    assert out.summary["task"] == "G2" and out.summary["files"] == [
        "src/shared/util.py"
    ]  # .env is forbidden: never offered
    assert set(ctx.get("changeset").commits) == {"G1"}
    requested = [
        e for e in ctx.trace.events("r1") if e.kind == Kind.POLICY_DECISION and e.status == "SCOPE_REQUESTED"
    ]
    assert requested and requested[0].payload["files"] == ["src/shared/util.py"]

    ctx.approvals.add(node.id)  # what Runner.approve does
    out = await handler(node, ctx)
    assert isinstance(out, Success), out
    assert "src/shared/util.py" in ex.seen_allowed[-2]  # G2 re-ran with the widened list
    assert "scope:G2:src/shared/util.py" in ctx.approvals and node.id not in ctx.approvals
    statuses = [e.status for e in ctx.trace.events("r1") if e.kind == Kind.POLICY_DECISION]
    assert "SCOPE_APPROVED" in statuses and statuses.count("OK") == 3 and "VIOLATION" not in statuses
    assert (ctx.sandbox / "src" / "shared" / "util.py").exists()
    assert (
        ctx.feedback[node.id]["scope_change"]["task"] == "G2" and "scope_request" not in ctx.feedback[node.id]
    )


class ReplaysWithGrant(OverlapDetector):
    """Pretends to be a recording executor replaying a task whose extra file a human granted earlier."""

    async def execute(
        self, ctx: RunContext, task: TaskSpec, design: Design, feedback: dict[str, Any] | None
    ) -> Done:
        done = await super().execute(ctx, task, design, feedback)
        if task.id == "G2":
            (ctx.sandbox / "src" / "shared").mkdir(parents=True, exist_ok=True)
            (ctx.sandbox / "src" / "shared" / "util.py").write_text("# granted\n")
            git = GitSandbox(ctx.sandbox)
            changed = await git.changed_files(f"{done.commit_sha}~1")
            sha = await git.commit_task(task.id, task.title, ctx.run_id)
            return Done(files_changed=changed, tests_added=[], commit_sha=sha, notes="replayed G2.patch",
                        granted_scope=["src/shared/util.py"])  # fmt: skip
        return done


async def test_recorded_scope_grant_is_honoured_on_replay(tmp_path: Path) -> None:
    ex = ReplaysWithGrant()
    ctx = RunContext(
        run_id="r1",
        scenario="t",
        policy=POLICY,
        sandbox=tmp_path / "sb",
        trace=InMemorySink(),
        target_stack="python",
    )
    concrete = [
        {"id": f"G{i}", "title": f"task {i}", "parallel_group": "g", "definition_of_done": ["done"],
         "allowed_files": [f"src/g{i}.py", f"tests/test_g{i}.py"]}
        for i in (1, 2, 3)
    ]  # fmt: skip
    ctx.put("plan", Plan.model_validate({"run_id": "r1", "spec_version": 1, "tasks": concrete}), "planning")
    ctx.put("design", Design.model_validate(CANNED["Design"]), "architecture")
    graph = Graph.load(REPO / "workflow.yaml")
    out = await build_handlers(FakeClient(), ex, graph)["executor"](graph.nodes["implementation"], ctx)
    assert isinstance(out, Success), out
    statuses = [e.status for e in ctx.trace.events("r1") if e.kind == Kind.POLICY_DECISION]
    assert "SCOPE_APPROVED" in statuses and "VIOLATION" not in statuses and statuses.count("OK") == 3
    assert "scope:G2:src/shared/util.py" in ctx.approvals
