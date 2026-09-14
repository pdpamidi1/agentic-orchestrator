from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Protocol

from ..engine.context import RunContext
from ..models import Design, TaskSpec


@dataclass(frozen=True)
class Done:
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
    reason: str  # e.g. needs a file outside allowed_files


@dataclass(frozen=True)
class Errored:
    reason: str
    transient: bool = True


ExecResult = Done | BlockedTask | Errored


@dataclass
class Changeset:
    """Artifact produced by the implementation node: everything the run changed, task by task."""

    branch: str
    commits: dict[str, str] = field(default_factory=dict)  # task_id -> sha
    files_changed: list[str] = field(default_factory=list)
    tests_added: list[str] = field(default_factory=list)
    notes: dict[str, Any] = field(default_factory=dict)


class CodeExecutor(Protocol):
    async def execute(
        self, ctx: RunContext, task: TaskSpec, design: Design, feedback: dict[str, Any] | None
    ) -> ExecResult: ...
