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


class RecordingExecutor:
    def __init__(self, inner: CodeExecutor, cache_dir: Path) -> None:
        self.inner, self.cache_dir = inner, cache_dir

    async def execute(
        self, ctx: RunContext, task: TaskSpec, design: Design, feedback: dict[str, Any] | None
    ) -> ExecResult:
        git = GitSandbox(ctx.sandbox)
        before = await git.head()
        result = await self.inner.execute(ctx, task, design, feedback)
        if isinstance(result, Done):
            attempt = (feedback or {}).get("attempt", 0) + 1  # same rule ReplayExecutor uses to pick the file
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
