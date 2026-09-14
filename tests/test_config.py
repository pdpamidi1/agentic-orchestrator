"""Settings: the API key is read under the SDK's conventional name as well as the SDLC_ prefix."""

from __future__ import annotations

from pathlib import Path

import pytest

from orchestrator.config import Settings


def test_api_key_from_dotenv_under_either_name(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    monkeypatch.delenv("SDLC_ANTHROPIC_API_KEY", raising=False)
    env = tmp_path / ".env"
    env.write_text("ANTHROPIC_API_KEY=sk-test-conventional   # inline comment\nSDLC_MODEL=claude-opus-5\n")
    s = Settings(_env_file=env)  # type: ignore[call-arg]
    assert s.anthropic_api_key == "sk-test-conventional" and s.model == "claude-opus-5"
    env.write_text("SDLC_ANTHROPIC_API_KEY=sk-test-prefixed\nANTHROPIC_WORKSPACE_ID=wrkspc_1\n")
    s = Settings(_env_file=env)  # type: ignore[call-arg]
    assert s.anthropic_api_key == "sk-test-prefixed" and s.anthropic_workspace_id == "wrkspc_1"
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-from-env")
    assert Settings(_env_file=None).anthropic_api_key == "sk-from-env"  # type: ignore[call-arg]
