"""
Governed DAG runner.

- readiness: a node is READY when every dependency is PASSED/SKIPPED (or FAILED, for the dependency's
  fallback)
- parallel paths: all READY nodes run concurrently (bounded by policy.budgets.parallelism); the batch is
  awaited before readiness is recomputed => join semantics at every dependent
- gates: approval/input nodes pause the run; only a human (or replay auto-approve) resumes it
- bounded retries with exponential backoff; structured feedback is stored for the next attempt
- exhaustion: fallback node if declared, else rollback hook, then continue or halt per policy
- safe-stop: budget/policy/replan-limit triggers persist state and HALT; the run is resumable
- re-planning: artifacts re-produced with a new version invalidate consumers (+ downstream), which re-run
"""

from __future__ import annotations

import asyncio
import time
from collections.abc import Awaitable, Callable
from datetime import UTC, datetime
from typing import Protocol

from ..models.state import RUNNABLE, NodeStatus, RunState, RunStatus
from ..models.trace import Kind
from .conditions import evaluate
from .context import RunContext
from .graph import Graph, NodeDef
from .outcomes import Blocked, NeedsApproval, NeedsInput, Outcome, Retry, Route, Skip, Success

Handler = Callable[[NodeDef, RunContext], Awaitable[Outcome]]


class StateStore(Protocol):
    async def save(self, state: RunState) -> None: ...
    async def load(self, run_id: str) -> RunState: ...


class RollbackHook(Protocol):
    async def rollback(self, ctx: RunContext, node: NodeDef) -> None: ...


class NoRollback:
    async def rollback(self, ctx: RunContext, node: NodeDef) -> None:
        return None


class Runner:
    def __init__(
        self,
        graph: Graph,
        handlers: dict[str, Handler],
        store: StateStore,
        rollback: RollbackHook | None = None,
        sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
    ) -> None:
        self.graph = graph
        self.handlers = handlers  # keyed by node kind ("agent", "executor", "gate", "approval", "input")
        self.store = store
        self.rollback = rollback or NoRollback()
        self._sleep = sleep

    # ------------------------------------------------------------------ public API
    async def run(self, ctx: RunContext, state: RunState) -> RunState:
        if state.status in (RunStatus.HALTED, RunStatus.COMPLETED):
            return state
        state.status = RunStatus.RUNNING
        ctx.emit(Kind.RUN_STARTED, payload={"scenario": ctx.scenario, "lineage": ctx.lineage_snapshot()})
        pol = ctx.policy

        while True:
            if (reason := self._budget_trip(state, ctx)) is not None:
                return await self._halt(ctx, state, reason)

            ready = self.graph.ready(state)
            if not ready:
                if self.graph.all_done(state):
                    state.status = RunStatus.COMPLETED
                    ctx.emit(Kind.RUN_COMPLETED, payload={"lineage": ctx.lineage_snapshot()})
                else:
                    state.status = state.status if state.status != RunStatus.RUNNING else RunStatus.FAILED
                await self.store.save(state)
                return state

            batch = ready[: pol.budgets.parallelism]
            for n in batch:
                state.mark(n.id, NodeStatus.RUNNING)
            await self.store.save(state)

            results = await asyncio.gather(*(self._execute(n, ctx, state) for n in batch))  # join point

            paused = False
            for node, outcome in zip(batch, results, strict=True):
                paused |= await self._apply(node, outcome, ctx, state)
            await self.store.save(state)
            if state.status == RunStatus.HALTED:
                return state
            if paused:
                return state

    async def approve(self, ctx: RunContext, state: RunState, node_id: str, approver: str) -> RunState:
        ctx.approvals.add(node_id)
        ctx.emit(Kind.APPROVAL_GRANTED, node_id=node_id, actor=approver, status="APPROVED")
        state.mark(node_id, NodeStatus.PENDING)
        return await self.run(ctx, state)

    async def reject(
        self, ctx: RunContext, state: RunState, node_id: str, approver: str, reason: str
    ) -> RunState:
        ctx.emit(
            Kind.APPROVAL_REJECTED,
            node_id=node_id,
            actor=approver,
            status="REJECTED",
            payload={"reason": reason},
        )
        return await self._halt(ctx, state, "human.reject")

    async def answer(
        self, ctx: RunContext, state: RunState, node_id: str, answers: dict[str, str], who: str
    ) -> RunState:
        ctx.answers.update(answers)
        ctx.emit(Kind.INPUT_RECEIVED, node_id=node_id, actor=who, payload={"answers": answers})
        state.pending_questions = []
        state.mark(node_id, NodeStatus.PENDING)
        return await self.run(ctx, state)

    def invalidate(
        self, ctx: RunContext, state: RunState, changed: set[str], origin: str, *, reproduce: bool = False
    ) -> set[str]:
        """Mark consumers of changed artifacts (+ downstream) for re-run. Never re-runs the origin node.
        reproduce=True also re-runs the artifacts' producers (used by diagnose -> retry/replan)."""
        targets = (
            self.graph.reproduce_targets(changed)
            if reproduce
            else self.graph.invalidated_by_artifacts(changed)
        ) - {origin}
        hit = set()
        for nid in targets:
            if state.nodes[nid] not in (NodeStatus.PENDING, NodeStatus.RUNNING):
                state.mark(nid, NodeStatus.INVALIDATED)
                hit.add(nid)
                ctx.emit(
                    Kind.NODE_INVALIDATED,
                    node_id=nid,
                    actor="orchestrator",
                    payload={"because": sorted(changed), "origin": origin},
                )
        # never bypass a human checkpoint: an invalidated approval node needs a fresh approval, and a new
        # plan voids task-level approvals (and the high-impact actions they were mapped onto)
        revoked = {a for a in ctx.approvals if a in hit}
        if "plan" in changed:
            revoked |= {
                a for a in ctx.approvals if a not in self.graph.nodes
            }  # task:<id> tokens + action names
        if revoked:
            ctx.approvals -= revoked
            ctx.emit(
                Kind.POLICY_DECISION,
                node_id=origin,
                actor="policy",
                status="APPROVAL_REVOKED",
                payload={"revoked": sorted(revoked), "because": sorted(changed)},
            )
        return hit

    # ------------------------------------------------------------------ execution of one node
    async def _execute(self, node: NodeDef, ctx: RunContext, state: RunState) -> Outcome:
        pol = ctx.policy
        # `when` condition -> SKIP
        if node.when and not evaluate(node.when, ctx):
            return Skip(f"condition false: {node.when}")
        # entry gate: approvals
        if node.kind == "approval" or node.high_impact:
            action = node.high_impact or "unknown"
            ctx.emit(
                Kind.NODE_ENTRY_GATE,
                node_id=node.id,
                actor="gate",
                status="approval_check",
                payload={"action": action},
            )
            if node.id in ctx.approvals:
                pass
            elif ctx.replay and pol.autonomy.auto_approve_in_replay:
                ctx.emit(
                    Kind.POLICY_DECISION,
                    node_id=node.id,
                    actor="policy",
                    status="AUTO_APPROVED",
                    payload={"action": action, "reason": "replay mode"},
                )
                ctx.approvals.add(node.id)
            else:
                summary = {k: str(ctx.get(k))[:500] for k in node.summary_from if ctx.get(k) is not None}
                return NeedsApproval(action, summary)
            if node.kind == "approval":
                return Success()
        # input nodes: pause until answered
        if node.kind == "input":
            spec = ctx.get("spec")
            questions = [a.model_dump() for a in getattr(spec, "ambiguities", [])] if spec else []
            unanswered = [q for q in questions if q["id"] not in ctx.answers]
            if unanswered and not ctx.replay:
                return NeedsInput(unanswered)
            handler = self.handlers.get("input")
            return await handler(node, ctx) if handler else Success()

        handler = self.handlers[node.kind]
        max_attempts = int(node.retries.get("max_attempts", pol.budgets.max_attempts_per_task))
        delay = float(pol.retries.initial_delay_seconds)
        last: Outcome = Blocked("no attempts")
        for attempt in range(1, max_attempts + 1):
            ctx.emit(Kind.ATTEMPT_STARTED, node_id=node.id, attempt=attempt, actor=node.agent or node.kind)
            started = time.monotonic()
            try:
                last = await asyncio.wait_for(handler(node, ctx), timeout=pol.budgets.node_timeout_seconds)
            except TimeoutError:
                last = Retry("node timeout")
            except Exception as e:  # handler bugs are attempts, not crashes
                last = Retry(f"handler error: {type(e).__name__}: {e}")
            took = round(time.monotonic() - started, 3)
            if isinstance(last, Retry):
                ctx.feedback[node.id] = dict(last.feedback, reason=last.reason, attempt=attempt)
                ctx.emit(
                    Kind.ATTEMPT_FAILED,
                    node_id=node.id,
                    attempt=attempt,
                    status="RETRY",
                    payload={"reason": last.reason, "took_s": took},
                )
                if attempt < max_attempts:
                    await self._sleep(min(delay, pol.retries.max_delay_seconds))
                    delay *= 2
                continue
            ctx.emit(
                Kind.ATTEMPT_PASSED if isinstance(last, Success | Route) else Kind.ATTEMPT_FAILED,
                node_id=node.id,
                attempt=attempt,
                status=type(last).__name__,
                payload={"took_s": took},
            )
            return last
        return last  # Retry after exhaustion -> handled in _apply

    # ------------------------------------------------------------------ apply outcome to state
    async def _apply(self, node: NodeDef, out: Outcome, ctx: RunContext, state: RunState) -> bool:
        """Returns True when the run must pause for a human."""
        match out:
            case Success(artifacts=arts, tokens_in=ti, tokens_out=to, cost_usd=c):
                state.budget.tokens_used += ti + to
                state.budget.cost_usd += c
                changed = {name for name, val in arts.items() if ctx.put(name, val, node.id)}
                state.mark(node.id, NodeStatus.PASSED)
                ctx.emit(Kind.NODE_PASSED, node_id=node.id, payload={"lineage": ctx.lineage_snapshot()})
                if node.fallback and state.nodes[node.fallback] in RUNNABLE:
                    state.mark(node.fallback, NodeStatus.SKIPPED)  # fail-path not needed this time
                    ctx.emit(
                        Kind.NODE_SKIPPED, node_id=node.fallback, payload={"reason": f"{node.id} passed"}
                    )
                if changed:  # upstream output changed -> re-plan downstream
                    ctx.emit(Kind.REPLAN_TRIGGERED, node_id=node.id, payload={"changed": sorted(changed)})
                    self.invalidate(ctx, state, changed, origin=node.id)
                return False

            case Skip(reason=r):
                state.mark(node.id, NodeStatus.SKIPPED)
                ctx.emit(Kind.NODE_SKIPPED, node_id=node.id, payload={"reason": r})
                return False

            case NeedsApproval(action=a, summary=s):
                state.mark(node.id, NodeStatus.AWAITING_APPROVAL)
                state.status = RunStatus.AWAITING_APPROVAL
                ctx.emit(
                    Kind.APPROVAL_REQUESTED,
                    node_id=node.id,
                    actor="gate",
                    status="PENDING",
                    payload={"action": a, "summary": s},
                )
                return True

            case NeedsInput(questions=qs):
                state.mark(node.id, NodeStatus.AWAITING_INPUT)
                state.status = RunStatus.AWAITING_INPUT
                state.pending_questions = [q["question"] for q in qs]
                ctx.emit(
                    Kind.INPUT_REQUESTED,
                    node_id=node.id,
                    actor="gate",
                    status="PENDING",
                    payload={"questions": qs},
                )
                return True

            case Route(result=r, feedback=fb):
                branch = node.on_result.get(r)
                if branch is None:
                    await self._halt(ctx, state, f"{node.id}.unknown_route:{r}")
                    return True
                state.mark(node.id, NodeStatus.PASSED)
                ctx.emit(Kind.NODE_PASSED, node_id=node.id, status=r, payload=fb)
                if "safe_stop" in branch:
                    await self._halt(ctx, state, branch["safe_stop"])
                    return True
                if "invalidate" in branch:
                    changed = set(branch["invalidate"])
                    if "plan" in changed:
                        state.budget.replans += 1
                        if state.budget.replans > ctx.policy.budgets.max_replans_per_run:
                            await self._halt(ctx, state, "replan.limit_reached")
                            return True
                        state.plan_version += 1
                    for target in self.graph.producers_of(changed):
                        ctx.feedback.setdefault(target, {}).update(fb)
                    ctx.emit(
                        Kind.REPLAN_TRIGGERED,
                        node_id=node.id,
                        payload={"changed": sorted(changed), "route": r},
                    )
                    self.invalidate(ctx, state, changed, origin=node.id, reproduce=True)
                state.mark(node.id, NodeStatus.PENDING)  # a routing node is re-entrant
                return False

            case Blocked(reason=r):
                state.mark(node.id, NodeStatus.FAILED)
                ctx.emit(Kind.NODE_FAILED, node_id=node.id, status="BLOCKED", payload={"reason": r})
                await self._halt(
                    ctx, state, "policy.violation" if "policy" in r.lower() else f"{node.id}.blocked"
                )
                return True

            case Retry(reason=r):  # retries exhausted
                if node.fallback:
                    state.mark(node.id, NodeStatus.FAILED)
                    ctx.emit(
                        Kind.FALLBACK_TAKEN, node_id=node.id, payload={"reason": r, "fallback": node.fallback}
                    )
                    if state.nodes[node.fallback] == NodeStatus.SKIPPED:
                        state.mark(node.fallback, NodeStatus.PENDING)
                    return False
                await self.rollback.rollback(ctx, node)
                state.mark(node.id, NodeStatus.ROLLED_BACK)
                ctx.emit(Kind.ROLLED_BACK, node_id=node.id, payload={"reason": r})
                if ctx.policy.retries.on_exhausted.endswith("halt"):
                    await self._halt(ctx, state, "gate.repeated_failure")
                    return True
                state.mark(node.id, NodeStatus.FAILED)  # dependents cannot proceed; run ends FAILED
                return False
        return False

    # ------------------------------------------------------------------ helpers
    def _budget_trip(self, state: RunState, ctx: RunContext) -> str | None:
        b = ctx.policy.budgets
        elapsed = (datetime.now(UTC) - state.budget.started_at).total_seconds() / 60
        if state.budget.cost_usd > b.max_cost_usd_per_run:
            return "budget.exceeded:cost"
        if state.budget.tokens_used > b.max_tokens_per_run:
            return "budget.exceeded:tokens"
        if elapsed > b.max_wall_clock_minutes:
            return "budget.exceeded:wall_clock"
        return None

    async def _halt(self, ctx: RunContext, state: RunState, reason: str) -> RunState:
        state.status = RunStatus.HALTED
        state.halt_reason = reason
        ctx.emit(
            Kind.RUN_HALTED, status="HALTED", payload={"trigger": reason, "lineage": ctx.lineage_snapshot()}
        )
        await self.store.save(state)
        return state
