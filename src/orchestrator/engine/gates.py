"""
Deterministic validation gates. Ordered cheap -> expensive, deterministic -> judgement.
Command gates run in the sandbox; `internal:` gates are Python. Failures return structured Findings
that become the next attempt's feedback.
"""

from __future__ import annotations

import json
import re
import shlex
import time
from collections.abc import Awaitable, Callable, Mapping
from pathlib import Path
from typing import Any

import yaml

from ..models.trace import Kind
from ..models.validation import Finding, GateOutcome, GateStatus, ValidationResult
from ..sandbox.git import GitSandbox
from ..sandbox.process import CommandNotAllowed, run_command
from .context import RunContext
from .conventions import COMMITTED_OPENAPI, SPRINGDOC_DUMPS
from .policy_engine import PolicyEngine

InternalGate = Callable[[RunContext, GitSandbox], Awaitable[list[Finding]]]


async def diff_scope(ctx: RunContext, git: GitSandbox) -> list[Finding]:
    pe = PolicyEngine(ctx.policy, ctx.target_stack)
    base = await git.run_base()  # everything the run changed, committed or not
    changed = await git.changed_files(base)
    lines = await git.lines_changed(base)
    plan = ctx.get("plan")
    task_allowed: list[str] = []
    if plan is not None:
        for t in plan.tasks:
            task_allowed.extend(t.allowed_files)
    approved = set(ctx.approvals) | set(ctx.get("approved_actions", set()))  # executor adds mapped actions
    # per-task size limits were enforced at the write boundary; here only paths and the tests rule matter
    return pe.check_scope(changed, task_allowed, approved, lines, limits=False).findings


async def secret_and_pattern_scan(ctx: RunContext, git: GitSandbox) -> list[Finding]:
    pe = PolicyEngine(ctx.policy, ctx.target_stack)
    files = await git.changed_file_contents(await git.run_base())
    return pe.scan(files)


# ---------------------------------------------------------------- contract gate
DUMP_SCRIPT = Path(__file__).resolve().parent.parent / "sandbox" / "openapi_dump.py"
HTTP_METHODS = {"get", "put", "post", "delete", "patch", "head", "options", "trace"}
COMMITTED_CANDIDATES = COMMITTED_OPENAPI
Shape = dict[str, set[str]]  # "METHOD /path/{}" -> declared status codes


def _norm(path: str) -> str:
    return re.sub(r"\{[^}]*\}", "{}", path)


def contract_shape(doc: Mapping[str, Any]) -> Shape:
    out: Shape = {}
    for path, ops in (doc.get("paths") or {}).items():
        for method, op in (ops or {}).items():
            if method.lower() in HTTP_METHODS:
                out[f"{method.upper()} {_norm(path)}"] = {str(c) for c in (op or {}).get("responses") or {}}
    return out


def design_shape(design: Any) -> Shape:
    return {
        f"{o.method.upper()} {_norm(o.path)}": {str(r.status) for r in o.responses}
        for o in design.api.operations
    }


def diff_contracts(committed: Shape, exposed: Shape) -> tuple[list[str], list[str]]:
    """-> (breaking: in the committed contract but not exposed, uncommitted: exposed but not committed)."""
    breaking, uncommitted = [], []
    for op in sorted(set(committed) | set(exposed)):
        c, e = committed.get(op), exposed.get(op)
        if e is None:
            breaking.append(f"{op}: operation removed")
        elif c is None:
            uncommitted.append(f"{op}: operation not in the committed contract")
        else:
            if c - e:
                breaking.append(f"{op}: status codes {sorted(c - e)} no longer returned")
            if e - c:
                uncommitted.append(f"{op}: undocumented status codes {sorted(e - c)}")
    return breaking, uncommitted


def _committed(ctx: RunContext) -> tuple[Shape | None, str]:
    for rel in COMMITTED_CANDIDATES:
        p = ctx.sandbox / rel
        if p.exists():
            return contract_shape(yaml.safe_load(p.read_text(encoding="utf-8")) or {}), rel
    design = ctx.get("design")
    if design is not None:
        return design_shape(design), "design.api"
    return None, "none"


async def _exposed(ctx: RunContext) -> tuple[Shape | None, str]:
    if ctx.target_stack == "python":
        cmd = f"python {shlex.quote(str(DUMP_SCRIPT))} src"
        try:
            rc, out = await run_command(
                cmd,
                cwd=ctx.sandbox,
                policy=ctx.policy,
                timeout=ctx.policy.budgets.node_timeout_seconds,
                stack=ctx.target_stack,
            )
        except CommandNotAllowed as e:
            return None, str(e)
        last = next((line for line in reversed(out.splitlines()) if line.strip().startswith("{")), "{}")
        try:
            doc = json.loads(last)
        except json.JSONDecodeError:
            return None, f"openapi dump produced no JSON (exit {rc}): {out[-500:]}"
        if rc != 0 or "error" in doc:
            return (
                None,
                f"{doc.get('error', f'openapi dump exit {rc}')}; {doc.get('import_errors', '')}".strip("; "),
            )
        return contract_shape(doc), "app.openapi()"
    if ctx.target_stack == "java":
        for rel in SPRINGDOC_DUMPS:
            p = ctx.sandbox / rel
            if p.exists():
                text = p.read_text(encoding="utf-8")
                doc = json.loads(text) if rel.endswith(".json") else yaml.safe_load(text)
                return contract_shape(doc or {}), rel
        return None, "no springdoc dump at target/openapi.json; the integration gate must produce it"
    return None, f"no contract dump strategy for target stack {ctx.target_stack!r}"


async def openapi_diff(ctx: RunContext, git: GitSandbox) -> list[Finding]:
    """Committed contract (openapi.yaml, else Design.api) vs what the code exposes (python: app.openapi()
    dumped inside the sandbox; java: the springdoc dump the integration gate leaves under target/).
    Removed operations/status codes are breaking -> need `api.contract.breaking_change` approval;
    exposed-but-undocumented ones must be committed to the document."""
    committed, source = _committed(ctx)
    if committed is None:
        return [
            Finding(
                rule="contract.no_committed_contract",
                message="no openapi.yaml and no Design in context",
                suggested_fix="commit openapi.yaml",
            )
        ]
    exposed, origin = await _exposed(ctx)
    if exposed is None:
        return [
            Finding(
                rule="contract.no_exposed_contract",
                message=origin,
                suggested_fix="make the application importable / produce the springdoc dump",
            )
        ]
    breaking, uncommitted = diff_contracts(committed, exposed)
    findings: list[Finding] = []
    if breaking:
        approved = "api.contract.breaking_change" in (
            set(ctx.approvals) | set(ctx.get("approved_actions", set()))
        )
        ctx.emit(
            Kind.POLICY_DECISION,
            actor="policy",
            status="OK" if approved else "VIOLATION",
            payload={
                "action": "api.contract.breaking_change",
                "gate": "contract",
                "breaking": breaking,
                "committed_from": source,
                "exposed_from": origin,
            },
        )
        if not approved:
            findings += [
                Finding(
                    rule="contract.breaking_change",
                    message=b,
                    suggested_fix="restore it, or obtain api.contract.breaking_change approval",
                )
                for b in breaking
            ]
    findings += [
        Finding(rule="contract.uncommitted_change", message=u, suggested_fix=f"document it in {source}")
        for u in uncommitted
    ]
    return findings


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
