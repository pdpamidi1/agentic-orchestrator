"""Sandbox baseline: the java Maven wrapper is provisioned by the orchestrator, once, and committed."""

from __future__ import annotations

from pathlib import Path

from orchestrator.engine.context import RunContext
from orchestrator.engine.provision import MAVEN_WRAPPER_FILES, provision_sandbox
from orchestrator.models import load_policy
from orchestrator.models.trace import Kind
from orchestrator.sandbox.git import GitSandbox
from orchestrator.trace.sink import InMemorySink

POLICY = load_policy(str(Path(__file__).resolve().parent.parent / "policy.yaml"))


def ctx_for(tmp_path: Path, stack: str) -> RunContext:
    return RunContext(
        run_id="r1",
        scenario="t",
        policy=POLICY,
        sandbox=tmp_path / "sb",
        trace=InMemorySink(),
        target_stack=stack,
    )


async def test_java_sandbox_gets_an_executable_maven_wrapper_once(tmp_path: Path) -> None:
    ctx = ctx_for(tmp_path, "java")
    git = GitSandbox(ctx.sandbox)
    await git.ensure_repo()
    assert await provision_sandbox(ctx, git, "implementation") == list(MAVEN_WRAPPER_FILES)
    mvnw = ctx.sandbox / "mvnw"
    assert mvnw.stat().st_mode & 0o111 and "Apache Software Foundation" in mvnw.read_text()
    assert "apache-maven-3.9" in (ctx.sandbox / ".mvn/wrapper/maven-wrapper.properties").read_text()
    assert await git.changed_files() == []  # committed as baseline, not an agent change
    assert await provision_sandbox(ctx, git, "implementation") == []  # idempotent
    events = [e for e in ctx.trace.events("r1") if e.payload.get("artifact") == "sandbox_baseline"]
    assert len(events) == 1 and events[0].kind == Kind.ARTIFACT_WRITTEN


async def test_python_sandbox_is_not_touched(tmp_path: Path) -> None:
    ctx = ctx_for(tmp_path, "python")
    git = GitSandbox(ctx.sandbox)
    await git.ensure_repo()
    assert await provision_sandbox(ctx, git, "implementation") == [] and not (ctx.sandbox / "mvnw").exists()


async def test_orchestrator_baseline_is_outside_the_run_diff(tmp_path: Path) -> None:
    """After provisioning + the architecture contract the base moves to HEAD, so the run-level scope gate
    never sees mvnw or ArchitectureTest.java as agent changes; a later regeneration is filtered by path."""
    from orchestrator.engine.arch_contract import write_architecture_contract
    from orchestrator.engine.gates import PROVISIONED_PATHS, diff_scope
    from orchestrator.llm.fake import CANNED
    from orchestrator.models import Design, Plan

    ctx = ctx_for(tmp_path, "java")
    git = GitSandbox(ctx.sandbox)
    await git.ensure_repo()
    await git.start_run_branch("run/r1")  # tags the base at the empty baseline
    ctx.put("design", Design.model_validate(CANNED["Design"]), "architecture")
    ctx.put("plan", Plan.model_validate(CANNED["Plan"]), "planning")
    await provision_sandbox(ctx, git, "implementation")
    await write_architecture_contract(ctx, git, "implementation")
    assert (
        len(await git.changed_files(await git.run_base())) == 4
    )  # wrapper x3 + ArchitectureTest, before the move
    await git.set_run_base()
    assert await git.changed_files(await git.run_base()) == []
    assert await diff_scope(ctx, git) == []
    assert "mvnw" in PROVISIONED_PATHS and "src/test/java/sdlc/ArchitectureTest.java" in PROVISIONED_PATHS
