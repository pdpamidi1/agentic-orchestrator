from __future__ import annotations

from datetime import datetime
from enum import StrEnum
from typing import Any

from pydantic import Field

from .common import Frozen, utcnow


class Kind(StrEnum):
    RUN_STARTED = "RUN_STARTED"
    RUN_HALTED = "RUN_HALTED"
    RUN_COMPLETED = "RUN_COMPLETED"
    RUN_RESUMED = "RUN_RESUMED"
    NODE_ENTRY_GATE = "NODE_ENTRY_GATE"
    NODE_STARTED = "NODE_STARTED"
    NODE_EXIT_GATE = "NODE_EXIT_GATE"
    NODE_PASSED = "NODE_PASSED"
    NODE_FAILED = "NODE_FAILED"
    NODE_SKIPPED = "NODE_SKIPPED"
    ATTEMPT_STARTED = "ATTEMPT_STARTED"
    ATTEMPT_FAILED = "ATTEMPT_FAILED"
    ATTEMPT_PASSED = "ATTEMPT_PASSED"
    ROLLED_BACK = "ROLLED_BACK"
    FALLBACK_TAKEN = "FALLBACK_TAKEN"
    LLM_CALL = "LLM_CALL"
    EXECUTOR_CALL = "EXECUTOR_CALL"
    GATE_RESULT = "GATE_RESULT"
    POLICY_DECISION = "POLICY_DECISION"
    APPROVAL_REQUESTED = "APPROVAL_REQUESTED"
    APPROVAL_GRANTED = "APPROVAL_GRANTED"
    APPROVAL_REJECTED = "APPROVAL_REJECTED"
    INPUT_REQUESTED = "INPUT_REQUESTED"
    INPUT_RECEIVED = "INPUT_RECEIVED"
    REPLAN_TRIGGERED = "REPLAN_TRIGGERED"
    NODE_INVALIDATED = "NODE_INVALIDATED"
    ARTIFACT_WRITTEN = "ARTIFACT_WRITTEN"


class TraceEvent(Frozen):
    """The single source of truth for observability. Every metric is derived from these events."""

    run_id: str
    kind: Kind
    ts: datetime = Field(default_factory=utcnow)
    node_id: str | None = None
    task_id: str | None = None
    attempt: int = 0
    status: str | None = None
    actor: str = "orchestrator"  # agent name | gate id | human id | orchestrator
    tokens_in: int = 0
    tokens_out: int = 0
    cost_usd: float = 0.0
    parent_event_id: str | None = None
    payload: dict[str, Any] = {}
