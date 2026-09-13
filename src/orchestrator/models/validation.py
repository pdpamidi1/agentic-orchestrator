from __future__ import annotations

from enum import StrEnum

from .common import Frozen


class GateStatus(StrEnum):
    PASSED = "PASSED"
    FAILED = "FAILED"
    WARNED = "WARNED"
    SKIPPED = "SKIPPED"


class Finding(Frozen):
    file: str | None = None
    line: int | None = None
    rule: str
    message: str
    suggested_fix: str | None = None


class GateOutcome(Frozen):
    gate_id: str
    required: bool
    status: GateStatus
    took_seconds: float
    findings: list[Finding] = []
    stdout_tail: str = ""


class ValidationResult(Frozen):
    task_id: str
    attempt: int
    gates: list[GateOutcome]

    @property
    def passed(self) -> bool:
        return all(g.status != GateStatus.FAILED for g in self.gates if g.required)

    @property
    def blocking_findings(self) -> list[Finding]:
        return [f for g in self.gates if g.required and g.status == GateStatus.FAILED for f in g.findings]
