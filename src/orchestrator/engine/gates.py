"""
Deterministic validation gates. Ordered cheap -> expensive, deterministic -> judgement.
Command gates run in the sandbox; `internal:` gates are Python. Failures return structured Findings
that become the next attempt's feedback.

Where it sits: ``engine/handlers.py`` (the gate handler) calls ``run_gates`` with the gate ids a gate node
lists in workflow.yaml; each id is resolved through ``policy.yaml#gates`` for the target stack, so the
commands are policy, not code. Internal gates lean on ``PolicyEngine`` (scope, security), the git sandbox
(run diff against the ``sdlc/base`` tag), the sandbox process runner (contract dump) and artifacts in
``RunContext`` (design, plan, review, validation_result).

Invariants
- Gates never mutate the sandbox or the context; they read and report. The one exception to "report only"
  is the ``POLICY_DECISION`` event the contract gate emits for a breaking change, because it is a policy
  rule with an approval attached.
- Gates decide, not agents: ``ValidationResult.passed`` (computed from required gates) is the truth the
  handler acts on; the advisory acceptance review can only WARN.
- Cheap-first short circuit: ``run_gates`` stops at the first *required* failure so an uncompilable tree is
  not also run through the integration suite.

Emits ``GATE_RESULT`` per gate executed and ``POLICY_DECISION`` (action ``api.contract.breaking_change``).
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
from .arch_contract import CONTRACT_PATH, JAVA_CONTRACT_PATH
from .context import RunContext
from .conventions import COMMITTED_OPENAPI, SPRINGDOC_DUMPS
from .policy_engine import PolicyEngine
from .provision import MAVEN_WRAPPER_FILES

# signature of an `internal:<name>` gate: findings only; an empty list means the gate passed
InternalGate = Callable[[RunContext, GitSandbox], Awaitable[list[Finding]]]
# files the orchestrator itself writes into the sandbox (engine/provision.py, engine/arch_contract.py);
# a later regeneration (design change) lands inside the run diff and must not read as an agent change
PROVISIONED_PATHS = {*MAVEN_WRAPPER_FILES, str(CONTRACT_PATH), str(JAVA_CONTRACT_PATH)}


async def diff_scope(ctx: RunContext, git: GitSandbox) -> list[Finding]:
    """``scope`` gate: the whole run diff must respect change control and the plan's ``allowed_files``.

    Reads every file changed since the run base tag (committed or not), minus ``PROVISIONED_PATHS``, and
    judges it with ``PolicyEngine.check_scope`` against the union of all tasks' ``allowed_files`` plus every
    file a human granted through ``task.scope_change`` (``scope:<task>:<path>`` tokens: the executor widened
    the task with them, so the run-level gate must accept them too) and the approvals in context (node ids,
    task/scope tokens and mapped action names all live in ``ctx.approvals``; an ``approved_actions``
    artifact is honoured too if present). Per-task size limits are skipped here:
    they were enforced task by task at the write boundary, and a whole run is legitimately larger.
    """
    pe = PolicyEngine(ctx.policy, ctx.target_stack)
    base = await git.run_base()  # everything the run changed, committed or not
    changed = [f for f in await git.changed_files(base) if f not in PROVISIONED_PATHS]
    lines = await git.lines_changed(base)
    plan = ctx.get("plan")
    task_allowed: list[str] = []
    if plan is not None:
        for t in plan.tasks:
            task_allowed.extend(t.allowed_files)
    task_allowed += [a.split(":", 2)[2] for a in ctx.approvals if a.startswith("scope:")]  # human grants
    approved = set(ctx.approvals) | set(ctx.get("approved_actions", set()))  # executor adds mapped actions
    # per-task size limits were enforced at the write boundary; here only paths and the tests rule matter
    return pe.check_scope(changed, task_allowed, approved, lines, limits=False).findings


async def secret_and_pattern_scan(ctx: RunContext, git: GitSandbox) -> list[Finding]:
    """``security`` gate: secrets, banned code patterns and forbidden PII across the run diff contents.

    Second line of defence after the write-boundary scan in the executor handler; also catches files that
    were changed but never committed by a task.
    """
    pe = PolicyEngine(ctx.policy, ctx.target_stack)
    files = await git.changed_file_contents(await git.run_base())
    return pe.scan(files)


# ---------------------------------------------------------------- contract gate
DUMP_SCRIPT = Path(__file__).resolve().parent.parent / "sandbox" / "openapi_dump.py"  # run inside the sandbox
HTTP_METHODS = {"get", "put", "post", "delete", "patch", "head", "options", "trace"}  # skip `parameters` etc.
COMMITTED_CANDIDATES = COMMITTED_OPENAPI  # openapi.yaml (python) or src/main/resources/openapi.yaml (java)
Shape = dict[str, set[str]]  # "METHOD /path/{}" -> declared status codes


def _norm(path: str) -> str:
    """Replace every ``{param}`` with ``{}`` so ``/users/{id}`` and ``/users/{userId}`` compare equal."""
    return re.sub(r"\{[^}]*\}", "{}", path)


def contract_shape(doc: Mapping[str, Any]) -> Shape:
    """Reduce an OpenAPI document to its comparable shape: operation key -> set of response status codes.

    Only real HTTP method keys under ``paths`` count (path-level ``parameters``/``summary`` are ignored);
    missing ``responses`` yield an empty set. Tolerates ``None`` bodies from a sparse YAML document.
    """
    out: Shape = {}
    for path, ops in (doc.get("paths") or {}).items():
        for method, op in (ops or {}).items():
            if method.lower() in HTTP_METHODS:
                out[f"{method.upper()} {_norm(path)}"] = {str(c) for c in (op or {}).get("responses") or {}}
    return out


def design_shape(design: Any) -> Shape:
    """Same shape as ``contract_shape``, built from the typed ``Design.api.operations`` (the fallback)."""
    return {
        f"{o.method.upper()} {_norm(o.path)}": {str(r.status) for r in o.responses}
        for o in design.api.operations
    }


def diff_contracts(committed: Shape, exposed: Shape) -> tuple[list[str], list[str]]:
    """-> (breaking: in the committed contract but not exposed, uncommitted: exposed but not committed).

    Compared operation by operation, then status code by status code within a shared operation. Messages
    are human-readable and sorted by operation key so the findings are stable between attempts.
    """
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
    """The contract the code must honour and where it came from.

    First committed OpenAPI file found among ``COMMITTED_CANDIDATES`` wins (label = its path); otherwise the
    ``Design.api`` in context (label ``"design.api"``); otherwise ``(None, "none")``.
    """
    for rel in COMMITTED_CANDIDATES:
        p = ctx.sandbox / rel
        if p.exists():
            return contract_shape(yaml.safe_load(p.read_text(encoding="utf-8")) or {}), rel
    design = ctx.get("design")
    if design is not None:
        return design_shape(design), "design.api"
    return None, "none"


async def _exposed(ctx: RunContext) -> tuple[Shape | None, str]:
    """What the application actually exposes, per stack, and its origin (or the reason it is unavailable).

    python: runs ``sandbox/openapi_dump.py src`` inside the sandbox through the policy-checked command
    runner; the script prints one JSON line (``app.openapi()`` or ``{"error": ...}``), taken as the last line
    starting with ``{`` so stray stdout does not break parsing. A disallowed command, a non-zero exit, no
    JSON or an ``error`` key all return ``(None, reason)``.
    java: reads the springdoc dump the ``integration`` gate (``-Pit verify``) leaves under ``target/``.
    Any other stack has no strategy and returns ``None``.
    """
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
    exposed-but-undocumented ones must be committed to the document.

    Findings: ``contract.no_committed_contract`` / ``contract.no_exposed_contract`` when either side is
    unavailable (each alone fails the gate), ``contract.breaking_change`` per breaking item unless the
    action is approved, ``contract.uncommitted_change`` per undocumented item (always).

    Emits one ``POLICY_DECISION`` (status OK when approved, VIOLATION otherwise) whenever a breaking change
    exists, so the audit log records the approval decision even when the gate passes.
    """
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
        # the action name may sit in ctx.approvals directly (a human approved it) or have been mapped there
        # from an approved HIGH task by the executor handler; an `approved_actions` artifact is honoured too
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
    """``release`` gate: the release artifacts exist and a validation verdict is in context.

    Checks for a Dockerfile, a ``.github/workflows`` directory, the committed OpenAPI document (root
    ``openapi.yaml`` first, else the java resources location) and a README; each missing item is a
    ``release.missing_artifact`` finding. ``release.no_validation`` fires when the ``validation`` node has
    not recorded a ``validation_result`` (the release gate depends on it in workflow.yaml, so this is a
    guard against a mis-wired graph rather than an expected path).
    """
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
    blockers.

    Reads the ``review`` artifact (a plain dict from the reviewer agent); every criterion with verdict
    ``FAIL`` becomes an ``acceptance.<criterion id>`` finding carrying the evidence. No review in context
    means no findings. The gate is ``required: false`` in policy.yaml, so ``run_gates`` records WARNED and
    continues; humans see the warnings at the release approval.
    """
    review = ctx.get("review")
    if review is None:
        return []
    return [
        Finding(rule=f"acceptance.{c['id']}", message=c.get("evidence", ""))
        for c in review.get("criteria", [])
        if c.get("verdict") == "FAIL"
    ]


# registry for `cmd: "internal:<name>"` in policy.yaml#gates; an unknown name is a KeyError at gate time
INTERNAL: dict[str, InternalGate] = {
    "diff_scope": diff_scope,
    "secret_and_pattern_scan": secret_and_pattern_scan,
    "openapi_diff": openapi_diff,
    "release_checklist": release_checklist,
    "llm_acceptance_review": llm_acceptance_review,
}


async def run_gates(gate_ids: list[str], ctx: RunContext, task_id: str, attempt: int) -> ValidationResult:
    """Run ``gate_ids`` in order and collect one ``GateOutcome`` per gate executed.

    Args:
        gate_ids: gate ids from the node's ``gates:`` list, resolved via ``policy.gate(id, stack)``.
        ctx: run context (sandbox, policy, artifacts).
        task_id: recorded on the result and the events; the gate handler passes the node id.
        attempt: the node attempt number, for the trace.

    Behaviour per gate: an ``internal:`` gate fails when it returns any finding; a command gate runs through
    the policy-checked sandbox runner with the node timeout and fails on a non-zero exit, keeping the last
    4000 chars of output as ``stdout_tail`` and a single ``gate.<id>`` finding. A failure of a
    ``required`` gate is FAILED and stops the loop (cheap-first: later gates are not run and appear in no
    outcome); a failure of an advisory gate is WARNED and the loop continues.

    Returns:
        ``ValidationResult`` whose ``passed`` derives from the required outcomes.

    Emits ``GATE_RESULT`` per executed gate (actor = gate id, status = PASSED/FAILED/WARNED).
    """
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
