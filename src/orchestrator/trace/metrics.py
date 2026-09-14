"""
Reliability metrics derived from trace events — never stored separately.
The SQL equivalents live in sql/views.sql for the Postgres sink; this module is the reference implementation
and is what the tests pin down.
"""

from __future__ import annotations

from collections import defaultdict
from dataclasses import asdict, dataclass

from ..models.trace import Kind, TraceEvent


@dataclass
class RunMetrics:
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
        return asdict(self)


def compute(run_id: str, events: list[TraceEvent]) -> RunMetrics:
    ev = sorted((e for e in events if e.run_id == run_id), key=lambda e: e.ts)
    by_node: dict[str, list[TraceEvent]] = defaultdict(list)
    for e in ev:
        if e.node_id:
            by_node[e.node_id].append(e)

    attempted = {n for n, es in by_node.items() if any(e.kind == Kind.ATTEMPT_STARTED for e in es)}
    passed = {n for n in attempted if any(e.kind == Kind.NODE_PASSED for e in by_node[n])}
    retries = sum(max(e.attempt for e in by_node[n] if e.kind == Kind.ATTEMPT_STARTED) - 1 for n in attempted)
    rollbacks = sum(1 for e in ev if e.kind == Kind.ROLLED_BACK)

    recoveries: list[float] = []
    for n in passed:
        fails = [e.ts for e in by_node[n] if e.kind == Kind.ATTEMPT_FAILED]
        ok = [e.ts for e in by_node[n] if e.kind == Kind.NODE_PASSED]
        if fails and ok:
            recoveries.append((max(ok) - min(fails)).total_seconds())
    mttr = round(sum(recoveries) / len(recoveries), 3) if recoveries else None

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
        halt_reason=(halted[-1].payload.get("trigger") if halted else None),
    )
