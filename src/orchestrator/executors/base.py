"""
Executor contract: the result types every code executor returns and the `CodeExecutor` protocol.

Where it sits: the implementation node (`engine/handlers.py#executor_handler`) walks the `Plan`'s task DAG and
calls `CodeExecutor.execute()` once per task attempt. The executor works inside the run's git sandbox and
leaves exactly one commit per task on the run branch; the handler then runs the policy engine over the
committed files and maps the `ExecResult` onto an engine `Outcome`:

- `Done`        -> the task commit is policy-checked (scope + scan); a violation reverts it (`ROLLED_BACK`)
                   and the task retries with structured feedback.
- `BlockedTask` -> if the reason names files outside `allowed_files` that policy classifies as allowed or
                   protected, the node pauses for a `task.scope_change` approval (`POLICY_DECISION=
                   SCOPE_REQUESTED`); otherwise the attempt fails and the task retries.
- `Errored`     -> `transient=True` counts as a failed attempt (retry with backoff); `transient=False`
                   blocks the node outright (no retry can help, e.g. missing binary or missing patch).

Implementations: `claude_code_cli.py` (primary), `fake.py` (offline), `replay.py` (recorded patches),
`recording.py` (wraps another executor and records patches). This module is a leaf: it emits no trace
events and writes no files.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Protocol

from ..engine.context import RunContext
from ..models import Design, TaskSpec


@dataclass(frozen=True)
class Done:
    """A task attempt that produced a commit on the run branch.

    Returned only after `GitSandbox.commit_task` succeeded, so `commit_sha` always points at a real commit
    the handler can revert. Token/cost fields are zero for executors that do not call an LLM (fake, replay).
    """

    files_changed: list[str]
    tests_added: list[str]
    commit_sha: str
    tokens_in: int = 0
    tokens_out: int = 0
    cost_usd: float = 0.0
    notes: str = ""
    granted_scope: list[str] = field(default_factory=list)  # files a human granted (recorded runs replay it)


@dataclass(frozen=True)
class BlockedTask:
    """The executor stopped without committing because it could not complete the task within its scope.

    The working tree has been reset by the executor before returning. The handler parses `reason` for file
    paths: paths outside `allowed_files` turn into a `task.scope_change` approval request, anything else is
    an ordinary failed attempt.
    """

    reason: str  # e.g. needs a file outside allowed_files


@dataclass(frozen=True)
class Errored:
    """The executor itself failed (subprocess error, timeout, unusable recording).

    `transient=True` (default) means "try again": timeouts, non-zero exit codes, max_turns exhausted.
    `transient=False` means the run cannot make progress by retrying (binary missing, no recorded patch,
    a patch that no longer applies) and the handler returns `Blocked` for the node.
    """

    reason: str
    transient: bool = True


# Closed set of executor results; `executor_handler` dispatches on it with isinstance checks.
ExecResult = Done | BlockedTask | Errored


@dataclass
class Changeset:
    """Artifact produced by the implementation node: everything the run changed, task by task.

    Mutable on purpose: the handler accumulates into one instance while tasks complete and stores that same
    object in context, so an approval pause inside the node does not produce a second version (which would
    read as a re-plan downstream). `commits` maps task id -> commit sha and is what rollback reverts.
    """

    branch: str
    commits: dict[str, str] = field(default_factory=dict)  # task_id -> sha
    files_changed: list[str] = field(default_factory=list)
    tests_added: list[str] = field(default_factory=list)
    notes: dict[str, Any] = field(default_factory=dict)


class CodeExecutor(Protocol):
    """Anything that can implement one `TaskSpec` in the sandbox and report an `ExecResult`.

    Contract for implementers:
    - work only inside `ctx.sandbox`; the run branch is already checked out;
    - on success leave exactly one new commit (`GitSandbox.commit_task`) and return `Done`;
    - on BLOCKED reset the working tree so nothing half-written leaks into the next attempt;
    - `feedback` is the previous attempt's structured findings (plus `attempt`, the 0-based count of
      earlier attempts); executors derive the 1-based attempt number from it;
    - never read the raw requirement: the prompt is built from the typed `TaskSpec` and `Design` only;
    - exceptions escaping `execute` are treated by the handler as an attempt failure, not a crash.
    """

    async def execute(
        self, ctx: RunContext, task: TaskSpec, design: Design, feedback: dict[str, Any] | None
    ) -> ExecResult: ...
