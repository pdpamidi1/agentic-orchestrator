from __future__ import annotations

from pathlib import Path
from typing import Literal

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="SDLC_", env_file=".env", extra="ignore")

    anthropic_api_key: str | None = None
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
