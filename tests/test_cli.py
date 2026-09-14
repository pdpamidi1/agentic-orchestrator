"""The CLI is a thin httpx client: a read timeout on a blocking call must not look like a failed run."""

from __future__ import annotations

import httpx
import pytest
from typer.testing import CliRunner

from orchestrator import cli


def test_post_timeout_reports_the_run_is_still_running(monkeypatch: pytest.MonkeyPatch) -> None:
    def boom(*a: object, **k: object) -> httpx.Response:
        raise httpx.ReadTimeout("slow")

    monkeypatch.setattr(cli.httpx, "post", boom)
    result = CliRunner().invoke(cli.app, ["approve", "run-1", "implementation"])
    assert result.exit_code == 0
    assert "still running" in result.output and "sdlc status" in result.output


def test_post_prints_reply_and_pending_brief(monkeypatch: pytest.MonkeyPatch) -> None:
    def ok(*a: object, **k: object) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "run_id": "run-1",
                "status": "AWAITING_APPROVAL",
                "pending_approval": {"markdown": "# Brief"},
            },
            request=httpx.Request("POST", "http://x"),
        )

    monkeypatch.setattr(cli.httpx, "post", ok)
    result = CliRunner().invoke(cli.app, ["approve", "run-1", "approval_design"])
    assert result.exit_code == 0
    assert '"status": "AWAITING_APPROVAL"' in result.output and "# Brief" in result.output
    assert "pending_approval" not in result.output
