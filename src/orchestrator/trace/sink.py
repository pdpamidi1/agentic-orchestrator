from __future__ import annotations

import json
from pathlib import Path
from typing import Protocol

from ..models.trace import TraceEvent


class TraceSink(Protocol):
    def emit(self, event: TraceEvent) -> None: ...
    def events(self, run_id: str) -> list[TraceEvent]: ...


class InMemorySink:
    def __init__(self) -> None:
        self._events: list[TraceEvent] = []

    def emit(self, event: TraceEvent) -> None:
        self._events.append(event)

    def events(self, run_id: str) -> list[TraceEvent]:
        return [e for e in self._events if e.run_id == run_id]


class JsonlSink:
    """Append-only runs/<id>/trace.jsonl — audit-grade, greppable, diffable, committed with the run."""

    def __init__(self, runs_dir: Path) -> None:
        self.runs_dir = runs_dir

    def _path(self, run_id: str) -> Path:
        p = self.runs_dir / run_id / "trace.jsonl"
        p.parent.mkdir(parents=True, exist_ok=True)
        return p

    def emit(self, event: TraceEvent) -> None:
        with self._path(event.run_id).open("a", encoding="utf-8") as f:
            f.write(event.model_dump_json() + "\n")

    def events(self, run_id: str) -> list[TraceEvent]:
        p = self._path(run_id)
        if not p.exists():
            return []
        return [
            TraceEvent.model_validate(json.loads(line))
            for line in p.read_text(encoding="utf-8").splitlines()
            if line
        ]


class MultiSink:
    def __init__(self, *sinks: TraceSink) -> None:
        self.sinks = sinks

    def emit(self, event: TraceEvent) -> None:
        for s in self.sinks:
            s.emit(event)

    def events(self, run_id: str) -> list[TraceEvent]:
        return self.sinks[0].events(run_id)
