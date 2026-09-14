from __future__ import annotations

from pathlib import Path
from typing import Literal

from pydantic import AliasChoices, Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="SDLC_", env_file=".env", extra="ignore")

    # the SDK's conventional name works too (.env.example documents ANTHROPIC_API_KEY)
    anthropic_api_key: str | None = Field(
        default=None, validation_alias=AliasChoices("ANTHROPIC_API_KEY", "SDLC_ANTHROPIC_API_KEY")
    )
    # required by the API when the key is not scoped to a workspace (sent as anthropic-workspace-id)
    anthropic_workspace_id: str | None = Field(
        default=None,
        validation_alias=AliasChoices("ANTHROPIC_WORKSPACE_ID", "SDLC_ANTHROPIC_WORKSPACE_ID"),
    )
    model: str = "claude-opus-5"
    # how agents are backed: auto = anthropic when a key is present else replay; fake = canned artifacts
    llm: Literal["auto", "fake", "replay", "anthropic"] = "auto"
    database_url: str = "sqlite+aiosqlite:///./runs/sdlc.db"
    workspace: Path = Path("./workspace/url-shortener")
    target_stack: str = "java"
    runs_dir: Path = Path("./runs")
    cache_dir: Path = Path("./runs/cache")
    policy_path: Path = Path("policy.yaml")
    workflow_path: Path = Path("workflow.yaml")
    specs_dir: Path = Path("specs/scenarios")
