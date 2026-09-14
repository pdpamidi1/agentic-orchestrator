"""Replay executor: applies pre-recorded patches (runs/cache/changesets/<scenario>/<task_id>.patch).
Record them from a live run with --record; then the whole demo runs without an API key or Claude Code.

Where it sits: chosen by `service.py` under `--replay` (paired with `llm.ReplayClient`). The module-level
helpers (`recorded_patch`, `recorded_scope`, `apply_recorded`) are shared with `RecordingExecutor`, so
record and replay agree on which file belongs to which attempt and on how a patch becomes a commit.

Invariants:
- A replayed task leaves the sandbox exactly as a live executor would: `git apply` + one commit per task
  on the run branch, files changed measured against the pre-task HEAD.
- Attempt files win over the promoted patch, so a recorded first attempt that violated policy is replayed
  as a violation (the demo's `POLICY_DECISION=VIOLATION -> ROLLED_BACK` story is reproducible).
- A missing or non-applying patch is `Errored(transient=False)`: retrying cannot help, the node blocks.
- Human scope grants recorded in `<task>.scope.json` travel with the patch as `Done.granted_scope`, which
  the handler turns back into approvals without pausing.

Files read: `<task>.attempt<n>.patch`, `<task>.patch`, `<task>.scope.json`. Nothing is written except
the git commit; no trace events are emitted here (the handler records the task outcome).
"""

from __future__ import annotations

import asyncio
import json
import os
from pathlib import Path
from typing import Any

from ..engine.context import RunContext
from ..models import Design, TaskSpec
from ..sandbox.git import GitSandbox
from .base import Done, Errored, ExecResult


def recorded_patch(cache_dir: Path, scenario: str, task_id: str, attempt: int) -> Path | None:
    """The exact attempt's patch if recorded, else the task's last policy-accepted patch, else None.

    Looks under `cache_dir/<scenario>/` for `<task_id>.attempt<attempt>.patch` first, then
    `<task_id>.patch` (the promoted copy written by `RecordingExecutor.accepted`). Pure lookup.
    """
    exact = cache_dir / scenario / f"{task_id}.attempt{attempt}.patch"
    accepted = cache_dir / scenario / f"{task_id}.patch"
    return exact if exact.exists() else accepted if accepted.exists() else None


def recorded_scope(cache_dir: Path, scenario: str, task_id: str) -> list[str]:
    """Files a human granted the task beyond its plan (task.scope_change) when it was recorded.

    Reads `<task_id>.scope.json` (a JSON list) if present; an absent sidecar means no extra scope.
    """
    sidecar = cache_dir / scenario / f"{task_id}.scope.json"
    return list(json.loads(sidecar.read_text(encoding="utf-8"))) if sidecar.exists() else []


async def apply_recorded(
    ctx: RunContext, task: TaskSpec, patch: Path, granted_scope: list[str] | None = None
) -> Done | Errored:
    """git apply + one commit per task, exactly like a live executor would leave the sandbox.

    Runs `git apply --whitespace=nowarn <patch>` with the sandbox as cwd, then commits with the standard
    task message. Returns `Errored(transient=False)` when apply fails (conflicting tree, corrupt patch);
    the caller decides whether to reset and fall back (recorder) or block (replay). On success the `Done`
    notes read `replayed <file>` (which `RecordingExecutor.accepted` uses to skip re-promotion),
    `tests_added` is every changed path containing "test", and `granted_scope` is passed through.

    Side effects: one `git apply` subprocess and one commit; no trace events.
    """
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
        granted_scope=list(granted_scope or []),
    )


class ReplayExecutor:
    """CodeExecutor that only ever applies recorded patches; no LLM, no Claude Code, no network.

    Holds the changesets cache root; the scenario folder comes from `ctx.scenario` on each call.
    """

    def __init__(self, cache_dir: Path) -> None:
        """`cache_dir` is the changesets root (`runs/cache/changesets`)."""
        self.cache_dir = cache_dir

    async def execute(
        self, ctx: RunContext, task: TaskSpec, design: Design, feedback: dict[str, Any] | None
    ) -> ExecResult:
        """Apply the patch recorded for this task attempt (or the promoted one) and commit it.

        `design` is unused: the recorded diff already embodies it. Returns `Errored(transient=False)`
        naming the expected `<task>.patch` path when nothing is recorded, so the node blocks with an
        actionable message ("run live once with --record").
        """
        attempt = (feedback or {}).get("attempt", 0) + 1
        patch = recorded_patch(self.cache_dir, ctx.scenario, task.id, attempt)
        if patch is None:
            missing = self.cache_dir / ctx.scenario / f"{task.id}.patch"
            return Errored(f"no recorded patch for {task.id} ({missing})", transient=False)
        return await apply_recorded(ctx, task, patch, recorded_scope(self.cache_dir, ctx.scenario, task.id))
