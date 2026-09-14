"""``TraceEvent``: the append-only audit record every decision point emits.

Emitted through ``RunContext.emit`` into a ``TraceSink`` (``runs/<id>/trace.jsonl`` in production,
in-memory in tests). Never updated or deleted. ``trace/metrics.py`` and ``sql/views.sql`` compute every
metric from these rows; no counter is stored anywhere else. ``payload`` is the one free-form field in the
codebase because events are produced by the engine, not by an LLM schema.
"""

from __future__ import annotations

from datetime import datetime
from enum import StrEnum
from typing import Any

from pydantic import Field

from .common import Frozen, utcnow


class Kind(StrEnum):
    """Event vocabulary, grouped by purpose.

    Run lifecycle: ``RUN_STARTED``, ``RUN_COMPLETED``, ``RUN_HALTED`` (payload ``trigger`` = halt reason),
    ``RUN_RESUMED`` (after a safe-stop approval). Node lifecycle: ``NODE_ENTRY_GATE``/``NODE_EXIT_GATE``,
    ``NODE_STARTED``, ``NODE_PASSED`` (carries the lineage snapshot), ``NODE_FAILED``, ``NODE_SKIPPED``,
    ``NODE_INVALIDATED`` (payload names the cause). Attempts: ``ATTEMPT_STARTED``/``ATTEMPT_PASSED``/
    ``ATTEMPT_FAILED`` (``attempt`` field; retries and MTTR derive from these), ``ROLLED_BACK``,
    ``FALLBACK_TAKEN``. Calls: ``LLM_CALL`` (tokens/cost), ``EXECUTOR_CALL``, ``GATE_RESULT``.
    Governance: ``POLICY_DECISION`` (every allow/violation/revocation), ``APPROVAL_REQUESTED``/``GRANTED``/
    ``REJECTED``, ``INPUT_REQUESTED``/``INPUT_RECEIVED``. Lineage: ``REPLAN_TRIGGERED`` (payload ``route``
    = ``replan`` for a diagnose-driven re-plan, else an artifact re-production), ``ARTIFACT_WRITTEN``.
    """

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
    """The single source of truth for observability. Every metric is derived from these events.

    ``run_id`` + ``kind`` + ``ts`` identify the row; ``node_id``/``task_id``/``attempt`` scope it (0 = not an
    attempt). ``status`` is a short outcome label (``APPROVED``, ``BLOCKED``, ``HALTED``...). ``tokens_*``
    and ``cost_usd`` are carried by the ``LLM_CALL``/``EXECUTOR_CALL`` rows that spent them and are summed
    for the cost metrics.
    ``parent_event_id`` is reserved for causal chaining. Frozen: an emitted event is never edited.
    """

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
