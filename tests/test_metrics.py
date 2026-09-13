from __future__ import annotations

from datetime import UTC, datetime, timedelta

from orchestrator.models.trace import Kind, TraceEvent
from orchestrator.trace.metrics import compute


def ev(kind: Kind, node: str | None, attempt: int, secs: int, **kw) -> TraceEvent:  # type: ignore[no-untyped-def]
    return TraceEvent(
        run_id="r",
        kind=kind,
        node_id=node,
        attempt=attempt,
        ts=datetime(2026, 1, 1, tzinfo=UTC) + timedelta(seconds=secs),
        **kw,
    )


def test_metrics_from_trace() -> None:
    events = [
        ev(Kind.RUN_STARTED, None, 0, 0),
        ev(Kind.ATTEMPT_STARTED, "impl", 1, 1),
        ev(Kind.ATTEMPT_FAILED, "impl", 1, 10),
        ev(Kind.ATTEMPT_STARTED, "impl", 2, 11),
        ev(Kind.NODE_PASSED, "impl", 2, 40, cost_usd=1.5, tokens_in=100),
        ev(Kind.ATTEMPT_STARTED, "docs", 1, 41),
        ev(Kind.NODE_PASSED, "docs", 1, 50),
        ev(Kind.ATTEMPT_STARTED, "x", 1, 51),
        ev(Kind.ROLLED_BACK, "x", 3, 60),
        ev(Kind.APPROVAL_REQUESTED, "plan", 0, 5),
        ev(Kind.REPLAN_TRIGGERED, "diagnose", 0, 30),
        ev(Kind.RUN_HALTED, None, 0, 100, payload={"trigger": "gate.repeated_failure"}),
    ]
    m = compute("r", events)
    assert m.nodes == 3 and m.task_success_rate == round(2 / 3, 3)
    assert m.retry_count == 1 and m.rollback_count == 1
    assert m.mttr_seconds == 30.0  # first failure (10s) -> passed (40s)
    assert m.e2e_latency_seconds == 100.0
    assert m.llm_cost_usd == 1.5 and m.tokens == 100
    assert m.human_checkpoints == 1 and m.replans == 1
    assert m.halted and m.halt_reason == "gate.repeated_failure"
