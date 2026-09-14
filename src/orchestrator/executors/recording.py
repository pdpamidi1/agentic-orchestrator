"""RecordingExecutor: wraps any CodeExecutor and records every successful task as a patch that
ReplayExecutor can apply later: runs/cache/changesets/<scenario>/<task>.attempt<n>.patch (exact attempt)
plus <task>.patch (latest). Record once with --record (live Claude Code, or the fake executor for an
offline golden run); replay forever with --replay."""

from __future__ import annotations

from pathlib import Path
from typing import Any

from ..engine.context import RunContext
from ..models import Design, TaskSpec
from ..models.trace import Kind
from ..sandbox.git import GitSandbox
from .base import CodeExecutor, Done, ExecResult
from .replay import apply_recorded


class RecordingExecutor:
    def __init__(self, inner: CodeExecutor, cache_dir: Path) -> None:
        self.inner, self.cache_dir = inner, cache_dir

    async def execute(
        self, ctx: RunContext, task: TaskSpec, design: Design, feedback: dict[str, Any] | None
    ) -> ExecResult:
        git = GitSandbox(ctx.sandbox)
        before = await git.head()
        attempt = (feedback or {}).get("attempt", 0) + 1  # same rule ReplayExecutor uses to pick the file
        # record once: a task already recorded for this scenario is replayed instead of re-run live, so a
        # run that died after task N costs only tasks N+1.. next time (policy checks still run on the result)
        patch = self.cache_dir / ctx.scenario / f"{task.id}.attempt{attempt}.patch"  # exact attempt only:
        if patch.exists():  # the task's *latest* patch may be the attempt the policy engine rejected
            reused = await apply_recorded(ctx, task, patch)
            if isinstance(reused, Done):
                ctx.emit(
                    Kind.EXECUTOR_CALL,
                    task_id=task.id,
                    attempt=attempt,
                    actor="recorder",
                    status="REUSED",
                    payload={"patch": patch.name, "commit": reused.commit_sha},
                )
                return reused
            await git.reset_working_tree()  # the patch no longer applies: fall through to the live executor
        result = await self.inner.execute(ctx, task, design, feedback)
        if isinstance(result, Done):
            folder = self.cache_dir / ctx.scenario
            exact = folder / f"{task.id}.attempt{attempt}.patch"
            await git.export_patch(before, exact)
            await git.export_patch(before, folder / f"{task.id}.patch")
            ctx.emit(
                Kind.ARTIFACT_WRITTEN,
                task_id=task.id,
                attempt=attempt,
                actor="recorder",
                payload={"artifact": "changeset_patch", "path": str(exact), "commit": result.commit_sha},
            )
        return result
