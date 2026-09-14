"""
Node-kind handlers: how each kind of node in workflow.yaml is executed.
  agent    -> AGENTS[node.agent](llm)
  executor -> run plan.tasks as a task-level DAG (task deps + parallel groups), HIGH tasks pause for approval
  gate     -> run_gates(node.gates) and convert the ValidationResult into Success/Retry
  input    -> re-run the requirements agent with human answers -> spec v2 (invalidation follows automatically)
"""

from __future__ import annotations

import asyncio
from typing import Any

from ..agents.catalog import AGENTS
from ..executors.base import BlockedTask, Changeset, CodeExecutor, Done, Errored
from ..llm.client import LLMClient
from ..models import Plan, TaskSpec
from ..models.trace import Kind
from ..sandbox.git import GitSandbox
from .arch_contract import write_architecture_contract
from .context import RunContext
from .gates import run_gates
from .graph import NodeDef
from .outcomes import Blocked, NeedsApproval, Outcome, Retry, Route, Success
from .policy_engine import PolicyEngine


def build_handlers(llm: LLMClient, executor: CodeExecutor) -> dict[str, Any]:
    agents = {name: cls(llm) for name, cls in AGENTS.items()}

    async def agent_handler(node: NodeDef, ctx: RunContext) -> Outcome:
        out = await agents[node.agent](node, ctx)  # type: ignore[index]
        if node.agent == "diagnoser" and isinstance(out, Success):
            d = out.artifacts["diagnosis"]
            return Route(d.decision, dict(d.feedback, root_cause=d.root_cause))
        return out

    async def executor_handler(node: NodeDef, ctx: RunContext) -> Outcome:
        plan: Plan = ctx.get("plan")
        design = ctx.get("design")
        git = GitSandbox(ctx.sandbox)
        await git.ensure_repo()
        branch = f"{ctx.policy.sandbox.branch_prefix}{ctx.run_id}"
        await git.start_run_branch(branch)
        await write_architecture_contract(ctx, git, node.id)  # the gate's test is never agent-authored
        cs: Changeset = ctx.get("changeset") or Changeset(branch=branch)
        feedback = ctx.feedback.get(node.id)
        pe = PolicyEngine(ctx.policy, ctx.target_stack)

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
            batch = next(iter(groups.values()))  # one group at a time; group members run concurrently
            results = await asyncio.gather(*(executor.execute(ctx, t, design, feedback) for t in batch))
            for t, r in zip(batch, results, strict=True):
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
                    verdict = pe.check_scope(r.files_changed, t.allowed_files, approved)
                    ctx.emit(
                        Kind.POLICY_DECISION,
                        task_id=t.id,
                        actor="policy",
                        status="OK" if verdict.ok else "VIOLATION",
                        payload={
                            "findings": [f.model_dump() for f in verdict.findings],
                            "approved_actions": sorted(approved),
                        },
                    )
                    if not verdict.ok:
                        await git.revert(r.commit_sha)
                        return Retry(
                            f"task {t.id} violated change control",
                            {"task": t.id, "findings": [f.model_dump() for f in verdict.findings]},
                        )
                    cs.commits[t.id] = r.commit_sha
                    cs.files_changed += r.files_changed
                    cs.tests_added += r.tests_added
                    cs.notes[t.id] = r.notes
                    remaining.pop(t.id)
                elif isinstance(r, BlockedTask):
                    return Retry(f"task {t.id} blocked: {r.reason}", {"task": t.id, "reason": r.reason})
                elif isinstance(r, Errored):
                    return Retry(f"task {t.id} errored: {r.reason}") if r.transient else Blocked(r.reason)
        if ctx.get("changeset") is cs:
            # already in context from an approval pause inside this node; the object carries every commit,
            # so a second put would only bump the version and read as a replan downstream
            return Success()
        return Success({"changeset": cs})

    async def gate_handler(node: NodeDef, ctx: RunContext) -> Outcome:
        attempt = (ctx.feedback.get(node.id) or {}).get("attempt", 0) + 1
        vr = await run_gates(list(node.gates), ctx, task_id=node.id, attempt=attempt)
        for name in node.produces:
            # stored once, pass or fail: the diagnoser reads a failed validation_result.
            # (`run_report` holds the release gate's result until TASKS T11 renders the real report.)
            ctx.put(name, vr, node.id)
        if vr.passed:
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
