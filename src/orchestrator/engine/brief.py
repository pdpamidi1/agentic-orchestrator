"""
Approval brief: what a human sees before deciding at an approval checkpoint.

The runner pauses an approval node with ``NeedsApproval(action, summary)``. This module builds that summary
as a structured dict plus a Markdown rendering so the decision is made on the actual proposal, not on a
truncated ``str()`` of artifacts: the design (endpoints, tables, packages, layering, decisions), the plan
(tasks, HIGH tasks and the protected paths / high-impact actions they need), the security findings and risks,
the cost so far (LLM + executor spend from the trace), and, for a brownfield run, the changes against the
current repository (endpoints added/changed/removed, new tables, new packages) computed from ``repo_map``.
The brief is stored as the ``approval_brief`` artifact (versioned per pause) and carried in the
``APPROVAL_REQUESTED`` payload so it is on disk, in the trace, and served by the API/CLI.
"""

from __future__ import annotations

import re
from typing import Any

from ..models import Design, Plan, RepoMap, ValidationResult
from ..models.trace import Kind
from .context import RunContext
from .graph import NodeDef
from .policy_engine import PolicyEngine

_PARAM = re.compile(r"\{[^}]*\}")


def _norm(path: str) -> str:
    return _PARAM.sub("{}", path)


def _cost(ctx: RunContext) -> dict[str, float]:
    """USD spent so far, split by who spent it (agents vs executor), from the trace."""
    llm = exe = 0.0
    for e in ctx.trace.events(ctx.run_id):
        if e.kind == Kind.LLM_CALL:
            llm += e.cost_usd
        elif e.kind == Kind.EXECUTOR_CALL:
            exe += e.cost_usd
    return {"llm_usd": round(llm, 3), "executor_usd": round(exe, 3), "total_usd": round(llm + exe, 3)}


def _contract_changes(design: Design | None, repo: RepoMap | None) -> dict[str, list[str]]:
    """Endpoints/tables/packages the design adds, changes or removes relative to the current repo."""
    if design is None:
        return {}
    ops = {f"{o.method.upper()} {_norm(o.path)}": o for o in design.api.operations}
    if repo is None:  # greenfield: everything is new
        return {
            "endpoints_added": sorted(ops),
            "endpoints_changed": [],
            "endpoints_removed": [],
            "tables_added": [t.name for t in design.data.tables],
            "packages_added": [p.name for p in design.classes.packages],
        }
    current = {f"{e.method.upper()} {_norm(e.path)}" for e in repo.endpoints}
    added = sorted(k for k in ops if k not in current)
    removed = sorted(k for k in current if k not in ops and not k.startswith("ANY "))
    changed = sorted(k for k in ops if k in current)  # present in both: the design restates its contract
    return {
        "endpoints_added": added,
        "endpoints_changed": changed,
        "endpoints_removed": removed,
        "tables_added": [t.name for t in design.data.tables if t.name not in set(repo.tables)],
        "packages_added": [p.name for p in design.classes.packages if p.name not in set(repo.packages)],
    }


def _plan_view(plan: Plan | None, ctx: RunContext) -> list[dict[str, Any]]:
    if plan is None:
        return []
    pe = PolicyEngine(ctx.policy, ctx.target_stack)
    out = []
    for t in plan.tasks:
        verdicts = [pe.classify(f) for f in t.allowed_files]
        out.append(
            {
                "id": t.id,
                "title": t.title,
                "impact_level": t.impact_level.value,
                "depends_on": list(t.depends_on),
                "files": len(t.allowed_files),
                "protected_actions": sorted(
                    {
                        v.required_action
                        for v in verdicts
                        if v.classification == "protected" and v.required_action
                    }
                ),
                "outside_policy": [v.path for v in verdicts if v.classification in ("outside", "forbidden")],
            }
        )
    return out


def build_brief(node: NodeDef, ctx: RunContext, action: str) -> dict[str, Any]:
    """Structured brief for the approval `node` (+ `markdown`), from the artifacts in context."""
    design = ctx.get("design")
    plan = ctx.get("plan")
    repo = ctx.get("repo_map")
    spec = ctx.get("spec")
    sec = ctx.get("security_findings")
    risks = ctx.get("risk_register")
    vr = ctx.get("validation_result")
    brief: dict[str, Any] = {
        "node": node.id,
        "action": action,
        "run_id": ctx.run_id,
        "scenario": ctx.scenario,
        "cost": _cost(ctx),
        "summary": getattr(spec, "summary", None),
        "tasks": _plan_view(plan if isinstance(plan, Plan) else None, ctx),
        "changes": _contract_changes(
            design if isinstance(design, Design) else None, repo if isinstance(repo, RepoMap) else None
        ),
    }
    if isinstance(design, Design):
        brief["design"] = {
            "endpoints": [
                f"{o.method} {o.path} ({o.operation_id}) -> " + ", ".join(str(r.status) for r in o.responses)
                for o in design.api.operations
            ],
            "tables": [f"{t.name}(" + ", ".join(c.name for c in t.columns) + ")" for t in design.data.tables],
            "migrations": len(design.data.migrations),
            "packages": [p.name for p in design.classes.packages],
            "layering_rules": list(design.classes.layering_rules),
            "decisions": list(design.decisions),
        }
    if sec is not None:
        brief["security"] = {
            "findings": [f"{f.id} [{f.severity}] {f.area}: {f.description}" for f in sec.findings],
            "required_controls": list(sec.required_controls),
            "high_impact_actions_expected": list(sec.high_impact_actions_expected),
        }
    if risks is not None:
        brief["risks"] = [f"{r.id} [{r.likelihood}/{r.severity}] {r.description}" for r in risks.risks]
    if isinstance(vr, ValidationResult):
        brief["validation"] = {
            "passed": vr.passed,
            "gates": [f"{g.gate_id}: {g.status.value}" for g in vr.gates],
        }
    brief["markdown"] = render_markdown(brief)
    return brief


def render_markdown(b: dict[str, Any]) -> str:
    """Human-readable rendering of the brief for the CLI, API and the trace."""
    lines = [
        f"# Approval requested: {b['node']} ({b['action']})",
        f"Run {b['run_id']} · scenario {b['scenario']} · spent so far: "
        f"{b['cost']['total_usd']} USD (agents {b['cost']['llm_usd']}, executor {b['cost']['executor_usd']})",
        "",
    ]
    if b.get("summary"):
        lines += ["## What is being built", b["summary"], ""]
    ch = b.get("changes") or {}
    if ch:
        lines += ["## Changes to the current contract and structure"]
        for key, label in (
            ("endpoints_added", "Endpoints added"),
            ("endpoints_changed", "Endpoints restated (present today)"),
            ("endpoints_removed", "Endpoints removed (BREAKING)"),
            ("tables_added", "Tables added (migration)"),
            ("packages_added", "Packages added"),
        ):
            if ch.get(key):
                lines.append(f"- {label}: " + ", ".join(ch[key]))
        lines.append("")
    d = b.get("design")
    if d:
        lines += ["## Design"]
        lines += [f"- endpoint: {e}" for e in d["endpoints"]]
        lines += [f"- table: {t}" for t in d["tables"]]
        lines.append(f"- migrations: {d['migrations']}; packages: {', '.join(d['packages'])}")
        lines += [f"- rule: {r}" for r in d["layering_rules"]]
        lines += (
            ["", "### Decisions (with rejected alternatives)"] + [f"- {x}" for x in d["decisions"]] + [""]
        )
    if b.get("tasks"):
        lines += [
            "## Plan",
            "| task | impact | title | files | needs approval for | outside policy |",
            "|---|---|---|---|---|---|",
        ]
        for t in b["tasks"]:
            lines.append(
                f"| {t['id']} | {t['impact_level']} | {t['title']} | {t['files']} | "
                f"{', '.join(t['protected_actions']) or '-'} | {', '.join(t['outside_policy']) or '-'} |"
            )
        lines.append("")
    s = b.get("security")
    if s:
        lines += ["## Security review"] + [f"- {f}" for f in s["findings"]]
        if s["high_impact_actions_expected"]:
            lines.append("- expected high-impact actions: " + "; ".join(s["high_impact_actions_expected"]))
        lines.append("")
    if b.get("risks"):
        lines += ["## Risks"] + [f"- {r}" for r in b["risks"]] + [""]
    v = b.get("validation")
    if v:
        lines += ["## Validation", f"- passed: {v['passed']}"] + [f"- {g}" for g in v["gates"]] + [""]
    lines.append("Decide with `sdlc approve <run> <node>` or `sdlc reject <run> <node> --reason ...`.")
    return "\n".join(lines)
