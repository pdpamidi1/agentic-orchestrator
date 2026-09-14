"""
Node-kind handlers: how each kind of node in workflow.yaml is executed.
  agent    -> AGENTS[node.agent](llm)
  executor -> run plan.tasks as a task-level DAG (task deps + parallel groups), HIGH tasks pause for approval
  gate     -> run_gates(node.gates) and convert the ValidationResult into Success/Retry
  input    -> re-run the requirements agent with human answers -> spec v2 (invalidation follows automatically)
"""

from __future__ import annotations

import asyncio
import re
from pathlib import Path
from typing import Any

from ..agents.catalog import AGENTS
from ..executors.base import BlockedTask, Changeset, CodeExecutor, Done, Errored
from ..llm.client import LLMClient
from ..models import Plan, TaskSpec, ValidationResult
from ..models.trace import Kind
from ..sandbox.git import GitSandbox
from .arch_contract import write_architecture_contract
from .context import RunContext
from .gates import run_gates
from .graph import Graph, NodeDef
from .outcomes import Blocked, NeedsApproval, Outcome, Retry, Route, Success
from .policy_engine import PolicyEngine, matches
from .provision import provision_sandbox


def build_handlers(llm: LLMClient, executor: CodeExecutor, graph: Graph) -> dict[str, Any]:
    agents = {name: cls(llm) for name, cls in AGENTS.items()}
    rolled_up = {r for n in graph.nodes.values() for r in n.rolls_up}  # gate nodes another gate node folds in

    async def agent_handler(node: NodeDef, ctx: RunContext) -> Outcome:
        out = await agents[node.agent](node, ctx)  # type: ignore[index]
        if node.agent == "diagnoser" and isinstance(out, Success):
            d = out.artifacts["diagnosis"]
            fb = {"items": [i.model_dump() for i in d.feedback], "root_cause": d.root_cause}
            return Route(d.decision, fb)
        return out

    async def executor_handler(node: NodeDef, ctx: RunContext) -> Outcome:
        plan: Plan = ctx.get("plan")
        design = ctx.get("design")
        git = GitSandbox(ctx.sandbox)
        await git.ensure_repo()
        branch = f"{ctx.policy.sandbox.branch_prefix}{ctx.run_id}"
        await git.start_run_branch(branch)
        await provision_sandbox(ctx, git, node.id)  # baseline the agent may not write (java: mvnw)
        await write_architecture_contract(ctx, git, node.id)  # the gate's test is never agent-authored
        cs: Changeset = ctx.get("changeset") or Changeset(branch=branch)
        feedback = ctx.feedback.get(node.id)
        pe = PolicyEngine(ctx.policy, ctx.target_stack)
        task_attempts: dict[str, int] = dict((feedback or {}).get("task_attempts", {}))

        def task_failed(t: TaskSpec, reason: str, extra: dict[str, Any]) -> Outcome:
            """One task's failure is retried per task (policy.budgets.max_attempts_per_task); a task
            that exhausts its own allowance blocks the node (safe-stop) instead of burning the other
            tasks' retries."""
            task_attempts[t.id] = task_attempts.get(t.id, 0) + 1
            if task_attempts[t.id] >= ctx.policy.budgets.max_attempts_per_task:
                return Blocked(f"task {t.id} failed {task_attempts[t.id]} times: {reason}")
            return Retry(reason, {**extra, "task": t.id, "task_attempts": task_attempts})

        # a task that reported BLOCKED naming the files it needs paused the node for a scope approval: the
        # human approving the node grants exactly those files (scope tokens, revoked on re-plan like the rest)
        pending = (ctx.feedback.get(node.id) or {}).get("scope_request")
        if pending and node.id in ctx.approvals:
            ctx.approvals.discard(node.id)
            ctx.approvals |= {f"scope:{pending['task']}:{f}" for f in pending["files"]}
            ctx.emit(
                Kind.POLICY_DECISION,
                node_id=node.id,
                task_id=pending["task"],
                actor="policy",
                status="SCOPE_APPROVED",
                payload={"action": "task.scope_change", "files": pending["files"]},
            )
            fb = dict(ctx.feedback.get(node.id) or {})
            fb.pop("scope_request")
            fb["scope_change"] = pending  # the agent learns why its scope grew
            ctx.feedback[node.id] = fb
            feedback = fb

        def widened(t: TaskSpec) -> TaskSpec:
            extra = sorted(a.split(":", 2)[2] for a in ctx.approvals if a.startswith(f"scope:{t.id}:"))
            return t.model_copy(update={"allowed_files": [*t.allowed_files, *extra]}) if extra else t

        remaining = {t.id: t for t in plan.tasks if t.id not in cs.commits}
        while remaining:
            ready = [t for t in remaining.values() if all(d in cs.commits for d in t.depends_on)]
            if not ready:
                return Blocked("plan has unsatisfiable task dependencies")
            for t in ready:  # task-level high-impact approval
                if t.requires_approval and f"task:{t.id}" not in ctx.approvals:
                    if node.id in ctx.approvals:  # human approved the node while it was paused on this task
                        ctx.approvals.discard(node.id)
                        ctx.approvals.add(f"task:{t.id}")
                        ctx.emit(
                            Kind.POLICY_DECISION,
                            node_id=node.id,
                            task_id=t.id,
                            actor="policy",
                            status="APPROVED",
                            payload={"action": "task.high_impact", "task": t.id},
                        )
                    elif ctx.replay and ctx.policy.autonomy.auto_approve_in_replay:
                        ctx.approvals.add(f"task:{t.id}")
                    else:
                        ctx.put("changeset", cs, node.id)
                        return NeedsApproval(
                            "task.high_impact", {"task": t.id, "title": t.title, "risk": t.risk_notes}
                        )
            groups: dict[str, list[TaskSpec]] = {}
            for t in ready:
                groups.setdefault(t.parallel_group or t.id, []).append(t)
            batch = next(iter(groups.values()))  # one group at a time
            # group members share ONE working tree and index, so they run one after another and each result is
            # judged before the next task starts (a concurrent task's half-written files would otherwise land
            # in this task's commit and scope check). True parallelism needs per-task worktrees: a stretch
            # item.
            for original in batch:
                t = widened(original)
                r = await executor.execute(ctx, t, design, feedback)
                if isinstance(r, Done):
                    # an approved HIGH task may touch the protected paths it declared; map the human's
                    # approval onto the high-impact actions those paths require
                    approved: set[str] = set()
                    if f"task:{t.id}" in ctx.approvals:
                        approved = {
                            pv.required_action
                            for pv in (pe.classify(f) for f in r.files_changed)
                            if pv.classification == "protected" and pv.required_action
                        }
                    granted = set(t.allowed_files) - set(original.allowed_files)  # scope-approved files
                    approved |= {
                        pv.required_action
                        for pv in (pe.classify(f) for f in r.files_changed if f in granted)
                        if pv.classification == "protected" and pv.required_action
                    }
                    verdict = pe.check_scope(r.files_changed, t.allowed_files, approved)
                    ctx.approvals |= approved  # the run-level gates see the same approvals
                    # write boundary: secrets, banned patterns and PII in what this task wrote
                    contents = await asyncio.to_thread(_read_all, ctx.sandbox, r.files_changed)
                    findings = verdict.findings + pe.scan(contents)
                    ctx.emit(
                        Kind.POLICY_DECISION,
                        task_id=t.id,
                        actor="policy",
                        status="OK" if not findings else "VIOLATION",
                        payload={
                            "findings": [f.model_dump() for f in findings],
                            "rules": sorted({f.rule for f in findings}),
                            "approved_actions": sorted(approved),
                        },
                    )
                    if findings:
                        await git.revert(r.commit_sha)
                        ctx.emit(
                            Kind.ROLLED_BACK,
                            node_id=node.id,
                            task_id=t.id,
                            actor="orchestrator",
                            payload={
                                "commit": r.commit_sha,
                                "reason": "policy violation at the write boundary",
                            },
                        )
                        return task_failed(
                            t,
                            f"task {t.id} violated policy: {', '.join(sorted({f.rule for f in findings}))}",
                            {"findings": [f.model_dump() for f in findings]},
                        )
                    cs.commits[t.id] = r.commit_sha
                    cs.files_changed += r.files_changed
                    cs.tests_added += r.tests_added
                    cs.notes[t.id] = r.notes
                    remaining.pop(t.id)
                elif isinstance(r, BlockedTask):
                    wanted = [
                        f
                        for f in _requested_files(r.reason)
                        if not matches(f, t.allowed_files)
                        and pe.classify(f).classification in ("allowed", "protected")
                    ]
                    if wanted:  # agents propose scope, humans approve it: pause instead of a pointless retry
                        ctx.put("changeset", cs, node.id)
                        ctx.feedback.setdefault(node.id, {})["scope_request"] = {
                            "task": t.id,
                            "files": wanted,
                            "reason": r.reason,
                        }
                        ctx.emit(
                            Kind.POLICY_DECISION,
                            node_id=node.id,
                            task_id=t.id,
                            actor="policy",
                            status="SCOPE_REQUESTED",
                            payload={"action": "task.scope_change", "files": wanted},
                        )
                        return NeedsApproval(
                            "task.scope_change", {"task": t.id, "files": wanted, "reason": r.reason[:500]}
                        )
                    return task_failed(t, f"task {t.id} blocked: {r.reason}", {"reason": r.reason})
                elif isinstance(r, Errored):
                    if not r.transient:
                        return Blocked(r.reason)
                    return task_failed(t, f"task {t.id} errored: {r.reason}", {})
        if ctx.get("changeset") is cs:
            # already in context from an approval pause inside this node; the object carries every commit,
            # so a second put would only bump the version and read as a replan downstream
            return Success()
        return Success({"changeset": cs})

    async def gate_handler(node: NodeDef, ctx: RunContext) -> Outcome:
        attempt = (ctx.feedback.get(node.id) or {}).get("attempt", 0) + 1
        own = await run_gates(list(node.gates), ctx, task_id=node.id, attempt=attempt)
        rolled = [
            g
            for dep in node.rolls_up
            for name in graph.nodes[dep].produces
            if isinstance(r := ctx.get(name), ValidationResult)
            for g in r.gates
        ]
        vr = ValidationResult(task_id=node.id, attempt=attempt, gates=rolled + own.gates)
        for name in node.produces:
            # stored once, pass or fail: the roll-up and the diagnoser read failed results.
            # (`run_report` holds the release gate's result until TASKS T11 renders the real report.)
            ctx.put(name, vr, node.id)
        if (
            vr.passed or node.id in rolled_up
        ):  # a rolled-up gate node records its verdict; the roll-up decides
            return Success()
        return Retry(
            f"{node.id}: {len(vr.blocking_findings)} blocking findings",
            {"findings": [f.model_dump() for f in vr.blocking_findings][:25]},
        )

    async def input_handler(node: NodeDef, ctx: RunContext) -> Outcome:
        return await agents["requirements"](node, ctx)  # produces spec v2 with answers folded in

    return {
        "agent": agent_handler,
        "executor": executor_handler,
        "gate": gate_handler,
        "input": input_handler,
    }


def _read_all(root: Path, files: list[str]) -> dict[str, str]:
    out: dict[str, str] = {}
    for f in files:
        p = root / f
        if p.is_file():
            out[f] = p.read_text(encoding="utf-8", errors="replace")
    return out


_PATH_RE = re.compile(r"(?<![\w/])((?:[\w.-]+/)+[\w.-]+\.\w+)")


def _requested_files(reason: str) -> list[str]:
    """Repository paths named in a BLOCKED reason, in order, deduplicated."""
    seen: list[str] = []
    for m in _PATH_RE.findall(reason):
        if m not in seen:
            seen.append(m)
    return seen
