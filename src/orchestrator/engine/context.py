"""Cross-stage context: versioned artifacts, lineage, policy, sandbox, trace. Shared by all nodes in a run."""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from ..models.policy import Policy
from ..models.trace import Kind, TraceEvent
from ..trace.sink import TraceSink


@dataclass
class Artifact:
    name: str
    version: int
    value: Any
    produced_by: str


@dataclass
class RunContext:
    run_id: str
    scenario: str
    policy: Policy
    sandbox: Path
    trace: TraceSink
    target_stack: str = "java"
    replay: bool = False
    artifacts: dict[str, Artifact] = field(default_factory=dict)
    feedback: dict[str, dict[str, Any]] = field(default_factory=dict)  # node_id -> structured feedback
    answers: dict[str, str] = field(default_factory=dict)  # ambiguity id -> human answer
    approvals: set[str] = field(default_factory=set)  # node ids approved by a human

    def emit(self, kind: Kind, **kw: Any) -> None:
        self.trace.emit(TraceEvent(run_id=self.run_id, kind=kind, **kw))

    def get(self, name: str, default: Any = None) -> Any:
        a = self.artifacts.get(name)
        return a.value if a else default

    def version(self, name: str) -> int:
        a = self.artifacts.get(name)
        return a.version if a else 0

    def put(self, name: str, value: Any, produced_by: str) -> bool:
        """Store a new artifact version.

        Returns True if this REPLACES an existing version (=> invalidation).
        """
        prev = self.artifacts.get(name)
        version = (prev.version + 1) if prev else 1
        self.artifacts[name] = Artifact(name, version, value, produced_by)
        self.emit(
            Kind.ARTIFACT_WRITTEN,
            node_id=produced_by,
            actor=produced_by,
            payload={"artifact": name, "version": version, "type": type(value).__name__},
        )
        return prev is not None

    def lineage_snapshot(self) -> dict[str, int]:
        return {k: v.version for k, v in self.artifacts.items()}
