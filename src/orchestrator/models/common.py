"""Base class and shared enums for every artifact model.

``Frozen`` is the root of all agent-produced and gate-produced artifacts; ``ImpactLevel`` is the
three-step scale shared by ``Plan.TaskSpec`` and the policy's autonomy rules. ``utcnow`` is the single
clock used for artifact and event timestamps (timezone-aware UTC, so jsonl traces sort and diff cleanly).
"""

from __future__ import annotations

from datetime import UTC, datetime
from enum import StrEnum

from pydantic import BaseModel, ConfigDict


def utcnow() -> datetime:
    """Current time as a timezone-aware UTC ``datetime`` (used as ``default_factory`` for ``ts`` fields)."""
    return datetime.now(UTC)


class Frozen(BaseModel):
    """All artifacts are immutable values; new versions are new objects (lineage, not mutation).

    ``frozen=True`` makes instances hashable and rejects attribute assignment; ``extra="forbid"`` makes
    an LLM reply with unknown keys a validation error (fed back through the repair loop) rather than a
    silently accepted artifact.
    """

    model_config = ConfigDict(frozen=True, extra="forbid")


class ImpactLevel(StrEnum):
    """Blast radius of a task as judged by the planner.

    ``HIGH`` is the only value with engine semantics: such a task pauses the implementation node for a
    human approval (``TaskSpec.requires_approval``). ``LOW``/``MEDIUM`` are informational for reviewers.
    """

    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"
