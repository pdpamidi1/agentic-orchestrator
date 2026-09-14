"""Replay executor: applies pre-recorded patches (runs/cache/changesets/<scenario>/<task_id>.patch).
Record them from a live run with --record; then the whole demo runs without an API key or Claude Code."""

from __future__ import annotations

import asyncio
from pathlib import Path
from typing import Any

from ..engine.context import RunContext
from ..models import Design, TaskSpec
from ..sandbox.git import GitSandbox
from .base import Done, Errored, ExecResult


class ReplayExecutor:
    def __init__(self, cache_dir: Path) -> None:
        self.cache_dir = cache_dir

    async def execute(
        self, ctx: RunContext, task: TaskSpec, design: Design, feedback: dict[str, Any] | None
    ) -> ExecResult:
        attempt = (feedback or {}).get("attempt", 0) + 1
        patch = self.cache_dir / ctx.scenario / f"{task.id}.attempt{attempt}.patch"
        if not patch.exists():
            patch = self.cache_dir / ctx.scenario / f"{task.id}.patch"
        if not patch.exists():
            return Errored(f"no recorded patch for {task.id} ({patch})", transient=False)
        git = GitSandbox(ctx.sandbox)
        base = await git.head()
        proc = await asyncio.create_subprocess_exec(
            "git", "apply", "--whitespace=nowarn", str(patch), cwd=str(ctx.sandbox)
        )
        await proc.communicate()
        if proc.returncode != 0:
            return Errored(f"git apply failed for {patch.name}", transient=False)
        changed = await git.changed_files(base)
        sha = await git.commit_task(task.id, task.title, ctx.run_id)
        return Done(
            files_changed=changed, tests_added=[f for f in changed if "test" in f.lower()], commit_sha=sha
        )
