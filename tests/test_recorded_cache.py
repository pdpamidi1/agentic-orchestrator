"""The committed replay cache (runs/cache/llm) must keep validating against the current agent schemas."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from orchestrator.agents.catalog import AGENTS

CACHE = Path(__file__).resolve().parent.parent / "runs" / "cache" / "llm"
SCHEMAS = {a.output.__name__: a.output for a in AGENTS.values()}
FILES = sorted(CACHE.glob("*.json")) if CACHE.exists() else []


@pytest.mark.skipif(not FILES, reason="no recorded cache yet (run with --record)")
@pytest.mark.parametrize("path", FILES, ids=lambda p: p.stem)
def test_cached_response_matches_its_schema(path: Path) -> None:
    data = json.loads(path.read_text(encoding="utf-8"))
    assert set(data) >= {"schema", "output", "usage"}
    schema = SCHEMAS[data["schema"]]
    out = schema.model_validate(data["output"])
    assert out.model_dump(mode="json")  # round-trips
    assert data["usage"]["tokens_in"] >= 0 and data["usage"]["cost_usd"] >= 0
