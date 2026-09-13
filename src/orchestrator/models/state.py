from __future__ import annotations

from datetime import datetime
from enum import StrEnum

from pydantic import BaseModel, ConfigDict, Field

from .common import utcnow


class RunStatus(StrEnum):
    RUNNING = "RUNNING"
    AWAITING_APPROVAL = "AWAITING_APPROVAL"
    AWAITING_INPUT = "AWAITING_INPUT"
    HALTED = "HALTED"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"


class NodeStatus(StrEnum):
    PENDING = "PENDING"
    RUNNING = "RUNNING"
    AWAITING_APPROVAL = "AWAITING_APPROVAL"
    AWAITING_INPUT = "AWAITING_INPUT"
    PASSED = "PASSED"
    FAILED = "FAILED"
    ROLLED_BACK = "ROLLED_BACK"
    INVALIDATED = "INVALIDATED"
    SKIPPED = "SKIPPED"


TERMINAL_OK = {NodeStatus.PASSED, NodeStatus.SKIPPED}
RUNNABLE = {NodeStatus.PENDING, NodeStatus.INVALIDATED}


class Budget(BaseModel):
    tokens_used: int = 0
    cost_usd: float = 0.0
    replans: int = 0
    started_at: datetime = Field(default_factory=utcnow)


class RunState(BaseModel):
    """Persisted after EVERY transition -> resumable, replayable, inspectable. Mutable by the engine only."""

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
        self.nodes[node_id] = status
        self.updated_at = utcnow()
