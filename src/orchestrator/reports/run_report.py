"""Run report: one Markdown document per run, rendered from what is on disk (TASKS T11).

Where it sits: a leaf over ``store/`` and ``trace/``; nothing in the engine imports it. ``sdlc report
<run>`` (CLI) and ``write()`` render ``runs/<id>/run_report.md`` from ``runs/<id>/`` alone, so a report can
be produced for any run, live or finished, in any process, without an API server.

Sources, in the order the document presents them:
- ``artifacts/<name>.v<n>.json`` (latest version each): requirement_text, spec, impact, design, plan,
  security_findings, risk_register, review, diagnosis, unit/integration/validation results;
- ``approvals.jsonl``: every human decision;
- ``trace.jsonl``: the execution timeline (tasks, policy decisions, rollbacks, halts, resumes, gate results)
  and the cost split (agent ``LLM_CALL`` vs executor ``EXECUTOR_CALL``), plus ``trace/metrics.compute``;
- ``state.json``: status, halt reason, budget;
- ``delivery_notes.md`` (optional): free text an operator adds about what happened after the run, e.g. a
  manual delivery; appended verbatim as the last section.

Invariants: read-only over the run directory (the only write is the report file itself); tolerant of
missing artifacts (a section states what is absent rather than failing), so a halted or half-finished run
still gets a complete document; artifacts are read as plain JSON, never revived into models, so the report
survives schema drift between the run's version and today's.
"""

from __future__ import annotations

import json
from collections import defaultdict
from datetime import datetime
from pathlib import Path
from typing import Any

from ..models.trace import Kind, TraceEvent
from ..store.file_store import FileStore
from ..trace.metrics import compute
from ..trace.sink import JsonlSink

# trace kinds that appear in the timeline section (everything else is noise at document level)
TIMELINE_KINDS = {
    Kind.RUN_STARTED,
    Kind.RUN_HALTED,
    Kind.RUN_RESUMED,
    Kind.RUN_COMPLETED,
    Kind.APPROVAL_REQUESTED,
    Kind.APPROVAL_GRANTED,
    Kind.APPROVAL_REJECTED,
    Kind.INPUT_REQUESTED,
    Kind.INPUT_RECEIVED,
    Kind.EXECUTOR_CALL,
    Kind.POLICY_DECISION,
    Kind.ROLLED_BACK,
    Kind.GATE_RESULT,
    Kind.NODE_FAILED,
    Kind.FALLBACK_TAKEN,
    Kind.REPLAN_TRIGGERED,
}


def _load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def _artifacts(store: FileStore, run_id: str) -> dict[str, tuple[int, Any]]:
    """name -> (version, raw JSON) for the latest version of every artifact on disk."""
    return {n: (v, _load_json(p)) for n, (v, p) in store.latest_artifacts(run_id).items()}


def _md_cell(text: object, limit: int = 160) -> str:
    """One table cell: single line, pipes escaped, truncated with an ellipsis."""
    s = " ".join(str(text if text is not None else "").split())
    s = s.replace("|", "\\|")
    return s if len(s) <= limit else s[: limit - 1] + "…"


def _texts(value: Any) -> list[str]:
    """A list of prose items. A string, or a string that a schema coerced into a list of single characters
    (seen in a live design's ``evolution_notes``), is returned as one item."""
    if isinstance(value, str):
        return [value]
    items = list(value or [])
    if items and all(isinstance(x, str) and len(x) == 1 for x in items):
        return ["".join(items)]
    return [str(x) for x in items]


def _table(headers: list[str], rows: list[list[object]]) -> list[str]:
    if not rows:
        return ["_none_", ""]
    out = ["| " + " | ".join(headers) + " |", "|" + "---|" * len(headers)]
    out += ["| " + " | ".join(_md_cell(c) for c in r) + " |" for r in rows]
    return [*out, ""]


def _ts(e: TraceEvent) -> str:
    return e.ts.strftime("%H:%M:%S")


def _fmt_dt(v: datetime | str | None) -> str:
    if v is None:
        return "-"
    if isinstance(v, str):
        return v.replace("T", " ")[:19] + " UTC"
    return v.strftime("%Y-%m-%d %H:%M:%S UTC")


# ----------------------------------------------------------------------------- sections


def _header(run_id: str, state: dict[str, Any] | None, events: list[TraceEvent]) -> list[str]:
    llm = sum(e.cost_usd for e in events if e.kind == Kind.LLM_CALL)
    exe = sum(e.cost_usd for e in events if e.kind == Kind.EXECUTOR_CALL)
    tokens = sum(e.tokens_in + e.tokens_out for e in events if e.kind == Kind.LLM_CALL)
    first, last = (events[0].ts, events[-1].ts) if events else (None, None)
    rows: list[list[object]] = [
        ["Scenario", (state or {}).get("scenario", "-")],
        ["Status", (state or {}).get("status", "-")],
        ["Halt reason", (state or {}).get("halt_reason") or "-"],
        ["First event", _fmt_dt(first)],
        ["Last event", _fmt_dt(last)],
        ["Agent (LLM) spend", f"${llm:.2f} ({tokens:,} tokens)"],
        ["Executor (Claude Code) spend, recorded", f"${exe:.2f}"],
        ["Total recorded spend", f"${llm + exe:.2f}"],
    ]
    if state:
        b = state.get("budget", {})
        rows.append(
            [
                "Budget counters (state.json)",
                f"cost ${b.get('cost_usd', 0):.2f}, replans {b.get('replans', 0)}",
            ]
        )
    return [f"# Run report: {run_id}", "", *_table(["Field", "Value"], rows)]


def _requirement(a: dict[str, tuple[int, Any]]) -> list[str]:
    out = ["## 1. Requirement", ""]
    if "requirement_text" not in a:
        return [*out, "_no requirement_text artifact_", ""]
    return [*out, "```text", str(a["requirement_text"][1]).strip(), "```", ""]


def _spec(a: dict[str, tuple[int, Any]]) -> list[str]:
    out = ["## 2. Specification", ""]
    if "spec" not in a:
        return [*out, "_no spec artifact_", ""]
    v, s = a["spec"]
    out += [f"Version {v}. {s.get('summary', '')}", ""]
    out += ["### Stories", ""]
    out += _table(
        ["Id", "As a", "I want", "So that"],
        [[x.get("id"), x.get("as_a"), x.get("i_want"), x.get("so_that")] for x in s.get("stories", [])],
    )
    out += ["### Acceptance criteria", ""]
    out += _table(
        ["Id", "Given", "When", "Then"],
        [
            [x.get("id"), x.get("given"), x.get("when"), x.get("then")]
            for x in s.get("acceptance_criteria", [])
        ],
    )
    if s.get("non_goals"):
        out += ["### Non-goals", "", *(f"- {n}" for n in s["non_goals"]), ""]
    if s.get("assumptions"):
        out += ["### Assumptions (answered ambiguities)", "", *(f"- {n}" for n in s["assumptions"]), ""]
    if s.get("ambiguities"):
        out += ["### Open ambiguities", ""]
        out += _table(
            ["Id", "Question", "Default"],
            [[x.get("id"), x.get("question"), x.get("default_if_unanswered")] for x in s["ambiguities"]],
        )
    return out


def _impact(a: dict[str, tuple[int, Any]]) -> list[str]:
    if "impact" not in a:
        return []
    _, i = a["impact"]
    out = ["## 3. Impact on the existing code (brownfield)", ""]
    out += ["- Impacted packages: " + (", ".join(f"`{p}`" for p in i.get("impacted_packages", [])) or "none")]
    out += ["- New packages: " + (", ".join(f"`{p}`" for p in i.get("new_packages", [])) or "none")]
    out += [
        "- Impacted endpoints: " + (", ".join(f"`{p}`" for p in i.get("impacted_endpoints", [])) or "none"),
        "",
    ]
    if i.get("data_flows"):
        out += ["### Data flows", "", *(f"- {f}" for f in i["data_flows"]), ""]
    out += ["### Risks identified by impact analysis", ""]
    out += _table(
        ["Id", "Description", "Likelihood", "Severity", "Mitigation"],
        [
            [r.get("id"), r.get("description"), r.get("likelihood"), r.get("severity"), r.get("mitigation")]
            for r in i.get("risks", [])
        ],
    )
    return out


def _design(a: dict[str, tuple[int, Any]]) -> list[str]:
    out = ["## 4. Design", ""]
    if "design" not in a:
        return [*out, "_no design artifact_", ""]
    _, d = a["design"]
    api = d.get("api", {})
    out += ["### API operations", ""]
    out += _table(
        ["Method", "Path", "Operation", "Responses", "Breaking"],
        [
            [
                o.get("method"),
                o.get("path"),
                o.get("operation_id"),
                ", ".join(str(r.get("status")) for r in o.get("responses", [])),
                o.get("breaking"),
            ]
            for o in api.get("operations", [])
        ],
    )
    data = d.get("data", {})
    out += ["### Tables", ""]
    out += _table(
        ["Table", "Columns", "Constraints"],
        [
            [
                t.get("name"),
                ", ".join(map(str, t.get("columns", []))),
                "; ".join(map(str, t.get("constraints", []))),
            ]
            for t in data.get("tables", [])
        ],
    )
    if data.get("migrations"):
        out += ["### Migrations", ""]
        for m in data["migrations"]:
            out += ["```sql", str(m).strip(), "```", ""]
    if data.get("evolution_notes"):
        out += ["### Data evolution notes", "", *(f"- {n}" for n in _texts(data["evolution_notes"])), ""]
    classes = d.get("classes", {})
    out += ["### Packages and classes", ""]
    for p in classes.get("packages", []):
        out += [
            f"**`{p.get('name')}`**"
            + (
                " (may depend on: " + ", ".join(f"`{x}`" for x in p.get("may_depend_on", [])) + ")"
                if p.get("may_depend_on")
                else ""
            ),
            "",
        ]
        out += _table(
            ["Class", "Responsibility"],
            [[c.get("fqcn", "").rsplit(".", 1)[-1], c.get("responsibility")] for c in p.get("classes", [])],
        )
    if classes.get("layering_rules"):
        out += ["### Layering rules", "", *(f"- {r}" for r in _texts(classes["layering_rules"])), ""]
    if d.get("decisions"):
        out += ["### Design decisions", "", *(f"- {x}" for x in _texts(d["decisions"])), ""]
    return out


def _plan(a: dict[str, tuple[int, Any]], changeset: dict[str, Any] | None) -> list[str]:
    out = ["## 5. Plan", ""]
    if "plan" not in a:
        return [*out, "_no plan artifact_", ""]
    v, p = a["plan"]
    commits = (changeset or {}).get("commits", {})
    out += [f"Version {v} (from spec v{p.get('spec_version')}). {p.get('rationale', '')}", ""]
    out += _table(
        ["Task", "Title", "Impact", "Depends on", "Group", "Files (globs)", "Committed as"],
        [
            [
                t.get("id"),
                t.get("title"),
                t.get("impact_level"),
                ", ".join(t.get("depends_on", [])),
                t.get("parallel_group") or "",
                len(t.get("allowed_files", [])),
                (commits.get(t.get("id")) or "")[:10],
            ]
            for t in p.get("tasks", [])
        ],
    )
    for t in p.get("tasks", []):
        out += [f"### {t.get('id')} — {t.get('title')}", ""]
        out += ["- Allowed files: " + ", ".join(f"`{g}`" for g in t.get("allowed_files", []))]
        if t.get("contract_slice"):
            out += ["- Contract slice: " + ", ".join(f"`{x}`" for x in t["contract_slice"])]
        if t.get("data_model_slice"):
            out += ["- Data model slice: " + ", ".join(f"`{x}`" for x in t["data_model_slice"])]
        if t.get("acceptance_criteria_ids"):
            out += ["- Acceptance criteria: " + ", ".join(t["acceptance_criteria_ids"])]
        if t.get("definition_of_done"):
            out += ["- Definition of done:", *(f"  - {x}" for x in t["definition_of_done"])]
        if t.get("risk_notes"):
            out += [f"- Risk notes: {t['risk_notes']}"]
        if t.get("rollback_note"):
            out += [f"- Rollback: {t['rollback_note']}"]
        out += [""]
    return out


def _security_and_risk(a: dict[str, tuple[int, Any]]) -> list[str]:
    out = ["## 6. Security findings and risk register", ""]
    if "security_findings" in a:
        _, s = a["security_findings"]
        out += ["### Security findings", ""]
        out += _table(
            ["Id", "Severity", "Area", "Description", "Requirement"],
            [
                [f.get("id"), f.get("severity"), f.get("area"), f.get("description"), f.get("requirement")]
                for f in s.get("findings", [])
            ],
        )
        if s.get("required_controls"):
            out += ["Required controls:", "", *(f"- {c}" for c in s["required_controls"]), ""]
        if s.get("high_impact_actions_expected"):
            out += [
                "High-impact actions expected: "
                + ", ".join(f"`{x}`" for x in s["high_impact_actions_expected"]),
                "",
            ]
    else:
        out += ["_no security_findings artifact_", ""]
    if "risk_register" in a:
        _, r = a["risk_register"]
        out += ["### Risk register", ""]
        out += _table(
            ["Id", "Description", "Likelihood", "Severity", "Mitigation", "Detection"],
            [
                [
                    x.get("id"),
                    x.get("description"),
                    x.get("likelihood"),
                    x.get("severity"),
                    x.get("mitigation"),
                    x.get("detection"),
                ]
                for x in r.get("risks", [])
            ],
        )
        if r.get("trade_offs"):
            out += ["Trade-offs:", "", *(f"- {t}" for t in r["trade_offs"]), ""]
        if r.get("failure_scenarios"):
            out += ["Failure scenarios:", "", *(f"- {t}" for t in r["failure_scenarios"]), ""]
    else:
        out += ["_no risk_register artifact_", ""]
    return out


def _approvals(run_dir: Path, a: dict[str, tuple[int, Any]]) -> list[str]:
    out = ["## 7. Human decisions", ""]
    p = run_dir / "approvals.jsonl"
    rows: list[list[object]] = []
    if p.exists():
        for line in p.read_text(encoding="utf-8").splitlines():
            if line.strip():
                d = json.loads(line)
                rows.append([d.get("node_id"), d.get("action"), d.get("decision"), d.get("by")])
    out += _table(["Node", "Action", "Decision", "By"], rows)
    if "approval_brief" in a:
        _, b = a["approval_brief"]
        out += [
            f"### Brief presented at `{b.get('node')}`",
            "",
            "<details><summary>Full brief as shown to the approver</summary>",
            "",
        ]
        brief_md = str(b.get("markdown", "")).strip().replace("\n## ", "\n#### ").replace("\n# ", "\n### ")
        out += [brief_md, "", "</details>", ""]
    return out


def _timeline(events: list[TraceEvent]) -> list[str]:
    out = ["## 8. Execution timeline", ""]
    rows: list[list[object]] = []
    for e in events:
        if e.kind not in TIMELINE_KINDS:
            continue
        p = e.payload or {}
        if e.kind == Kind.POLICY_DECISION and e.status == "OK" and not p.get("approved_actions"):
            continue  # a clean write-boundary check per task is not a decision worth a row
        detail = ""
        if e.kind == Kind.EXECUTOR_CALL:
            detail = f"turns {p.get('turns', '-')}, ${e.cost_usd:.2f}" + (
                f", {p.get('patch')}" if p.get("patch") else ""
            )
        elif e.kind == Kind.POLICY_DECISION:
            if p.get("findings"):
                detail = "; ".join(f"{f.get('file')}: {f.get('rule')}" for f in p["findings"][:4])
            elif p.get("files"):
                detail = (
                    f"{p.get('action')}: " + ", ".join(p["files"][:4]) + (" …" if len(p["files"]) > 4 else "")
                )
            elif p.get("approved_actions"):
                detail = "approved actions: " + ", ".join(p["approved_actions"])
            elif p.get("revoked"):
                detail = "revoked: " + ", ".join(p["revoked"])
            else:
                detail = str(p.get("action") or p.get("task") or "")
        elif e.kind in (Kind.RUN_HALTED,):
            detail = str(p.get("trigger", ""))
        elif e.kind == Kind.RUN_RESUMED:
            detail = "after " + str(p.get("after", ""))
        elif e.kind == Kind.APPROVAL_REQUESTED:
            summ = p.get("summary") or {}
            detail = f"{p.get('action')}" + (
                f" — {summ.get('task')}: {summ.get('title')}" if summ.get("task") else ""
            )
        elif e.kind == Kind.GATE_RESULT:
            detail = f"{e.actor}: {p.get('findings', 0)} finding(s), {p.get('took_s', 0)} s"
        elif e.kind in (Kind.ROLLED_BACK, Kind.NODE_FAILED, Kind.FALLBACK_TAKEN, Kind.REPLAN_TRIGGERED):
            detail = str(p.get("reason") or p.get("route") or p.get("fallback") or "")
        elif e.kind == Kind.INPUT_REQUESTED:
            detail = f"{len(p.get('questions', []))} question(s)"
        rows.append([_ts(e), e.kind.value, e.node_id or "", e.task_id or "", e.status or "", detail])
    out += _table(["Time (UTC)", "Event", "Node", "Task", "Status", "Detail"], rows)
    return out


def _tasks_executed(events: list[TraceEvent]) -> list[str]:
    out = ["## 9. Tasks as executed", ""]
    per: dict[str, dict[str, Any]] = defaultdict(
        lambda: {"calls": 0, "cost": 0.0, "ok": 0, "violations": 0, "rolled_back": 0, "reused": 0}
    )
    for e in events:
        if not e.task_id:
            continue
        t = per[e.task_id]
        if e.kind == Kind.EXECUTOR_CALL:
            t["calls"] += 1
            t["cost"] += e.cost_usd
            if e.status == "OK":
                t["ok"] += 1
            if e.status == "REUSED":
                t["reused"] += 1
        elif e.kind == Kind.POLICY_DECISION and e.status == "VIOLATION":
            t["violations"] += 1
        elif e.kind == Kind.ROLLED_BACK:
            t["rolled_back"] += 1
    out += _table(
        ["Task", "Executor calls", "Recorded cost", "Reused patches", "Policy violations", "Rollbacks"],
        [
            [k, v["calls"], f"${v['cost']:.2f}", v["reused"], v["violations"], v["rolled_back"]]
            for k, v in sorted(per.items())
            if v["calls"]  # gate events carry the gate node as task_id; only executed tasks belong here
        ],
    )
    out += [
        "Attempts killed by the per-task timeout do not report cost (the agent's JSON never arrives), so",
        "recorded executor spend is a lower bound.",
        "",
    ]
    return out


def _gates(a: dict[str, tuple[int, Any]]) -> list[str]:
    out = ["## 10. Gates", ""]
    any_result = False
    for name in ("unit_result", "integration_result", "validation_result"):
        if name not in a:
            continue
        any_result = True
        v, r = a[name]
        out += [f"### {name} (v{v}, attempt {r.get('attempt')})", ""]
        rows: list[list[object]] = []
        for g in r.get("gates", []):
            findings = g.get("findings") or []
            detail = "; ".join(
                f"{f.get('file') or ''} {f.get('rule') or ''}: {f.get('message') or ''}".strip()
                for f in findings[:3]
            )
            if not detail and g.get("message"):
                detail = str(g["message"])
            rows.append(
                [
                    g.get("gate_id"),
                    "required" if g.get("required") else "advisory",
                    g.get("status"),
                    g.get("took_seconds"),
                    detail,
                ]
            )
        out += _table(["Gate", "Kind", "Status", "Seconds", "Findings"], rows)
    if "review" in a:
        any_result = True
        v, r = a["review"]
        out += [f"### Code review (v{v}): recommendation {r.get('recommendation')}", ""]
        out += _table(
            ["Criterion", "Verdict", "Evidence"],
            [[c.get("id"), c.get("verdict"), c.get("evidence")] for c in r.get("criteria", [])],
        )
        if r.get("concerns"):
            out += ["Concerns:", "", *(f"- {c}" for c in r["concerns"]), ""]
    if "diagnosis" in a:
        any_result = True
        v, d = a["diagnosis"]
        out += [f"### Diagnosis (v{v}): {d.get('decision')}", "", str(d.get("root_cause", "")), ""]
        for f in d.get("feedback", []):
            out += [f"- `{f.get('target')}`: {f.get('instruction')}"]
        out += [""]
    if not any_result:
        out += ["_no gate, review or diagnosis artifact on disk_", ""]
    return out


def _metrics(run_id: str, events: list[TraceEvent]) -> list[str]:
    m = compute(run_id, events).as_dict()
    return [
        "## 11. Metrics (derived from the trace)",
        "",
        *_table(["Metric", "Value"], [[k, v] for k, v in m.items()]),
    ]


def _lineage(a: dict[str, tuple[int, Any]], events: list[TraceEvent]) -> list[str]:
    out = ["## 12. Artifact lineage", ""]
    producer: dict[str, str] = {}
    for e in events:
        p = e.payload or {}
        if e.kind == Kind.ARTIFACT_WRITTEN and e.node_id and "artifact" in p and "version" in p:
            producer[f"{p['artifact']}.v{p['version']}"] = e.node_id
    rows: list[list[object]] = [
        [n, v, producer.get(f"{n}.v{v}", "intake")] for n, (v, _) in sorted(a.items())
    ]
    return [*out, *_table(["Artifact", "Latest version", "Produced by"], rows)]


def _limitations(a: dict[str, tuple[int, Any]], state: dict[str, Any] | None) -> list[str]:
    out = ["## 13. Assumptions and limitations", ""]
    items: list[str] = []
    if "spec" in a:
        items += [f"Assumption: {x}" for x in a["spec"][1].get("assumptions", [])]
    if state and state.get("status") != "COMPLETED":
        items.append(
            f"The run did not reach COMPLETED (status {state.get('status')}, halt reason "
            f"{state.get('halt_reason') or '-'}); anything delivered from it was verified outside the "
            "run's gates, see the delivery section."
        )
    if "review" in a and a["review"][1].get("recommendation") == "REVISE":
        items.append("The last recorded code review recommended REVISE; its concerns are listed under Gates.")
    return [*out, *(f"- {i}" for i in items), ""] if items else [*out, "_none recorded_", ""]


def _delivery(run_dir: Path) -> list[str]:
    p = run_dir / "delivery_notes.md"
    if not p.exists():
        return []
    return ["## 14. Delivery and verification", "", p.read_text(encoding="utf-8").strip(), ""]


# ----------------------------------------------------------------------------- public API


def render(runs_dir: Path, run_id: str) -> str:
    """Render the Markdown report for ``run_id`` from ``runs_dir/<run_id>/`` (read-only)."""
    store = FileStore(runs_dir)
    run_dir = runs_dir / run_id
    a = _artifacts(store, run_id)
    events = sorted(JsonlSink(runs_dir).events(run_id), key=lambda e: e.ts)
    state = _load_json(run_dir / "state.json") if (run_dir / "state.json").exists() else None
    changeset = a["changeset"][1] if "changeset" in a and isinstance(a["changeset"][1], dict) else None
    parts = [
        _header(run_id, state, events),
        _requirement(a),
        _spec(a),
        _impact(a),
        _design(a),
        _plan(a, changeset),
        _security_and_risk(a),
        _approvals(run_dir, a),
        _timeline(events),
        _tasks_executed(events),
        _gates(a),
        _metrics(run_id, events),
        _lineage(a, events),
        _limitations(a, state),
        _delivery(run_dir),
    ]
    return "\n".join(line for part in parts for line in part).rstrip() + "\n"


def write(runs_dir: Path, run_id: str) -> Path:
    """Render and save ``runs/<id>/run_report.md``; returns its path."""
    out = runs_dir / run_id / "run_report.md"
    out.write_text(render(runs_dir, run_id), encoding="utf-8")
    return out
