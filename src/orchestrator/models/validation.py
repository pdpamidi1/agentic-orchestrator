"""``ValidationResult``: the verdict of an ordered set of gates for one node attempt.

Produced by ``engine/gates.py#run_gates`` when a gate node runs and stored by the gate handler under the
node's ``produces`` name (``unit_result``, ``integration_result``, ``validation_result``); the validation
node additionally rolls up the results of the nodes it ``rolls_up``. Consumed by the runner (``passed``
decides Success vs Retry) and by the diagnoser and documenter agents. Gates decide, not agents:
``passed`` is the truth.
"""

from __future__ import annotations

from enum import StrEnum

from .common import Frozen


class GateStatus(StrEnum):
    """Outcome of one gate. Only ``FAILED`` on a *required* gate blocks; ``WARNED`` is advisory
    (e.g. the acceptance review); ``SKIPPED`` means the gate did not apply to this stack or change."""

    PASSED = "PASSED"
    FAILED = "FAILED"
    WARNED = "WARNED"
    SKIPPED = "SKIPPED"


class Finding(Frozen):
    """One actionable problem a gate reported; fed back verbatim into the next attempt's prompt.

    ``file``/``line`` are optional because some gates (scope, budget, contract diff) report at run level.
    ``rule`` is the gate's own identifier for the check; ``suggested_fix`` is optional guidance.
    """

    file: str | None = None
    line: int | None = None
    rule: str
    message: str
    suggested_fix: str | None = None


class GateOutcome(Frozen):
    """Result of one gate: id, whether it was required by policy, status, duration and findings.

    ``stdout_tail`` keeps the last lines of the gate command for humans and the diagnoser; the full log is
    not stored in the artifact.
    """

    gate_id: str
    required: bool
    status: GateStatus
    took_seconds: float
    findings: list[Finding] = []
    stdout_tail: str = ""


class ValidationResult(Frozen):
    """All gate outcomes for ``task_id`` (a task id or, for gate nodes, the node id) at ``attempt``."""

    task_id: str
    attempt: int
    gates: list[GateOutcome]

    @property
    def passed(self) -> bool:
        """True when no *required* gate FAILED; optional gates and WARNED/SKIPPED never block."""
        return all(g.status != GateStatus.FAILED for g in self.gates if g.required)

    @property
    def blocking_findings(self) -> list[Finding]:
        """Findings of required gates that FAILED, in gate order: the structured feedback for the retry."""
        return [f for g in self.gates if g.required and g.status == GateStatus.FAILED for f in g.findings]
