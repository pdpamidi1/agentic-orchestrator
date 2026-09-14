"""
Reliability metrics derived from trace events — never stored separately.
The SQL equivalents live in sql/views.sql for the Postgres sink; this module is the reference implementation
and is what the tests pin down.

Position in the pipeline: a pure function over ``list[TraceEvent]``, called by ``service.metrics`` (API
``GET /runs/{id}/metrics``) and by the offline ``sdlc metrics`` command. It reads nothing but the events
passed in and writes nothing, so it can be run over any ``trace.jsonl`` at any time.

Definitions (all per run):
- nodes                 nodes that emitted at least one ``ATTEMPT_STARTED``
- task_success_rate     attempted nodes that later emitted ``NODE_PASSED`` / attempted nodes
- retry_count           sum over attempted nodes of (highest ``attempt`` number - 1)
- rollback_count        number of ``ROLLED_BACK`` events (node- or task-level)
- mttr_seconds          mean over passed nodes with a failure of (last ``NODE_PASSED`` - first
                        ``ATTEMPT_FAILED``); ``None`` when nothing ever failed and recovered
- e2e_latency_seconds   last ``RUN_COMPLETED``/``RUN_HALTED`` - first ``RUN_STARTED``; ``None`` while running
- llm_cost_usd, tokens  sums of ``cost_usd`` and ``tokens_in + tokens_out`` over every event
- human_checkpoints     number of ``APPROVAL_REQUESTED`` events
- replans               ``REPLAN_TRIGGERED`` events whose payload ``route`` is ``replan`` (see inline note)
- halted / halt_reason  whether a ``RUN_HALTED`` exists and the ``trigger`` of the latest one
"""

from __future__ import annotations

from collections import defaultdict
from dataclasses import asdict, dataclass

from ..models.trace import Kind, TraceEvent


@dataclass
class RunMetrics:
    """Result of ``compute``; a plain dataclass so it serialises with ``asdict`` for the API and CLI.

    ``mttr_seconds`` and ``e2e_latency_seconds`` are ``None`` rather than 0 when undefined so a dashboard
    can tell "never failed" from "recovered instantly".
    """

    run_id: str
    nodes: int
    task_success_rate: float
    retry_count: int
    rollback_count: int
    mttr_seconds: float | None
    e2e_latency_seconds: float | None
    llm_cost_usd: float
    tokens: int
    human_checkpoints: int
    replans: int
    halted: bool
    halt_reason: str | None

    def as_dict(self) -> dict[str, object]:
        """JSON-ready mapping of every field (what ``/runs/{id}/metrics`` and ``sdlc metrics`` print)."""
        return asdict(self)


def compute(run_id: str, events: list[TraceEvent]) -> RunMetrics:
    """Derive ``RunMetrics`` for ``run_id`` from ``events`` (rows for other runs are ignored).

    Events are sorted by timestamp first, so file order does not matter. Durations are rounded to
    milliseconds, cost to 4 decimals. An empty event list yields zeroed metrics with ``None`` durations.
    """
    ev = sorted((e for e in events if e.run_id == run_id), key=lambda e: e.ts)
    by_node: dict[str, list[TraceEvent]] = defaultdict(list)
    for e in ev:
        if e.node_id:
            by_node[e.node_id].append(e)

    # a node counts as "attempted" only once the runner actually started it (gates/approvals alone do not)
    attempted = {n for n, es in by_node.items() if any(e.kind == Kind.ATTEMPT_STARTED for e in es)}
    passed = {n for n in attempted if any(e.kind == Kind.NODE_PASSED for e in by_node[n])}
    # attempt numbers start at 1, so the highest attempt seen minus one is the number of retries
    retries = sum(max(e.attempt for e in by_node[n] if e.kind == Kind.ATTEMPT_STARTED) - 1 for n in attempted)
    rollbacks = sum(1 for e in ev if e.kind == Kind.ROLLED_BACK)

    # MTTR: per node, time from its first failed attempt to its final pass; averaged over recovering nodes
    recoveries: list[float] = []
    for n in passed:
        fails = [e.ts for e in by_node[n] if e.kind == Kind.ATTEMPT_FAILED]
        ok = [e.ts for e in by_node[n] if e.kind == Kind.NODE_PASSED]
        if fails and ok:
            recoveries.append((max(ok) - min(fails)).total_seconds())
    mttr = round(sum(recoveries) / len(recoveries), 3) if recoveries else None

    # a resumed run may have several RUN_STARTED/RUN_HALTED rows: measure first start to last end
    starts = [e.ts for e in ev if e.kind == Kind.RUN_STARTED]
    ends = [e.ts for e in ev if e.kind in (Kind.RUN_COMPLETED, Kind.RUN_HALTED)]
    e2e = round((max(ends) - min(starts)).total_seconds(), 3) if starts and ends else None

    halted = [e for e in ev if e.kind == Kind.RUN_HALTED]
    return RunMetrics(
        run_id=run_id,
        nodes=len(attempted),
        task_success_rate=round(len(passed) / len(attempted), 3) if attempted else 0.0,
        retry_count=retries,
        rollback_count=rollbacks,
        mttr_seconds=mttr,
        e2e_latency_seconds=e2e,
        llm_cost_usd=round(sum(e.cost_usd for e in ev), 4),
        tokens=sum(e.tokens_in + e.tokens_out for e in ev),
        human_checkpoints=sum(1 for e in ev if e.kind == Kind.APPROVAL_REQUESTED),
        # diagnose-driven re-plans only (what budgets.max_replans_per_run bounds); every re-produced
        # artifact also emits REPLAN_TRIGGERED for lineage, so the bare event would inflate this per cycle
        replans=sum(1 for e in ev if e.kind == Kind.REPLAN_TRIGGERED and e.payload.get("route") == "replan"),
        halted=bool(halted),
        # the most recent halt wins: a run may halt, be resumed by an approval, and halt again
        halt_reason=(halted[-1].payload.get("trigger") if halted else None),
    )
