"""T11: the run report renders from the run directory alone and survives missing artifacts."""

from __future__ import annotations

from pathlib import Path

import pytest
from test_fake_llm import settings

from orchestrator.reports.run_report import render, write
from orchestrator.service import OrchestratorService


async def test_report_renders_every_section_from_disk(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    svc = OrchestratorService(settings(tmp_path, target_stack="python"))
    live = await svc.start("greenfield")
    run_id = live.state.run_id
    live = await svc.approve(run_id, "approval_design", "pdp")
    (tmp_path / "runs" / run_id / "delivery_notes.md").write_text("Delivered by hand.\n")

    out = write(tmp_path / "runs", run_id)
    text = out.read_text()
    assert out.name == "run_report.md"
    for heading in (
        "# Run report:",
        "## 1. Requirement",
        "## 2. Specification",
        "## 4. Design",
        "## 5. Plan",
        "## 7. Human decisions",
        "## 8. Execution timeline",
        "## 9. Tasks as executed",
        "## 11. Metrics",
        "## 12. Artifact lineage",
        "## 14. Delivery and verification",
    ):
        assert heading in text, heading
    assert "| approval_design | plan.approve | APPROVED | pdp |" in text
    assert "Delivered by hand." in text
    assert "## 3. Impact" not in text  # greenfield: no impact artifact, section omitted


def test_report_tolerates_an_empty_run_dir(tmp_path: Path) -> None:
    (tmp_path / "runs" / "r-empty").mkdir(parents=True)
    text = render(tmp_path / "runs", "r-empty")
    assert "_no spec artifact_" in text and "_no plan artifact_" in text and "## 11. Metrics" in text
