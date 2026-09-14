"""``RunState``: the engine's persisted view of one run (``runs/<id>/state.json``).

Written by ``store/file_store.py`` after every batch, pause and halt (``engine/runner.py``); read back by
the API (``GET /runs/{id}``) and, once resume-from-store lands, by a fresh process. Unlike artifacts this
model is deliberately mutable, but only the engine mutates it; the service replaces its reference to the
returned object after each runner call.
"""

from __future__ import annotations

from datetime import datetime
from enum import StrEnum

from pydantic import BaseModel, ConfigDict, Field

from .common import utcnow


class RunStatus(StrEnum):
    """Lifecycle of a whole run.

    ``RUNNING`` while the runner loops; ``AWAITING_APPROVAL``/``AWAITING_INPUT`` when a node paused on a
    human checkpoint (only ``approve``/``answer`` move on); ``HALTED`` after a safe-stop (``halt_reason``
    set; ``approve`` can resume it); ``COMPLETED`` when every node is PASSED/SKIPPED; ``FAILED`` when no
    node is ready and the graph is not done (a dependency FAILED with no fallback).
    """

    RUNNING = "RUNNING"
    AWAITING_APPROVAL = "AWAITING_APPROVAL"
    AWAITING_INPUT = "AWAITING_INPUT"
    HALTED = "HALTED"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"


class NodeStatus(StrEnum):
    """Lifecycle of one graph node.

    Transitions: ``PENDING -> RUNNING -> {PASSED | FAILED | ROLLED_BACK | SKIPPED | AWAITING_*}``;
    ``AWAITING_* -> PENDING`` on approve/answer; any finished node ``-> INVALIDATED`` when an artifact it
    consumed gets a new version; ``INVALIDATED`` is runnable again like ``PENDING`` (see ``RUNNABLE``).
    ``SKIPPED`` is used for fallback nodes whose primary passed and for ``when:`` conditions that are false.
    """

    PENDING = "PENDING"
    RUNNING = "RUNNING"
    AWAITING_APPROVAL = "AWAITING_APPROVAL"
    AWAITING_INPUT = "AWAITING_INPUT"
    PASSED = "PASSED"
    FAILED = "FAILED"
    ROLLED_BACK = "ROLLED_BACK"
    INVALIDATED = "INVALIDATED"
    SKIPPED = "SKIPPED"


# statuses that satisfy a dependency (graph readiness) / statuses the runner may schedule again
TERMINAL_OK = {NodeStatus.PASSED, NodeStatus.SKIPPED}
RUNNABLE = {NodeStatus.PENDING, NodeStatus.INVALIDATED}


class Budget(BaseModel):
    """Running totals the runner compares against ``policy.budgets`` before every batch.

    ``tokens_used``/``cost_usd`` accumulate the usage each node's ``Success`` outcome reports (agent and
    executor calls); ``replans`` counts
    ``diagnose -> replan`` routes (bounded by ``max_replans_per_run``); ``started_at`` anchors the wall-clock
    limit. Time spent waiting for a human does not count against it: ``paused_at`` is set whenever the run
    stops for an approval, an answer or a safe-stop review, and the wait is folded into ``paused_seconds``
    when the run re-enters the loop. These are control-loop inputs, not metrics: reporting still derives
    from trace events.
    """

    tokens_used: int = 0
    cost_usd: float = 0.0
    replans: int = 0
    started_at: datetime = Field(default_factory=utcnow)
    paused_at: datetime | None = None  # set while AWAITING_* / HALTED; cleared on re-entry
    paused_seconds: float = 0.0  # cumulative human wait, excluded from the wall-clock budget

    def pause(self) -> None:
        """Stop the wall clock: the run is about to wait for a human (idempotent)."""
        if self.paused_at is None:
            self.paused_at = utcnow()

    def unpause(self) -> None:
        """Restart the wall clock, crediting the completed wait to ``paused_seconds``."""
        if self.paused_at is not None:
            self.paused_seconds += (utcnow() - self.paused_at).total_seconds()
            self.paused_at = None

    def elapsed_minutes(self) -> float:
        """Active wall-clock minutes since ``started_at`` (human waits excluded)."""
        return ((utcnow() - self.started_at).total_seconds() - self.paused_seconds) / 60


class RunState(BaseModel):
    """Persisted after EVERY transition -> resumable, replayable, inspectable. Mutable by the engine only.

    ``nodes`` holds a status for every node in ``workflow.yaml`` (``Graph.initial_statuses``).
    ``plan_version`` is bumped on each replan; ``spec_version`` and ``artifact_versions`` are carried for
    the schema but not updated by the prototype engine: the authoritative versions live in
    ``RunContext`` and in the lineage snapshots on trace events. ``pending_questions`` mirrors the open
    ``Ambiguity`` questions while ``AWAITING_INPUT``; ``halt_reason`` is the safe-stop trigger while HALTED.
    ``extra="forbid"`` makes a stale ``state.json`` with unknown keys fail loudly on load.
    """

    model_config = ConfigDict(extra="forbid")

    run_id: str
    scenario: str
    status: RunStatus = RunStatus.RUNNING
    spec_version: int = 1
    plan_version: int = 1
    nodes: dict[str, NodeStatus]
    artifact_versions: dict[str, int] = {}  # lineage: which artifact version each node consumed is in trace
    pending_questions: list[str] = []
    halt_reason: str | None = None
    budget: Budget = Field(default_factory=Budget)
    updated_at: datetime = Field(default_factory=utcnow)

    def mark(self, node_id: str, status: NodeStatus) -> None:
        """Set a node's status and touch ``updated_at``; the only sanctioned way to change ``nodes``."""
        self.nodes[node_id] = status
        self.updated_at = utcnow()
