"""File-backed run store: runs/<id>/state.json + artifacts/<name>.v<n>.json. Zero infra; committed with
the run. A Postgres store with the same interface is TASKS.md item 10."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from pydantic import BaseModel

from ..models.state import RunState


class FileStore:
    def __init__(self, runs_dir: Path) -> None:
        self.runs_dir = runs_dir

    def _dir(self, run_id: str) -> Path:
        d = self.runs_dir / run_id
        d.mkdir(parents=True, exist_ok=True)
        return d

    async def save(self, state: RunState) -> None:
        (self._dir(state.run_id) / "state.json").write_text(state.model_dump_json(indent=2), encoding="utf-8")

    async def load(self, run_id: str) -> RunState:
        return RunState.model_validate_json((self._dir(run_id) / "state.json").read_text(encoding="utf-8"))

    async def save_artifact(self, run_id: str, name: str, version: int, value: Any) -> Path:
        d = self._dir(run_id) / "artifacts"
        d.mkdir(exist_ok=True)
        p = d / f"{name}.v{version}.json"
        body = (
            value.model_dump_json(indent=2)
            if isinstance(value, BaseModel)
            else json.dumps(value, indent=2, default=str)
        )
        p.write_text(body, encoding="utf-8")
        return p

    async def record_approval(self, run_id: str, node_id: str, action: str, decision: str, who: str) -> None:
        p = self._dir(run_id) / "approvals.jsonl"
        with p.open("a", encoding="utf-8") as f:
            f.write(
                json.dumps({"node_id": node_id, "action": action, "decision": decision, "by": who}) + "\n"
            )

    def exists(self, run_id: str) -> bool:
        return (self.runs_dir / run_id / "state.json").exists()
