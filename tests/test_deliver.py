"""Delivery: a COMPLETED run's sandbox tree lands in the workspace; nothing earlier can be delivered."""

from __future__ import annotations

from pathlib import Path

import pytest
from test_fake_llm import settings

from orchestrator.models.state import RunStatus
from orchestrator.service import OrchestratorService


async def test_deliver_only_after_release_approval(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    dest = tmp_path / "workspace" / "url-shortener"
    svc = OrchestratorService(settings(tmp_path, target_stack="python", workspace=dest))
    live = await svc.start("greenfield")  # dest does not exist yet -> greenfield, no seeding
    run_id = live.state.run_id
    with pytest.raises(RuntimeError, match="deliver needs a COMPLETED run"):
        await svc.deliver(run_id)
    live = await svc.approve(run_id, "approval_design", "pdp")
    live = await svc.approve(run_id, "implementation", "pdp")
    live = await svc.approve(run_id, "approval_release", "pdp")
    assert live.state.status == RunStatus.COMPLETED

    out = await svc.deliver(run_id)
    assert out == dest and (dest / "Dockerfile").exists() and (dest / "src" / "shortener" / "app.py").exists()
    assert not (dest / ".git").exists() and not list(dest.rglob("__pycache__"))
    assert (dest / "openapi.yaml").read_text() == (live.ctx.sandbox / "openapi.yaml").read_text()
    delivery = next(e for e in svc.trace.events(run_id) if e.payload.get("artifact") == "delivery")
    assert (
        delivery.payload["path"] == str(dest)
        and delivery.payload["files"] > 10
        and delivery.payload["commit"]
    )
