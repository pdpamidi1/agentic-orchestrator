"""
Sandbox provisioning: baseline files the orchestrator owns, committed before the first task so they are never
an agent change (policy.change_control does not allow agents to write them) and so the gates can run.

java: the Apache Maven wrapper (`./mvnw`, every java gate command starts with it) from specs/templates.
The architecture contract (engine/arch_contract.py) is provisioned the same way.
"""

from __future__ import annotations

import asyncio
import shutil
import stat
from pathlib import Path

from ..models.trace import Kind
from ..sandbox.git import GitSandbox
from .context import RunContext

TEMPLATES = Path(__file__).resolve().parents[3] / "specs" / "templates"
MAVEN_WRAPPER = TEMPLATES / "maven-wrapper"
MAVEN_WRAPPER_FILES = ("mvnw", "mvnw.cmd", ".mvn/wrapper/maven-wrapper.properties")


async def provision_sandbox(ctx: RunContext, git: GitSandbox, node_id: str) -> list[str]:
    """Copy the stack's baseline files into the sandbox when missing; commit them. Returns what was added."""
    if ctx.target_stack != "java":
        return []
    added = await asyncio.to_thread(_copy_wrapper, ctx.sandbox)
    if not added:
        return []
    sha = await git.commit_task("sdlc", "maven wrapper (orchestrator baseline)", ctx.run_id)
    ctx.emit(
        Kind.ARTIFACT_WRITTEN,
        node_id=node_id,
        actor="orchestrator",
        payload={"artifact": "sandbox_baseline", "files": added, "commit": sha},
    )
    return added


def _copy_wrapper(sandbox: Path) -> list[str]:
    added = []
    for rel in MAVEN_WRAPPER_FILES:
        dest = sandbox / rel
        if dest.exists():
            continue
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(MAVEN_WRAPPER / rel, dest)
        if rel == "mvnw":
            dest.chmod(dest.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
        added.append(rel)
    return added
