"""RecordingExecutor: wraps any CodeExecutor and records every successful task as a patch that
ReplayExecutor can apply later: runs/cache/changesets/<scenario>/<task>.attempt<n>.patch (exact attempt)
plus <task>.patch (latest). Record once with --record (live Claude Code, or the fake executor for an
offline golden run); replay forever with --replay.

Where it sits: `service.py` wraps the live (or fake) executor in this class under `--record`; the
implementation handler calls `execute()` per task attempt and, once the policy engine has accepted the
commit, `accepted()` to promote that attempt to the task's replayable patch.

Record-once semantics: before running the inner executor, an already recorded patch for this scenario and
task is applied instead (exact attempt file first, else the promoted `<task>.patch`), so a recording run
that died after task N only pays for tasks N+1.. on the next run. Policy checks still run on the reused
commit, so a reused patch is never trusted blindly. A patch that no longer applies is discarded (working
tree reset) and the inner executor runs live.

Files written under `runs/cache/changesets/<scenario>/`:
- `<task>.attempt<n>.patch`  git diff of the sandbox from the pre-task HEAD, one per successful attempt;
- `<task>.patch`             copy of the most recent attempt patch, written by `accepted()` (promotion);
- `<task>.scope.json`        sorted list of files a human granted via `task.scope_change`; removed when
                             the accepted attempt needed no extra scope.

Trace events: `EXECUTOR_CALL` with `status="REUSED"` (actor `recorder`) when a recorded patch is applied,
`ARTIFACT_WRITTEN` (`artifact="changeset_patch"`) when an attempt patch is exported. Events from the inner
executor are emitted as usual when it runs.
"""

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
    """Decorator around a `CodeExecutor` that reuses recorded patches and records new ones.

    Holds the inner executor and the changesets cache root; per-scenario folders are created on first
    export by `GitSandbox.export_patch`. Stateless across calls otherwise.
    """

    def __init__(self, inner: CodeExecutor, cache_dir: Path) -> None:
        """Wrap `inner`; `cache_dir` is the changesets root (`runs/cache/changesets`)."""
        self.inner, self.cache_dir = inner, cache_dir

    async def execute(
        self, ctx: RunContext, task: TaskSpec, design: Design, feedback: dict[str, Any] | None
    ) -> ExecResult:
        """Replay a recorded patch for this task attempt if one exists, otherwise run `inner` and record it.

        Attempt numbering follows the executor convention (`feedback["attempt"] + 1`) so the file picked
        here is the same one `ReplayExecutor` would pick. When the exact attempt patch is missing but the
        promoted `<task>.patch` exists, its `.scope.json` sidecar is replayed with it so the handler can
        re-grant the same files without a human. Returns the reused `Done` (emitting
        `EXECUTOR_CALL/REUSED`), or whatever `inner` returns; on an inner `Done` the diff since the pre-task
        HEAD is exported to `<task>.attempt<n>.patch` and `ARTIFACT_WRITTEN` is emitted.

        Side effects: git apply/commit (via `apply_recorded`), `reset_working_tree` when a stale patch
        fails to apply, patch file export, trace events. Never raises for a stale recording.
        """
        git = GitSandbox(ctx.sandbox)
        before = await git.head()
        attempt = (feedback or {}).get("attempt", 0) + 1  # same rule ReplayExecutor uses to pick the file
        # record once: a task already recorded for this scenario is replayed instead of re-run live, so a
        # run that died after task N costs only tasks N+1.. next time (policy checks still run on the result)
        patch = recorded_patch(self.cache_dir, ctx.scenario, task.id, attempt)
        if patch is not None:  # exact attempt, else the last policy-accepted patch (never a rejected one)
            # only the promoted patch has a scope sidecar; an exact attempt file is replayed as recorded
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
            # BlockedTask/Errored leave nothing worth replaying; only a committed attempt is exported
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
        human granted it.

        Called by the implementation handler after the policy engine accepted the task commit. A `Done`
        whose notes say it was replayed is skipped (the recording it came from is already the accepted
        one). Otherwise `_promote` runs in a worker thread: newest attempt patch -> `<task>.patch`, and
        `granted` -> `<task>.scope.json` (or the sidecar is removed when `granted` is empty).
        """
        if "replayed" in result.notes:
            return  # a reused recording is already the accepted one
        folder = self.cache_dir / ctx.scenario
        await asyncio.to_thread(_promote, folder, task.id, result.commit_sha, granted)


def _promote(folder: Path, task_id: str, commit_sha: str, granted: list[str]) -> None:
    """Copy the most recently written `<task>.attempt*.patch` to `<task>.patch` and sync the scope sidecar.

    "Most recent" is by file mtime, not attempt number, so a re-recorded lower attempt still wins.
    `commit_sha` is accepted for symmetry with the caller but not used. The sidecar is written as a sorted
    JSON list when `granted` is non-empty and deleted otherwise, so a replay never re-grants stale scope.
    """
    attempts = sorted(folder.glob(f"{task_id}.attempt*.patch"), key=lambda p: p.stat().st_mtime)
    if attempts:
        (folder / f"{task_id}.patch").write_bytes(attempts[-1].read_bytes())
    sidecar = folder / f"{task_id}.scope.json"
    if granted:
        sidecar.write_text(json.dumps(sorted(granted)), encoding="utf-8")
    elif sidecar.exists():
        sidecar.unlink()
