"""Replay executor: applies pre-recorded patches (runs/cache/changesets/<scenario>/<task_id>.patch).
Record them from a live run with --record; then the whole demo runs without an API key or Claude Code."""

from __future__ import annotations

import asyncio
import os
from pathlib import Path
from typing import Any

from ..engine.context import RunContext
from ..models import Design, TaskSpec
from ..sandbox.git import GitSandbox
from .base import Done, Errored, ExecResult


def recorded_patch(cache_dir: Path, scenario: str, task_id: str, attempt: int) -> Path | None:
    """The exact attempt's patch if recorded, else the task's latest, else None."""
    exact = cache_dir / scenario / f"{task_id}.attempt{attempt}.patch"
    latest = cache_dir / scenario / f"{task_id}.patch"
    return exact if exact.exists() else latest if latest.exists() else None


async def apply_recorded(ctx: RunContext, task: TaskSpec, patch: Path) -> Done | Errored:
    """git apply + one commit per task, exactly like a live executor would leave the sandbox."""
    git = GitSandbox(ctx.sandbox)
    base = await git.head()
    # git runs inside the sandbox: a cache path relative to the orchestrator root must be made absolute
    absolute = await asyncio.to_thread(os.path.abspath, patch)
    proc = await asyncio.create_subprocess_exec(
        "git", "apply", "--whitespace=nowarn", absolute, cwd=str(ctx.sandbox)
    )
    await proc.communicate()
    if proc.returncode != 0:
        return Errored(f"git apply failed for {patch.name}", transient=False)
    changed = await git.changed_files(base)
    sha = await git.commit_task(task.id, task.title, ctx.run_id)
    return Done(
        files_changed=changed,
        tests_added=[f for f in changed if "test" in f.lower()],
        commit_sha=sha,
        notes=f"replayed {patch.name}",
    )


class ReplayExecutor:
    def __init__(self, cache_dir: Path) -> None:
        self.cache_dir = cache_dir

    async def execute(
        self, ctx: RunContext, task: TaskSpec, design: Design, feedback: dict[str, Any] | None
    ) -> ExecResult:
        attempt = (feedback or {}).get("attempt", 0) + 1
        patch = recorded_patch(self.cache_dir, ctx.scenario, task.id, attempt)
        if patch is None:
            missing = self.cache_dir / ctx.scenario / f"{task.id}.patch"
            return Errored(f"no recorded patch for {task.id} ({missing})", transient=False)
        return await apply_recorded(ctx, task, patch)
