"""Trace sinks: where ``TraceEvent`` rows go and how they are read back.

Position in the pipeline: ``RunContext.emit`` (engine) calls ``TraceSink.emit``; ``trace/metrics.py``,
the API ``/trace`` and ``/metrics`` routes and ``sdlc metrics`` call ``events``. The trace is the only
store of observability data: metrics are recomputed from it, never persisted.

Sinks are append-only; there is no update or delete. ``JsonlSink`` writes ``runs/<id>/trace.jsonl`` (one
JSON object per line); ``InMemorySink`` backs unit tests; ``MultiSink`` fans out to several sinks (the
Postgres/Kafka sinks in TASKS would plug in here).
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Protocol

from ..models.trace import TraceEvent


class TraceSink(Protocol):
    """Structural interface every sink satisfies: synchronous ``emit`` plus per-run ``events`` read-back."""

    def emit(self, event: TraceEvent) -> None: ...
    def events(self, run_id: str) -> list[TraceEvent]: ...


class InMemorySink:
    """Keeps events in a process-local list; for tests and ad-hoc inspection, lost on exit."""

    def __init__(self) -> None:
        self._events: list[TraceEvent] = []

    def emit(self, event: TraceEvent) -> None:
        """Append the event (all runs share one list; ``events`` filters by run)."""
        self._events.append(event)

    def events(self, run_id: str) -> list[TraceEvent]:
        """Events for ``run_id`` in emission order."""
        return [e for e in self._events if e.run_id == run_id]


class JsonlSink:
    """Append-only runs/<id>/trace.jsonl — audit-grade, greppable, diffable, committed with the run."""

    def __init__(self, runs_dir: Path) -> None:
        """``runs_dir`` is ``Settings.runs_dir``; the per-run directory is created on first emit/read."""
        self.runs_dir = runs_dir

    def _path(self, run_id: str) -> Path:
        """``runs_dir/<run_id>/trace.jsonl``; creates the run directory (not the file) as a side effect."""
        p = self.runs_dir / run_id / "trace.jsonl"
        p.parent.mkdir(parents=True, exist_ok=True)
        return p

    def emit(self, event: TraceEvent) -> None:
        """Append one JSON line; the file is opened and closed per event so a crash loses at most one row."""
        with self._path(event.run_id).open("a", encoding="utf-8") as f:
            f.write(event.model_dump_json() + "\n")

    def events(self, run_id: str) -> list[TraceEvent]:
        """Parse the whole file back into ``TraceEvent`` rows in file order; ``[]`` when no trace exists yet.

        Blank lines are skipped; a malformed line raises (the trace is expected to be machine-written only).
        """
        p = self._path(run_id)
        if not p.exists():
            return []
        return [
            TraceEvent.model_validate(json.loads(line))
            for line in p.read_text(encoding="utf-8").splitlines()
            if line
        ]


class MultiSink:
    """Fan-out sink: every event goes to all sinks; reads come from the first one (the source of truth)."""

    def __init__(self, *sinks: TraceSink) -> None:
        """``sinks[0]`` must be a sink that can read back; order matters for ``events``."""
        self.sinks = sinks

    def emit(self, event: TraceEvent) -> None:
        """Emit to each sink in order; an exception in one sink stops the fan-out for that event."""
        for s in self.sinks:
            s.emit(event)

    def events(self, run_id: str) -> list[TraceEvent]:
        """Delegate to the first sink only; secondary sinks are write-only from this class's point of view."""
        return self.sinks[0].events(run_id)
