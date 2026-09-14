"""
Node-kind handlers: how each kind of node in workflow.yaml is executed.
  agent    -> AGENTS[node.agent](llm)
  executor -> run plan.tasks as a task-level DAG (task deps + parallel groups), HIGH tasks pause for approval
  gate     -> run_gates(node.gates) and convert the ValidationResult into Success/Retry
  input    -> re-run the requirements agent with human answers -> spec v2 (invalidation follows automatically)

Where it sits: ``api -> service -> engine -> {agents, executors, sandbox, trace, store}``. ``service.py``
calls ``build_handlers`` once with the LLM client, the code executor and the loaded ``Graph``; the result is
the ``handlers`` dict the ``Runner`` indexes by ``NodeDef.kind``. Approval nodes need no handler: the runner's
entry gate resolves them. Handlers know nothing about retries, budgets or state persistence: they return an
``Outcome`` and the runner does the rest.

Invariants
- The graph is data: no handler names a workflow node. The only agent name in this file is ``diagnoser``
  (its output is a ``Route``, not an artifact) and ``requirements`` (the input node re-runs it).
- Specs before code: the executor implements ``Plan.TaskSpec`` objects against the ``Design``; it never sees
  the requirement text.
- Policy is law at the write boundary: every task commit is scope-checked and scanned *before* it counts,
  and a violation is reverted (``ROLLED_BACK``) rather than left for a later gate.
- Gates decide: the gate handler stores the ``ValidationResult`` under ``produces`` whether it passed or not,
  so the roll-up node and the diagnoser can read failed results.
- Human checkpoints are never bypassed: a HIGH task or a scope request returns ``NeedsApproval``; only the
  approval token appearing in ``ctx.approvals`` (human, or replay auto-approve by policy) lets it proceed.

Trace events emitted here: POLICY_DECISION (statuses OK, VIOLATION, APPROVED, SCOPE_REQUESTED,
SCOPE_APPROVED), ROLLED_BACK, plus ARTIFACT_WRITTEN via ``ctx.put`` and through ``provision_sandbox`` /
``write_architecture_contract``; ``run_gates`` emits GATE_RESULT.
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
    """Build the ``kind -> handler`` table the ``Runner`` dispatches on.

    Args:
        llm: structured-output client every agent is instantiated with (one agent instance per name).
        executor: applies a ``TaskSpec`` to the sandbox (Claude Code CLI, replay, recording, fake).
        graph: the loaded workflow, needed for roll-up lookups (which gate nodes fold into which).

    Returns:
        ``{"agent", "executor", "gate", "input"}`` -> ``async (NodeDef, RunContext) -> Outcome``. The
        handlers are closures over these three arguments; there is no other shared state.
    """
    agents = {name: cls(llm) for name, cls in AGENTS.items()}
    rolled_up = {r for n in graph.nodes.values() for r in n.rolls_up}  # gate nodes another gate node folds in

    async def agent_handler(node: NodeDef, ctx: RunContext) -> Outcome:
        """kind=agent: run the agent named by ``node.agent`` and pass its outcome through.

        The diagnoser is special-cased: its ``Diagnosis`` artifact carries a ``decision`` (retry | replan |
        halt) that must select an ``on_result`` branch in workflow.yaml, so a ``Success`` is converted into a
        ``Route`` whose feedback (items + root cause) the runner attaches to the re-run producers.
        """
        out = await agents[node.agent](node, ctx)  # type: ignore[index]
        if node.agent == "diagnoser" and isinstance(out, Success):
            d = out.artifacts["diagnosis"]
            fb = {"items": [i.model_dump() for i in d.feedback], "root_cause": d.root_cause}
            return Route(d.decision, fb)
        return out

    async def executor_handler(node: NodeDef, ctx: RunContext) -> Outcome:
        """kind=executor: run ``plan.tasks`` as a task-level DAG inside the run's git branch.

        Setup (idempotent on every entry, including re-entry after an approval pause or a retry): ensure the
        sandbox repo, check out ``<branch_prefix><run_id>``, provision the stack baseline, regenerate the
        architecture contract, and, when no task has committed yet, tag the run base so the orchestrator's
        own commits are never judged as agent changes. The ``changeset`` artifact carries progress across
        pauses: tasks already in ``cs.commits`` are not re-run.

        Loop: pick the tasks whose ``depends_on`` are all committed; require a ``task:<id>`` approval for
        each ``requires_approval`` task (pause with ``task.high_impact`` if missing); run one parallel group
        at a time, task by task, in the shared working tree. For each ``Done`` result: map approvals onto the
        high-impact actions of the protected paths touched, check scope, scan contents, record a
        ``POLICY_DECISION``; on a violation revert the commit (``ROLLED_BACK``) and retry/ block per task
        allowance. A ``BlockedTask`` naming files outside its scope pauses for ``task.scope_change``; an
        ``Errored`` result retries when transient, else blocks the node.

        Returns:
            ``Success`` (with ``changeset`` unless it is already in context), ``Retry`` with per-task
            feedback, ``Blocked`` (unsatisfiable deps, exhausted task, non-transient error) or
            ``NeedsApproval`` (``task.high_impact`` / ``task.scope_change``).

        Side effects: git branch/tag/commits/reverts in the sandbox; ``ctx.approvals`` grows with task,
        scope and action tokens; ``ctx.feedback[node.id]`` carries ``task_attempts`` / ``scope_request`` /
        ``scope_change``; ``changeset`` may be put into context before a pause.
        """
        plan: Plan = ctx.get("plan")
        design = ctx.get("design")
        git = GitSandbox(ctx.sandbox)
        await git.ensure_repo()
        branch = f"{ctx.policy.sandbox.branch_prefix}{ctx.run_id}"
        await git.start_run_branch(branch)
        await provision_sandbox(ctx, git, node.id)  # baseline the agent may not write (java: mvnw)
        await write_architecture_contract(ctx, git, node.id)  # the gate's test is never agent-authored
        cs: Changeset = ctx.get("changeset") or Changeset(branch=branch)
        if not cs.commits:  # the baseline the orchestrator just committed is not an agent change
            await git.set_run_base()
        feedback = ctx.feedback.get(node.id)
        pe = PolicyEngine(ctx.policy, ctx.target_stack)
        # task id -> failed attempts so far; carried across node attempts inside the Retry feedback so one
        # flaky task cannot consume the whole node's retry budget unnoticed
        task_attempts: dict[str, int] = dict((feedback or {}).get("task_attempts", {}))

        def task_failed(t: TaskSpec, reason: str, extra: dict[str, Any]) -> Outcome:
            """One task's failure is retried per task (policy.budgets.max_attempts_per_task); a task
            that exhausts its own allowance blocks the node (safe-stop) instead of burning the other
            tasks' retries.

            Side effect: increments ``task_attempts[t.id]``; the updated map travels in the ``Retry``
            feedback so the next node attempt resumes the count.
            """
            task_attempts[t.id] = task_attempts.get(t.id, 0) + 1
            if task_attempts[t.id] >= ctx.policy.budgets.max_attempts_per_task:
                return Blocked(f"task {t.id} failed {task_attempts[t.id]} times: {reason}")
            return Retry(reason, {**extra, "task": t.id, "task_attempts": task_attempts})

        # a task that reported BLOCKED naming the files it needs paused the node for a scope approval: the
        # human approving the node grants exactly those files (scope tokens, revoked on re-plan like the rest)
        pending = (ctx.feedback.get(node.id) or {}).get("scope_request")
        if pending and node.id in ctx.approvals:
            # convert the coarse node approval into precise per-file grants; the node token must not linger
            # or the next HIGH task would read it as its own approval
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
            """``t`` with every human-granted ``scope:<t.id>:<path>`` token appended to ``allowed_files``.

            Returns the original object when nothing was granted, so identity comparisons stay valid; the
            difference between the widened and the original ``allowed_files`` is what ``granted`` measures.
            """
            extra = sorted(a.split(":", 2)[2] for a in ctx.approvals if a.startswith(f"scope:{t.id}:"))
            return t.model_copy(update={"allowed_files": [*t.allowed_files, *extra]}) if extra else t

        remaining = {t.id: t for t in plan.tasks if t.id not in cs.commits}  # resume: skip committed tasks
        while remaining:
            ready = [t for t in remaining.values() if all(d in cs.commits for d in t.depends_on)]
            if not ready:
                return Blocked("plan has unsatisfiable task dependencies")
            for t in ready:  # task-level high-impact approval
                if t.requires_approval and f"task:{t.id}" not in ctx.approvals:
                    if node.id in ctx.approvals:  # human approved the node while it was paused on this task
                        # the node token is consumed and narrowed to this task; a later HIGH task pauses again
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
                        # persist progress so the resumed node skips the tasks already committed
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
                if isinstance(r, Done) and r.granted_scope:  # a recorded run's human grant, replayed with it
                    ctx.approvals |= {f"scope:{t.id}:{f}" for f in r.granted_scope}
                    ctx.emit(
                        Kind.POLICY_DECISION,
                        node_id=node.id,
                        task_id=t.id,
                        actor="policy",
                        status="SCOPE_APPROVED",
                        payload={
                            "action": "task.scope_change",
                            "files": r.granted_scope,
                            "source": "recorded",
                        },
                    )
                    t = widened(original)
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
                    # a scope grant is also a human decision about those exact files: if one of them is
                    # protected, the grant carries the action it requires
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
                        # the task's commit is undone before it can count; the findings become the next
                        # attempt's feedback so the agent fixes the violation rather than repeating it
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
                    accepted = getattr(executor, "accepted", None)
                    if (
                        accepted is not None
                    ):  # e.g. RecordingExecutor promotes this attempt to the replayable one
                        await accepted(ctx, t, r, sorted(granted))
                    cs.commits[t.id] = r.commit_sha
                    cs.files_changed += r.files_changed
                    cs.tests_added += r.tests_added
                    cs.notes[t.id] = r.notes
                    remaining.pop(t.id)
                elif isinstance(r, BlockedTask):
                    # only files the agent could legitimately be granted count: already-allowed ones are not
                    # a scope problem, and forbidden/outside paths can never be granted
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
        """kind=gate: run the node's gates, fold in rolled-up results, record the verdict.

        ``attempt`` is derived from the last ``Retry`` feedback (the runner stores ``attempt`` there) so the
        recorded ``ValidationResult`` and the ``GATE_RESULT`` events carry the node attempt number. The
        rolled-up outcomes (from the ``ValidationResult`` artifacts of the nodes in ``rolls_up``) come first
        so the verdict lists them in graph order.

        Returns ``Success`` when the combined result passed OR when this node is itself rolled up by another
        gate node: a rolled-up node (unit_tests, integration_tests) only records; the roll-up (validation)
        is the single node that fails into ``diagnose``, which keeps one fail-path instead of three.
        Otherwise ``Retry`` with up to 25 blocking findings as feedback.
        """
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
        """kind=input: once every ambiguity is answered (runner checks ``ctx.answers``), re-run requirements.

        The requirements agent folds the answers into a new ``Spec``; because ``spec`` already exists in
        context the put replaces it (v2), and the runner's invalidation re-runs planning and downstream.
        """
        return await agents["requirements"](node, ctx)  # produces spec v2 with answers folded in

    return {
        "agent": agent_handler,
        "executor": executor_handler,
        "gate": gate_handler,
        "input": input_handler,
    }


def _read_all(root: Path, files: list[str]) -> dict[str, str]:
    """Contents of the given repo-relative ``files`` under ``root`` (sync; run via ``asyncio.to_thread``).

    Deleted paths are skipped (they cannot leak a secret); undecodable bytes are replaced rather than raised
    so a binary file does not turn the write-boundary scan into a handler error.
    """
    out: dict[str, str] = {}
    for f in files:
        p = root / f
        if p.is_file():
            out[f] = p.read_text(encoding="utf-8", errors="replace")
    return out


# a repository path in free text: at least one directory segment, a file name with an extension, not glued to
# a preceding word or slash (so "src/app/x.py" matches but the tail of "/abs/path/x.py" is not re-matched)
_PATH_RE = re.compile(r"(?<![\w/])((?:[\w.-]+/)+[\w.-]+\.\w+)")


def _requested_files(reason: str) -> list[str]:
    """Repository paths named in a BLOCKED reason, in order, deduplicated."""
    seen: list[str] = []
    for m in _PATH_RE.findall(reason):
        if m not in seen:
            seen.append(m)
    return seen
