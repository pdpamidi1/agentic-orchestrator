"""T4: contract gate (openapi_diff) and the generated architecture contract, on fixture repos."""

from __future__ import annotations

import shutil
from pathlib import Path

from orchestrator.engine.arch_contract import (
    CONTRACT_PATH,
    JAVA_CONTRACT_PATH,
    importlinter_config,
    render_architecture_test,
    render_architecture_test_java,
    write_architecture_contract,
)
from orchestrator.engine.context import RunContext
from orchestrator.engine.gates import contract_shape, diff_contracts, openapi_diff
from orchestrator.llm.fake import CANNED
from orchestrator.models import Design, load_policy
from orchestrator.models.trace import Kind
from orchestrator.sandbox.git import GitSandbox
from orchestrator.sandbox.process import run_command
from orchestrator.trace.sink import InMemorySink

REPO = Path(__file__).resolve().parent.parent
FIXTURES = REPO / "tests" / "fixtures"
POLICY = load_policy(str(REPO / "policy.yaml"))


def make_ctx(tmp_path: Path, fixture: str | None, stack: str) -> RunContext:
    sandbox = tmp_path / "sandbox"
    if fixture:
        shutil.copytree(FIXTURES / fixture, sandbox)
    else:
        sandbox.mkdir()
    return RunContext(
        run_id="r1", scenario="t", policy=POLICY, sandbox=sandbox, trace=InMemorySink(), target_stack=stack
    )


def rules(ctx: RunContext, findings: list) -> set[str]:  # type: ignore[type-arg]
    return {f.rule for f in findings}


def test_contract_shape_and_diff_are_method_path_and_status_based() -> None:
    committed = contract_shape({"paths": {"/u/{id}": {"get": {"responses": {"200": {}, "404": {}}}}}})
    exposed = contract_shape({"paths": {"/u/{code}": {"get": {"responses": {200: {}, 410: {}}}}}})
    assert committed == {"GET /u/{}": {"200", "404"}}  # param names normalised, codes as strings
    breaking, uncommitted = diff_contracts(committed, exposed)
    assert breaking == ["GET /u/{}: status codes ['404'] no longer returned"]
    assert uncommitted == ["GET /u/{}: undocumented status codes ['410']"]


async def test_python_app_matching_committed_document_passes(tmp_path: Path) -> None:
    ctx = make_ctx(tmp_path, "py_contract", "python")
    assert await openapi_diff(ctx, GitSandbox(ctx.sandbox)) == []


async def test_removed_status_code_is_breaking_until_approved(tmp_path: Path) -> None:
    ctx = make_ctx(tmp_path, "py_contract", "python")
    doc = ctx.sandbox / "openapi.yaml"
    doc.write_text(doc.read_text().replace('"404": {description: problem}', '"404": {}, "410": {}'))
    findings = await openapi_diff(ctx, GitSandbox(ctx.sandbox))
    assert rules(ctx, findings) == {"contract.breaking_change"} and "410" in findings[0].message
    decisions = [e for e in ctx.trace.events("r1") if e.kind == Kind.POLICY_DECISION]
    assert (
        decisions[-1].status == "VIOLATION"
        and decisions[-1].payload["action"] == "api.contract.breaking_change"
    )

    ctx.put("approved_actions", {"api.contract.breaking_change"}, "approval_design")
    assert await openapi_diff(ctx, GitSandbox(ctx.sandbox)) == []
    assert [e.status for e in ctx.trace.events("r1") if e.kind == Kind.POLICY_DECISION][-1] == "OK"


async def test_exposed_but_undocumented_operation_must_be_committed(tmp_path: Path) -> None:
    ctx = make_ctx(tmp_path, "py_contract", "python")
    doc = ctx.sandbox / "openapi.yaml"
    doc.write_text(doc.read_text().split("  /{code}:")[0])  # drop the redirect operation from the document
    findings = await openapi_diff(ctx, GitSandbox(ctx.sandbox))
    assert rules(ctx, findings) == {"contract.uncommitted_change"}
    assert findings[0].message.startswith("GET /{}: operation not in the committed contract")


async def test_design_is_the_committed_contract_when_no_document(tmp_path: Path) -> None:
    ctx = make_ctx(tmp_path, "py_contract", "python")
    (ctx.sandbox / "openapi.yaml").unlink()
    assert rules(ctx, await openapi_diff(ctx, GitSandbox(ctx.sandbox))) == {"contract.no_committed_contract"}
    design = Design.model_validate(CANNED["Design"])  # same two operations, 409/410 more than the fixture app
    ctx.put("design", design, "architecture")
    findings = await openapi_diff(ctx, GitSandbox(ctx.sandbox))
    assert rules(ctx, findings) == {"contract.breaking_change"} and len(findings) == 2


async def test_python_sandbox_without_an_app_is_a_finding(tmp_path: Path) -> None:
    ctx = make_ctx(tmp_path, None, "python")
    (ctx.sandbox / "openapi.yaml").write_text("openapi: 3.1.0\npaths: {}\n")
    (ctx.sandbox / "src").mkdir()
    findings = await openapi_diff(ctx, GitSandbox(ctx.sandbox))
    assert (
        rules(ctx, findings) == {"contract.no_exposed_contract"}
        and "no FastAPI application" in findings[0].message
    )


async def test_java_uses_the_springdoc_dump(tmp_path: Path) -> None:
    ctx = make_ctx(tmp_path, "java_contract", "java")
    findings = await openapi_diff(ctx, GitSandbox(ctx.sandbox))
    assert [(f.rule, f.message) for f in findings] == [
        ("contract.breaking_change", "GET /{}: status codes ['410'] no longer returned"),
        ("contract.uncommitted_change", "GET /actuator/health: operation not in the committed contract"),
    ]
    (ctx.sandbox / "target" / "openapi.json").unlink()
    assert rules(ctx, await openapi_diff(ctx, GitSandbox(ctx.sandbox))) == {"contract.no_exposed_contract"}


def layered_design(*rules_: str) -> Design:
    return Design.model_validate(
        {**CANNED["Design"], "classes": {"packages": [], "layering_rules": list(rules_)}}
    )


def test_importlinter_config_from_layering_rules() -> None:
    cfg = importlinter_config(layered_design("demo.api -> demo.service -> demo.repo", "keep handlers thin"))
    assert cfg is not None
    assert "root_packages =\n    demo" in cfg and "type = layers" in cfg
    assert "    (demo.api)\n    (demo.service)\n    (demo.repo)" in cfg  # optional layers, top to bottom
    assert importlinter_config(layered_design("keep handlers thin")) is None
    assert "test_no_machine_checkable_layering_rules" in render_architecture_test(
        layered_design("prose only")
    )


async def test_architecture_contract_is_committed_and_the_gate_catches_a_violation(tmp_path: Path) -> None:
    ctx = make_ctx(tmp_path, "py_layers", "python")
    git = GitSandbox(ctx.sandbox)
    await git.ensure_repo()
    ctx.put("design", layered_design("demo.api -> demo.service -> demo.repo"), "architecture")

    assert await write_architecture_contract(ctx, git, "implementation") is True
    assert (
        await write_architecture_contract(ctx, git, "implementation") is False
    )  # unchanged -> no new commit
    _, log = await git._git("log", "--oneline")
    assert len(log.splitlines()) == 2 and "architecture contract" in log
    written = [e for e in ctx.trace.events("r1") if e.payload.get("artifact") == "architecture_contract"]
    assert written and written[0].payload["contracts"] == 1

    cmd = POLICY.gate("architecture", "python").cmd
    rc, out = await run_command(cmd, cwd=ctx.sandbox, policy=POLICY, timeout=120, stack="python")
    assert rc == 0, out

    repo = ctx.sandbox / "src" / "demo" / "repo" / "__init__.py"
    repo.write_text(
        "from demo import api  # noqa: F401  (repo -> api breaks the layering)\n" + repo.read_text()
    )
    rc, out = await run_command(cmd, cwd=ctx.sandbox, policy=POLICY, timeout=120, stack="python")
    assert rc != 0 and "demo.repo" in out and "demo.api" in out
    assert await git.changed_files() == ["src/demo/repo/__init__.py"]  # the contract test itself is committed


async def test_contract_not_written_without_design_or_for_an_unknown_stack(tmp_path: Path) -> None:
    ctx = make_ctx(tmp_path, "py_layers", "python")
    git = GitSandbox(ctx.sandbox)
    await git.ensure_repo()
    assert await write_architecture_contract(ctx, git, "implementation") is False  # no design yet
    ctx.put("design", layered_design("demo.api -> demo.repo"), "architecture")
    ctx.target_stack = "kotlin"
    assert await write_architecture_contract(ctx, git, "implementation") is False
    assert not (ctx.sandbox / CONTRACT_PATH).exists() and not (ctx.sandbox / JAVA_CONTRACT_PATH).exists()


def test_java_architecture_contract_from_free_text_layer_rules() -> None:
    d = layered_design(
        "Controller -> Service -> Repository -> Domain; dependencies point strictly inward, never reverse",
        "domain has zero outward dependencies",
    )
    java = render_architecture_test_java(d)
    assert 'List.of("controller", "service", "repository", "domain")' in java
    assert "class ArchitectureTest" in java and "package sdlc;" in java and "@Test" in java
    assert "domain has zero outward dependencies" in java  # prose kept for humans


async def test_java_contract_is_written_for_the_java_stack(tmp_path: Path) -> None:
    ctx = make_ctx(tmp_path, None, "java")
    git = GitSandbox(ctx.sandbox)
    await git.ensure_repo()
    ctx.put("design", layered_design("Controller -> Service -> Repository"), "architecture")
    assert await write_architecture_contract(ctx, git, "implementation") is True
    assert (ctx.sandbox / JAVA_CONTRACT_PATH).exists() and not (ctx.sandbox / CONTRACT_PATH).exists()
