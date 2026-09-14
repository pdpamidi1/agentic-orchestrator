"""File-backed run store: runs/<id>/state.json + artifacts/<name>.v<n>.json. Zero infra; committed with
the run. A Postgres store with the same interface is TASKS.md item 10.

Position in the pipeline: written by the engine (``Runner`` saves ``RunState`` after every batch, pause
and halt) and by the service (artifacts after each start/approve/answer, approvals on each human
decision). Files under ``runs/<id>/``:

- ``state.json``               latest ``RunState`` (overwritten on every save)
- ``artifacts/<name>.v<n>.json`` one file per artifact version; older versions are never deleted
- ``approvals.jsonl``          append-only human decisions (node, action, APPROVED|REJECTED, who)
- ``context.json``             the non-artifact half of ``RunContext`` (feedback, answers, approval
                               tokens, artifact producers, mode flags) so a fresh process can resume
                               the run (``service.OrchestratorService.load``, TASKS T12)

``trace.jsonl`` in the same directory is owned by ``trace/sink.py``, not by this store. Methods are
``async`` to match the store interface even though the file I/O here is synchronous.
"""

from __future__ import annotations

import dataclasses
import json
from pathlib import Path
from typing import Any

from pydantic import BaseModel

from ..models.state import RunState


class FileStore:
    """Persistence for run state, artifact versions and approvals under ``runs_dir/<run_id>/``."""

    def __init__(self, runs_dir: Path) -> None:
        """``runs_dir`` is ``Settings.runs_dir``; nothing is created until the first write."""
        self.runs_dir = runs_dir

    def _dir(self, run_id: str) -> Path:
        """Return ``runs_dir/<run_id>``, creating it (and parents) on first use."""
        d = self.runs_dir / run_id
        d.mkdir(parents=True, exist_ok=True)
        return d

    async def save(self, state: RunState) -> None:
        """Overwrite ``state.json`` with the given ``RunState`` (pretty JSON, UTC timestamps)."""
        (self._dir(state.run_id) / "state.json").write_text(state.model_dump_json(indent=2), encoding="utf-8")

    async def load(self, run_id: str) -> RunState:
        """Read ``state.json`` back into a ``RunState``; ``FileNotFoundError`` if the run was never saved."""
        return RunState.model_validate_json((self._dir(run_id) / "state.json").read_text(encoding="utf-8"))

    async def save_artifact(self, run_id: str, name: str, version: int, value: Any) -> Path:
        """Write one artifact version to ``artifacts/<name>.v<version>.json`` and return its path.

        Pydantic models are dumped with their own serialiser, dataclasses (the executor's ``Changeset``)
        as their field dict; anything else (e.g. the plain-string ``requirement_text``) goes through
        ``json.dumps`` with ``default=str``. Re-saving an existing version overwrites it with identical
        content, so the call is idempotent.
        """
        d = self._dir(run_id) / "artifacts"
        d.mkdir(exist_ok=True)
        p = d / f"{name}.v{version}.json"
        if dataclasses.is_dataclass(value) and not isinstance(value, type):
            value = dataclasses.asdict(value)
        body = (
            value.model_dump_json(indent=2)
            if isinstance(value, BaseModel)
            else json.dumps(value, indent=2, default=str)
        )
        p.write_text(body, encoding="utf-8")
        return p

    async def record_approval(self, run_id: str, node_id: str, action: str, decision: str, who: str) -> None:
        """Append one line to ``approvals.jsonl``: ``{node_id, action, decision, by}``.

        ``action`` is the high-impact action approved (``release.merge``, ``task.high_impact``...) or ``-``
        for a rejection; ``decision`` is ``APPROVED`` or ``REJECTED``. The corresponding trace event
        (``APPROVAL_GRANTED``/``APPROVAL_REJECTED``) is emitted by the runner, not here.
        """
        p = self._dir(run_id) / "approvals.jsonl"
        with p.open("a", encoding="utf-8") as f:
            f.write(
                json.dumps({"node_id": node_id, "action": action, "decision": decision, "by": who}) + "\n"
            )

    async def save_context(self, run_id: str, data: dict[str, Any]) -> None:
        """Overwrite ``context.json`` with the resumable part of the run context (see module doc)."""
        (self._dir(run_id) / "context.json").write_text(
            json.dumps(data, indent=2, default=str), encoding="utf-8"
        )

    async def load_context(self, run_id: str) -> dict[str, Any] | None:
        """Read ``context.json`` back, or None for a run saved before it existed (trace fallback)."""
        p = self.runs_dir / run_id / "context.json"
        if not p.exists():
            return None
        data: dict[str, Any] = json.loads(p.read_text(encoding="utf-8"))
        return data

    def context_stale(self, run_id: str) -> bool:
        """True when ``context.json`` is missing or older than ``state.json``: the process died between
        the state save and the context save, so the file may lack grants the trace already records."""
        d = self.runs_dir / run_id
        ctx, st = d / "context.json", d / "state.json"
        if not ctx.exists():
            return True
        return st.exists() and ctx.stat().st_mtime < st.stat().st_mtime - 1

    def latest_artifacts(self, run_id: str) -> dict[str, tuple[int, Path]]:
        """Artifact name -> (highest saved version, its file) from ``artifacts/<name>.v<n>.json``."""
        out: dict[str, tuple[int, Path]] = {}
        d = self.runs_dir / run_id / "artifacts"
        if not d.is_dir():
            return out
        for p in d.glob("*.v*.json"):
            name, _, rest = p.name[: -len(".json")].rpartition(".v")
            if not name or not rest.isdigit():
                continue
            if name not in out or int(rest) > out[name][0]:
                out[name] = (int(rest), p)
        return out

    def exists(self, run_id: str) -> bool:
        """True when a ``state.json`` has been saved for ``run_id`` (does not create the directory)."""
        return (self.runs_dir / run_id / "state.json").exists()
