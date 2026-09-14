"""Process configuration, read from the environment and ``.env`` (pydantic-settings).

Consumed by the composition root (``service.py``) and the offline CLI commands (``sdlc metrics``,
``sdlc graph``). All ``SDLC_*`` variables map onto fields by name (``SDLC_LLM`` -> ``llm``); the two
Anthropic credentials additionally accept the SDK's conventional un-prefixed names. Paths are relative to
the process working directory (the repo root under ``make run``). Unknown variables are ignored.
"""

from __future__ import annotations

from pathlib import Path
from typing import Literal

from pydantic import AliasChoices, Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Runtime settings for one orchestrator process.

    Mode selection (see ``OrchestratorService._mode``): an explicit ``--replay`` wins, then ``llm``, then
    key presence (``anthropic`` when a key is set, else ``replay``). The ``fake`` mode is a *live* run with
    canned agents: checkpoints still pause. Only ``replay`` auto-approves.
    """

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
    # model id passed to AnthropicClient for every agent call (executor runs are Claude Code CLI)
    model: str = "claude-opus-5"
    # how agents are backed: auto = anthropic when a key is present else replay; fake = canned artifacts
    llm: Literal["auto", "fake", "replay", "anthropic"] = "auto"
    # reserved for the Postgres/SQLite store (TASKS); the prototype persists to runs_dir only
    database_url: str = "sqlite+aiosqlite:///./runs/sdlc.db"
    # brownfield target: copied into runs/<id>/sandbox at start and written back by `deliver`;
    # an absent or empty directory means greenfield
    workspace: Path = Path("./workspace/url-shortener")
    # "java" (Spring Boot) or "python" (FastAPI): selects gate commands and repo-map parser
    target_stack: str = "java"
    # runs/<id>/{state.json,artifacts/,trace.jsonl,approvals.jsonl,sandbox/,rejected/}
    runs_dir: Path = Path("./runs")
    # replay cache: <cache_dir>/llm (recorded structured responses) and <cache_dir>/changesets (patches)
    cache_dir: Path = Path("./runs/cache")
    policy_path: Path = Path("policy.yaml")
    workflow_path: Path = Path("workflow.yaml")
    # <specs_dir>/<scenario>.md is the requirement text when a run gives none
    specs_dir: Path = Path("specs/scenarios")
