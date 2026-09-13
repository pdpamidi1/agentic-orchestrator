"""What a node handler may return. The runner turns these into state transitions and trace events."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass(frozen=True)
class Success:
    artifacts: dict[str, Any] = field(default_factory=dict)
    tokens_in: int = 0
    tokens_out: int = 0
    cost_usd: float = 0.0


@dataclass(frozen=True)
class Retry:
    reason: str
    feedback: dict[str, Any] = field(default_factory=dict)  # structured; fed to the next attempt


@dataclass(frozen=True)
class Blocked:
    reason: str  # non-retryable (policy violation, unrecoverable)


@dataclass(frozen=True)
class NeedsApproval:
    action: str
    summary: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class NeedsInput:
    questions: list[dict[str, Any]]


@dataclass(frozen=True)
class Skip:
    reason: str


@dataclass(frozen=True)
class Route:
    """Used by the diagnose node: choose a branch defined in workflow.yaml `on_result`."""

    result: str  # retry | replan | halt
    feedback: dict[str, Any] = field(default_factory=dict)


Outcome = Success | Retry | Blocked | NeedsApproval | NeedsInput | Skip | Route
