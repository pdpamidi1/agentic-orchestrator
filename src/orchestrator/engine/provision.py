"""
Sandbox provisioning: baseline files the orchestrator owns, committed before the first task so they are never
an agent change (policy.change_control does not allow agents to write them) and so the gates can run.

java: the Apache Maven wrapper (`./mvnw`, every java gate command starts with it) from specs/templates.
The architecture contract (engine/arch_contract.py) is provisioned the same way.

Where it sits: called by the executor handler (``engine/handlers.py``) right after the run branch is
created and before ``set_run_base`` tags the baseline, so the provisioned commit is *below* the base and the
run-level ``scope`` gate never sees it as a change. ``engine/gates.py`` additionally excludes
``MAVEN_WRAPPER_FILES`` from the run diff (``PROVISIONED_PATHS``) for the case where the base already existed.

Invariants
- Idempotent: files already present are left alone; nothing is committed when nothing was copied.
- The python stack needs no baseline here (its architecture contract comes from ``arch_contract.py``).

Emits ``ARTIFACT_WRITTEN`` (artifact ``sandbox_baseline``) once per provisioning commit.
"""

from __future__ import annotations

import asyncio
import shutil
import stat
from pathlib import Path

from ..models.trace import Kind
from ..sandbox.git import GitSandbox
from .context import RunContext

# <repo>/specs/templates, resolved from this file (src/orchestrator/engine/provision.py -> parents[3] = repo)
TEMPLATES = Path(__file__).resolve().parents[3] / "specs" / "templates"
MAVEN_WRAPPER = TEMPLATES / "maven-wrapper"
# relative sandbox paths the java baseline consists of; gates.PROVISIONED_PATHS reuses this tuple
MAVEN_WRAPPER_FILES = ("mvnw", "mvnw.cmd", ".mvn/wrapper/maven-wrapper.properties")


async def provision_sandbox(ctx: RunContext, git: GitSandbox, node_id: str) -> list[str]:
    """Copy the stack's baseline files into the sandbox when missing; commit them. Returns what was added.

    Args:
        ctx: run context (``target_stack`` selects the baseline; ``sandbox`` is the destination).
        git: sandbox git wrapper used to commit as task ``sdlc``.
        node_id: node attributed in the trace event (the executor node).

    Returns:
        The relative paths copied this time; empty for non-java stacks or an already-provisioned tree.

    Side effects: writes files under ``ctx.sandbox``, creates one commit, emits ``ARTIFACT_WRITTEN`` with
    the files and commit sha. The copy runs in a worker thread to keep the event loop free.
    """
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
    """Copy each missing ``MAVEN_WRAPPER_FILES`` entry from the template dir; make ``mvnw`` executable.

    Synchronous filesystem work (run via ``asyncio.to_thread``). Existing files are never overwritten so a
    brownfield workspace keeps its own wrapper. Returns the relative paths that were copied.
    """
    added = []
    for rel in MAVEN_WRAPPER_FILES:
        dest = sandbox / rel
        if dest.exists():
            continue
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(MAVEN_WRAPPER / rel, dest)
        if rel == "mvnw":
            # copy2 keeps the template's mode, but a checkout may have lost the x bit; the gates run ./mvnw
            dest.chmod(dest.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
        added.append(rel)
    return added
