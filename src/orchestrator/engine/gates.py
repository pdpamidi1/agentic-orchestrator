"""
Deterministic validation gates. Ordered cheap -> expensive, deterministic -> judgement.
Command gates run in the sandbox; `internal:` gates are Python. Failures return structured Findings
that become the next attempt's feedback.
"""

from __future__ import annotations

import time
from collections.abc import Awaitable, Callable

from ..models.trace import Kind
from ..models.validation import Finding, GateOutcome, GateStatus, ValidationResult
from ..sandbox.git import GitSandbox
from ..sandbox.process import run_command
from .context import RunContext
from .policy_engine import PolicyEngine

InternalGate = Callable[[RunContext, GitSandbox], Awaitable[list[Finding]]]


async def diff_scope(ctx: RunContext, git: GitSandbox) -> list[Finding]:
    pe = PolicyEngine(ctx.policy, ctx.target_stack)
    changed = await git.changed_files()
    lines = await git.lines_changed()
    plan = ctx.get("plan")
    task_allowed: list[str] = []
    if plan is not None:
        for t in plan.tasks:
            task_allowed.extend(t.allowed_files)
    approved = {
        a for a in ctx.approvals
    }  # node ids; high-impact actions are mapped by the executor when approved
    verdict = pe.check_scope(changed, task_allowed, approved | set(ctx.get("approved_actions", set())), lines)
    return verdict.findings


async def secret_and_pattern_scan(ctx: RunContext, git: GitSandbox) -> list[Finding]:
    pe = PolicyEngine(ctx.policy, ctx.target_stack)
    files = await git.changed_file_contents()
    return pe.scan(files)


async def openapi_diff(ctx: RunContext, git: GitSandbox) -> list[Finding]:
    """Compare the committed contract (design stage) with what the code exposes. TODO(claude-code):
    java -> springdoc /v3/api-docs after boot in Testcontainers; python -> app.openapi().
    Diff paths/ops/status codes; any removed operation or status code is `breaking` -> requires
    api.contract.breaking_change approval."""
    return []


async def release_checklist(ctx: RunContext, git: GitSandbox) -> list[Finding]:
    findings: list[Finding] = []
    root = ctx.sandbox
    checks = {
        "Dockerfile": root / "Dockerfile",
        "ci workflow": root / ".github" / "workflows",
        "openapi": root / "openapi.yaml"
        if (root / "openapi.yaml").exists()
        else root / "src/main/resources/openapi.yaml",
        "README": root / "README.md",
    }
    for name, path in checks.items():
        if not path.exists():
            findings.append(
                Finding(file=str(path), rule="release.missing_artifact", message=f"{name} missing")
            )
    if ctx.get("validation_result") is None:
        findings.append(Finding(rule="release.no_validation", message="no validation result in context"))
    return findings


async def llm_acceptance_review(ctx: RunContext, git: GitSandbox) -> list[Finding]:
    """Advisory: the reviewer agent's verdicts per acceptance criterion become WARN findings, never
    blockers."""
    review = ctx.get("review")
    if review is None:
        return []
    return [
        Finding(rule=f"acceptance.{c['id']}", message=c.get("evidence", ""))
        for c in review.get("criteria", [])
        if c.get("verdict") == "FAIL"
    ]


INTERNAL: dict[str, InternalGate] = {
    "diff_scope": diff_scope,
    "secret_and_pattern_scan": secret_and_pattern_scan,
    "openapi_diff": openapi_diff,
    "release_checklist": release_checklist,
    "llm_acceptance_review": llm_acceptance_review,
}


async def run_gates(gate_ids: list[str], ctx: RunContext, task_id: str, attempt: int) -> ValidationResult:
    git = GitSandbox(ctx.sandbox)
    outcomes: list[GateOutcome] = []
    for gid in gate_ids:
        gdef = ctx.policy.gate(gid, ctx.target_stack)
        started = time.monotonic()
        findings: list[Finding] = []
        tail = ""
        if gdef.cmd.startswith("internal:"):
            findings = await INTERNAL[gdef.cmd.split(":", 1)[1]](ctx, git)
            failed = bool(findings)
        else:
            rc, out = await run_command(
                gdef.cmd, cwd=ctx.sandbox, policy=ctx.policy, timeout=ctx.policy.budgets.node_timeout_seconds
            )
            tail = out[-4000:]
            failed = rc != 0
            if failed:
                findings = [
                    Finding(rule=f"gate.{gid}", message=f"exit {rc}", suggested_fix="see stdout_tail")
                ]
        status = (
            GateStatus.PASSED if not failed else (GateStatus.FAILED if gdef.required else GateStatus.WARNED)
        )
        outcome = GateOutcome(
            gate_id=gid,
            required=gdef.required,
            status=status,
            took_seconds=round(time.monotonic() - started, 3),
            findings=findings,
            stdout_tail=tail,
        )
        outcomes.append(outcome)
        ctx.emit(
            Kind.GATE_RESULT,
            task_id=task_id,
            attempt=attempt,
            actor=gid,
            status=status.value,
            payload={"required": gdef.required, "findings": len(findings), "took_s": outcome.took_seconds},
        )
        if status == GateStatus.FAILED:
            break  # cheap-first ordering: stop at the first blocking failure
    return ValidationResult(task_id=task_id, attempt=attempt, gates=outcomes)
