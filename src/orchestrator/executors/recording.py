"""RecordingExecutor: wraps any CodeExecutor and records every successful task as a patch that
ReplayExecutor can apply later: runs/cache/changesets/<scenario>/<task>.attempt<n>.patch (exact attempt)
plus <task>.patch (latest). Record once with --record (live Claude Code, or the fake executor for an
offline golden run); replay forever with --replay."""

from __future__ import annotations

import asyncio
import json
from pathlib import Path
from typing import Any

from ..engine.context import RunContext
from ..models import Design, TaskSpec
from ..models.trace import Kind
from ..sandbox.git import GitSandbox
from .base import CodeExecutor, Done, ExecResult
from .replay import apply_recorded, recorded_patch, recorded_scope


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
        patch = recorded_patch(self.cache_dir, ctx.scenario, task.id, attempt)
        if patch is not None:  # exact attempt, else the last policy-accepted patch (never a rejected one)
            scope = (
                recorded_scope(self.cache_dir, ctx.scenario, task.id)
                if patch.name == f"{task.id}.patch"
                else []
            )
            reused = await apply_recorded(ctx, task, patch, scope)
            if isinstance(reused, Done):
                ctx.emit(
                    Kind.EXECUTOR_CALL,
                    task_id=task.id,
                    attempt=attempt,
                    actor="recorder",
                    status="REUSED",
                    payload={"patch": patch.name, "commit": reused.commit_sha, "granted_scope": scope},
                )
                return reused
            await git.reset_working_tree()  # the patch no longer applies: fall through to the live executor
        result = await self.inner.execute(ctx, task, design, feedback)
        if isinstance(result, Done):
            folder = self.cache_dir / ctx.scenario
            exact = folder / f"{task.id}.attempt{attempt}.patch"
            await git.export_patch(before, exact)
            ctx.emit(
                Kind.ARTIFACT_WRITTEN,
                task_id=task.id,
                attempt=attempt,
                actor="recorder",
                payload={"artifact": "changeset_patch", "path": str(exact), "commit": result.commit_sha},
            )
        return result

    async def accepted(self, ctx: RunContext, task: TaskSpec, result: Done, granted: list[str]) -> None:
        """The handler's verdict was OK: this attempt becomes the task's replayable patch, with the scope a
        human granted it."""
        if "replayed" in result.notes:
            return  # a reused recording is already the accepted one
        folder = self.cache_dir / ctx.scenario
        await asyncio.to_thread(_promote, folder, task.id, result.commit_sha, granted)


def _promote(folder: Path, task_id: str, commit_sha: str, granted: list[str]) -> None:
    attempts = sorted(folder.glob(f"{task_id}.attempt*.patch"), key=lambda p: p.stat().st_mtime)
    if attempts:
        (folder / f"{task_id}.patch").write_bytes(attempts[-1].read_bytes())
    sidecar = folder / f"{task_id}.scope.json"
    if granted:
        sidecar.write_text(json.dumps(sorted(granted)), encoding="utf-8")
    elif sidecar.exists():
        sidecar.unlink()
