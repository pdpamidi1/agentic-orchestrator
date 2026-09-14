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

Where it sits: ``api -> service -> engine -> {agents, executors, sandbox, trace, store}``. ``service.py`` (the
composition root) builds one ``Runner`` from the ``Graph`` (workflow.yaml), the node-kind handlers
(``engine/handlers.py``), a ``StateStore`` and a ``RollbackHook``; the API/CLI call ``run``, ``approve``,
``reject`` and ``answer``. The runner owns every ``RunState`` transition; handlers only return ``Outcome``s.

Invariants
- The graph is data: nothing here knows a node by name. Behaviour comes from ``NodeDef`` fields (``kind``,
  ``when``, ``high_impact``, ``fallback``, ``retries``, ``on_result``) and from ``policy.yaml`` budgets.
- ``RunState`` is saved after every batch and on every halt, so a run can be resumed from the store.
- Human checkpoints are never bypassed: ``AWAITING_APPROVAL``/``AWAITING_INPUT`` only advance through
  ``approve``/``answer``; auto-approval exists only in replay mode and is itself a ``POLICY_DECISION``.
- Handler exceptions and timeouts are attempt failures (``Retry``), never a crash of the run.
- ``_apply`` is the single ``match`` over the closed ``Outcome`` set; a new outcome needs a new ``case``.

Trace events emitted here: RUN_STARTED, RUN_COMPLETED, RUN_HALTED, RUN_RESUMED, NODE_ENTRY_GATE,
POLICY_DECISION (AUTO_APPROVED, APPROVAL_REVOKED), ATTEMPT_STARTED, ATTEMPT_PASSED, ATTEMPT_FAILED,
NODE_PASSED, NODE_SKIPPED, NODE_FAILED, NODE_INVALIDATED, REPLAN_TRIGGERED, FALLBACK_TAKEN, ROLLED_BACK,
APPROVAL_REQUESTED, APPROVAL_GRANTED, APPROVAL_REJECTED, INPUT_REQUESTED, INPUT_RECEIVED.
"""

from __future__ import annotations

import asyncio
import time
from collections.abc import Awaitable, Callable
from typing import Protocol

from ..models.common import utcnow
from ..models.state import RUNNABLE, NodeStatus, RunState, RunStatus
from ..models.trace import Kind
from .brief import build_brief
from .conditions import evaluate
from .context import RunContext
from .graph import Graph, NodeDef
from .outcomes import Blocked, NeedsApproval, NeedsInput, Outcome, Retry, Route, Skip, Success

# one node attempt: the handler for the node's kind, given the definition and the shared context
Handler = Callable[[NodeDef, RunContext], Awaitable[Outcome]]


class StateStore(Protocol):
    """Persistence for ``RunState`` (``store/file_store.py`` today; Postgres is a TASKS item).

    The runner calls ``save`` after every batch, on every pause and on every halt; ``load`` is used by the
    service layer to resume. Implementations must be safe to call repeatedly with the same run id.
    """

    async def save(self, state: RunState) -> None:
        """Persist ``state`` (full overwrite of the run's current state)."""
        ...

    async def load(self, run_id: str) -> RunState:
        """Return the last persisted state of ``run_id``; raises if the run is unknown to the store."""
        ...


class RollbackHook(Protocol):
    """Strategy applied when a node exhausts its retries and has no ``fallback``.

    ``service.GitRollback`` reverts the run branch's task commits (``NodeDef.rollback`` names the strategy
    in workflow.yaml); tests inject fakes. Called before the node is marked ROLLED_BACK.
    """

    async def rollback(self, ctx: RunContext, node: NodeDef) -> None:
        """Undo the sandbox effects of ``node`` (e.g. revert its task commits); safe on a clean tree."""
        ...


class NoRollback:
    """Default ``RollbackHook`` that does nothing; used when the composition root passes ``None``."""

    async def rollback(self, ctx: RunContext, node: NodeDef) -> None:
        """No-op: the node is still marked ROLLED_BACK and the event is still emitted by the runner."""
        return None


class Runner:
    """Executes a ``Graph`` over a ``RunState`` until it completes, halts or pauses for a human.

    Lifecycle: constructed once per process by ``service.py``; ``run`` is re-entered by ``approve`` and
    ``answer`` on the same ``ctx``/``state`` pair. The runner holds no per-run state of its own: everything
    lives in ``RunState`` (persisted) and ``RunContext`` (artifacts, approvals, feedback).
    """

    def __init__(
        self,
        graph: Graph,
        handlers: dict[str, Handler],
        store: StateStore,
        rollback: RollbackHook | None = None,
        sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
    ) -> None:
        """Wire the collaborators.

        Args:
            graph: the loaded workflow.yaml.
            handlers: node-kind -> handler (``build_handlers``); ``approval`` needs none.
            store: where ``RunState`` is persisted.
            rollback: hook applied on retry exhaustion without a fallback; ``None`` -> ``NoRollback``.
            sleep: backoff sleeper, injectable so tests do not wait for real seconds.
        """
        self.graph = graph
        self.handlers = handlers  # keyed by node kind ("agent", "executor", "gate", "approval", "input")
        self.store = store
        self.rollback = rollback or NoRollback()
        self._sleep = sleep

    # ------------------------------------------------------------------ public API
    async def run(self, ctx: RunContext, state: RunState) -> RunState:
        """Drive the run until COMPLETED, HALTED, FAILED or paused (AWAITING_APPROVAL / AWAITING_INPUT).

        Each loop iteration: check the run budget (cost, tokens, wall clock -> safe-stop), compute the ready
        set, take at most ``policy.budgets.parallelism`` nodes, mark them RUNNING, execute them concurrently,
        then apply every outcome in order and persist. Returning from ``run`` never loses work: the state
        is saved before every return.

        Terminal cases: no ready node and every node PASSED/SKIPPED -> COMPLETED (``RUN_COMPLETED``); no
        ready node but unfinished nodes remain (a FAILED node with no fallback blocks its dependents) ->
        FAILED unless the status was already changed by a halt.

        Args:
            ctx: run context shared with the handlers.
            state: mutable run state; returned after the last transition.

        Emits ``RUN_STARTED`` on entry (also on each resume through ``approve``/``answer``), ``RUN_COMPLETED``
        on completion; everything else comes from ``_execute`` / ``_apply`` / ``_halt``.
        """
        if state.status in (RunStatus.HALTED, RunStatus.COMPLETED):
            return state
        state.status = RunStatus.RUNNING
        state.budget.unpause()  # a human wait (approval, answer, halt review) is not run time
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
                    # stuck: a node FAILED without a fallback, so its dependents can never become ready
                    state.status = state.status if state.status != RunStatus.RUNNING else RunStatus.FAILED
                await self.store.save(state)
                return state

            batch = ready[: pol.budgets.parallelism]
            for n in batch:
                state.mark(n.id, NodeStatus.RUNNING)
            await self.store.save(state)

            results = await asyncio.gather(*(self._execute(n, ctx, state) for n in batch))  # join point

            # every outcome of the batch is applied even when an earlier one paused or halted the run, so a
            # sibling's artifacts and events are never lost; the loop exits after the whole batch
            paused = False
            for node, outcome in zip(batch, results, strict=True):
                paused |= await self._apply(node, outcome, ctx, state)
            if paused and state.status != RunStatus.HALTED:
                state.budget.pause()  # AWAITING_*: the wall clock stops until a human answers
            await self.store.save(state)
            if state.status == RunStatus.HALTED:
                return state
            if paused:
                return state

    async def approve(self, ctx: RunContext, state: RunState, node_id: str, approver: str) -> RunState:
        """A human approved ``node_id``; record it and resume the run.

        ``node_id`` is an approval node, or the executor node paused on a HIGH task / scope request (the
        executor handler converts that node token into ``task:<id>`` or ``scope:<task>:<path>`` tokens).
        Also the resume path after a safe-stop: a HALTED run is set back to RUNNING and the node's
        ``task_attempts`` feedback is cleared so the reviewer's decision to continue gives the task a fresh
        allowance. The node is re-queued as PENDING; its entry gate then finds the approval.

        Emits ``APPROVAL_GRANTED`` (actor = approver) and, when resuming from a halt, ``RUN_RESUMED`` with
        the previous halt reason.
        """
        ctx.approvals.add(node_id)
        ctx.emit(Kind.APPROVAL_GRANTED, node_id=node_id, actor=approver, status="APPROVED")
        if state.status == RunStatus.HALTED:  # safe-stop is "persist state then halt; resumable after review"
            state.status = RunStatus.RUNNING
            ctx.emit(
                Kind.RUN_RESUMED,
                node_id=node_id,
                actor=approver,
                payload={"after": state.halt_reason, "lineage": ctx.lineage_snapshot()},
            )
            if state.halt_reason == "budget.exceeded:wall_clock":
                # the reviewer decided to go on: like task_attempts below, the wall clock starts afresh
                state.budget.started_at = utcnow()
                state.budget.paused_seconds = 0.0
                state.budget.paused_at = None
            state.halt_reason = None
            fb = ctx.feedback.get(node_id)
            if fb:
                fb.pop(
                    "task_attempts", None
                )  # the reviewer decided to go on: the task gets a fresh allowance
        state.mark(node_id, NodeStatus.PENDING)
        return await self.run(ctx, state)

    async def reject(
        self, ctx: RunContext, state: RunState, node_id: str, approver: str, reason: str
    ) -> RunState:
        """A human rejected the checkpoint at ``node_id``: record it and safe-stop with ``human.reject``.

        Nothing is auto-retried; the run stays HALTED (resumable via ``approve`` after review). Emits
        ``APPROVAL_REJECTED`` then ``RUN_HALTED``.
        """
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
        """A human answered the questions of input node ``node_id``; store them and resume.

        ``answers`` maps ambiguity id -> answer and is merged into ``ctx.answers`` (partial answers are
        allowed: the node pauses again for whatever is still unanswered). Clears ``pending_questions``,
        re-queues the node as PENDING and re-enters ``run``. Emits ``INPUT_RECEIVED``.
        """
        ctx.answers.update(answers)
        ctx.emit(Kind.INPUT_RECEIVED, node_id=node_id, actor=who, payload={"answers": answers})
        state.pending_questions = []
        state.mark(node_id, NodeStatus.PENDING)
        return await self.run(ctx, state)

    def invalidate(
        self, ctx: RunContext, state: RunState, changed: set[str], origin: str, *, reproduce: bool = False
    ) -> set[str]:
        """Mark consumers of changed artifacts (+ downstream) for re-run. Never re-runs the origin node.
        reproduce=True also re-runs the artifacts' producers (used by diagnose -> retry/replan).

        Args:
            changed: artifact names that got a new version (or that a Route asked to reproduce).
            origin: node that caused it; excluded from the targets and recorded on the events.
            reproduce: use ``Graph.reproduce_targets`` (producers + consumers) instead of consumers only.

        Returns:
            The node ids actually flipped to INVALIDATED. Nodes that are PENDING or RUNNING are left alone
            (they will pick up the new version anyway, or are mid-flight and judged by their own outcome).

        Side effects / emits: ``NODE_INVALIDATED`` per node; revokes approvals and emits one
        ``POLICY_DECISION=APPROVAL_REVOKED`` when any were revoked (see the comment below for which).
        """
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
            # every token that is not a node id (task:<id>, scope:<task>:<path>, action names) was granted
            # against the old plan's tasks and files; a re-planned design must be re-approved from scratch
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
        """Run one node through its entry gates and its bounded attempt loop; return the final ``Outcome``.

        Order of checks:
        1. ``when:`` predicate false -> ``Skip`` (no handler, no attempt).
        2. Approval entry gate (``kind == "approval"`` or a ``high_impact`` action): ``NODE_ENTRY_GATE`` is
           emitted; the node proceeds if its id is in ``ctx.approvals``, or is auto-approved in replay mode
           when policy allows (``POLICY_DECISION=AUTO_APPROVED``); otherwise ``NeedsApproval`` with a summary
           of the ``summary_from`` artifacts (500 chars each). A pure approval node then returns ``Success``;
           a high-impact worker node continues to its handler.
        3. Input node: unanswered ambiguities in the ``spec`` -> ``NeedsInput`` (live mode only; replay runs
           the handler regardless), else the ``input`` handler (or ``Success`` when none is registered).
        4. Attempt loop for every other kind: up to ``_max_attempts`` attempts, each bounded by
           ``_timeout``; a timeout or any exception is turned into ``Retry`` (handler bugs are attempt
           failures, not crashes). A ``Retry`` stores its feedback (plus ``reason`` and ``attempt``) in
           ``ctx.feedback[node.id]``, emits ``ATTEMPT_FAILED``, sleeps with exponential backoff capped at
           ``max_delay_seconds`` and tries again; any other outcome ends the loop (``ATTEMPT_PASSED`` for
           ``Success``/``Route``, ``ATTEMPT_FAILED`` otherwise).

        Returns the last outcome; after exhaustion that is the final ``Retry``, which ``_apply`` treats as
        "retries exhausted" (fallback / rollback).

        Emits ``NODE_ENTRY_GATE``, ``POLICY_DECISION``, ``ATTEMPT_STARTED``, ``ATTEMPT_PASSED``,
        ``ATTEMPT_FAILED``.
        """
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
                # replay is the only mode that may auto-approve, and it still leaves a policy decision in
                # the trace so a replayed run is distinguishable from a human-approved one
                ctx.emit(
                    Kind.POLICY_DECISION,
                    node_id=node.id,
                    actor="policy",
                    status="AUTO_APPROVED",
                    payload={"action": action, "reason": "replay mode"},
                )
                ctx.approvals.add(node.id)
            else:
                brief = build_brief(node, ctx, action)  # the human decides on the real proposal + cost
                ctx.put("approval_brief", brief, node.id)
                return NeedsApproval(action, brief)
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
        max_attempts = self._max_attempts(node, ctx)
        delay = float(pol.retries.initial_delay_seconds)  # doubles after each failed attempt (exponential)
        last: Outcome = Blocked("no attempts")  # sentinel in case max_attempts is 0; never a real outcome
        for attempt in range(1, max_attempts + 1):
            ctx.emit(Kind.ATTEMPT_STARTED, node_id=node.id, attempt=attempt, actor=node.agent or node.kind)
            started = time.monotonic()
            try:
                last = await asyncio.wait_for(handler(node, ctx), timeout=self._timeout(node, ctx))
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
        """Returns True when the run must pause for a human.

        The single ``match`` over the closed ``Outcome`` set; every case updates ``state`` and emits the
        events for that transition. Also returns True after a halt so ``run`` stops scheduling (``run``
        checks ``state.status`` as well).

        Cases:
        - ``Success``: account tokens/cost, ``ctx.put`` each artifact, mark PASSED (``NODE_PASSED`` with the
          lineage snapshot); a still-runnable fallback node is SKIPPED (the fail-path was not needed); any
          artifact that replaced an earlier version triggers ``REPLAN_TRIGGERED`` + ``invalidate``.
        - ``Skip``: SKIPPED + ``NODE_SKIPPED``.
        - ``NeedsApproval`` / ``NeedsInput``: node and run enter the AWAITING_* status, questions are
          persisted on the state, ``APPROVAL_REQUESTED`` / ``INPUT_REQUESTED`` is emitted; returns True.
        - ``Route``: look up ``on_result[result]`` (unknown -> halt ``<node>.unknown_route:<r>``); mark
          PASSED with the routing feedback; ``safe_stop`` halts with the named trigger; ``invalidate``
          bumps ``budget.replans``/``plan_version`` for a plan change (halting at ``max_replans_per_run``),
          copies the feedback onto the producers of the invalidated artifacts, emits ``REPLAN_TRIGGERED``
          and invalidates with ``reproduce=True``; the routing node goes back to PENDING (re-entrant).
        - ``Blocked``: FAILED + ``NODE_FAILED``; halt with ``policy.violation`` if the reason mentions
          policy, else ``<node>.blocked``.
        - ``Retry`` (only reaches here after exhaustion): with a ``fallback`` -> FAILED +
          ``FALLBACK_TAKEN`` and the fallback node is re-armed if it was SKIPPED earlier; without one ->
          ``RollbackHook.rollback``, ROLLED_BACK + ``ROLLED_BACK``, then halt (``gate.repeated_failure``)
          when ``policy.retries.on_exhausted`` ends in ``halt``, else FAILED so dependents stay blocked and
          ``run`` ends the run FAILED.
        """
        match out:
            case Success(artifacts=arts, tokens_in=ti, tokens_out=to, cost_usd=c):
                state.budget.tokens_used += ti + to
                state.budget.cost_usd += c
                changed = {name for name, val in arts.items() if ctx.put(name, val, node.id)}
                state.mark(node.id, NodeStatus.PASSED)
                ctx.emit(Kind.NODE_PASSED, node_id=node.id, payload={"lineage": ctx.lineage_snapshot()})
                if node.fallback and state.nodes[node.fallback] in RUNNABLE:
                    # the fallback (e.g. diagnose) depends on this node; skipping it lets all_done() become
                    # true and keeps it from ever becoming ready on the success path
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
                state.pending_questions = [q["question"] for q in qs]  # shown by the API/CLI while paused
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
                        # a replan is the expensive loop (planning and everything below re-run), so it is
                        # counted and bounded here rather than by the generic retry budget
                        state.budget.replans += 1
                        if state.budget.replans > ctx.policy.budgets.max_replans_per_run:
                            await self._halt(ctx, state, "replan.limit_reached")
                            return True
                        state.plan_version += 1
                    for target in self.graph.producers_of(changed):
                        ctx.feedback.setdefault(target, {}).update(fb)  # the diagnosis reaches the re-run
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
                        # skipped on an earlier successful pass of this node; re-arm it for this failure
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
    @staticmethod
    def _max_attempts(node: NodeDef, ctx: RunContext) -> int:
        """max_attempts_per_task is per task: the executor node may retry once per task per allowance (the
        handler blocks a single task that exhausts its own allowance); other nodes get the plain budget.

        ``node.retries["max_attempts"]`` (workflow.yaml) overrides ``policy.budgets.max_attempts_per_task``.
        For the executor the base is multiplied by the number of plan tasks, because each ``Retry`` from the
        handler is *one task's* failure and the handler itself enforces the per-task ceiling.
        """
        base = int(node.retries.get("max_attempts", ctx.policy.budgets.max_attempts_per_task))
        if node.kind != "executor":
            return base
        tasks = getattr(ctx.get("plan"), "tasks", None)
        return base * max(1, len(tasks)) if tasks else base

    @staticmethod
    def _timeout(node: NodeDef, ctx: RunContext) -> float:
        """node_timeout_seconds bounds one unit of work: a node attempt, or for the executor node one task
        (the executor bounds each task itself), so the node budget scales with the plan's task count.

        Without the scaling a plan with many tasks would time out the whole implementation node even
        though every individual task finished within its own limit.
        """
        base = float(ctx.policy.budgets.node_timeout_seconds)
        if node.kind != "executor":
            return base
        tasks = getattr(ctx.get("plan"), "tasks", None)  # tests stub the plan with plain values
        return base * max(1, len(tasks)) if tasks else base

    def _budget_trip(self, state: RunState, ctx: RunContext) -> str | None:
        """Safe-stop trigger name when a run budget is exceeded, else None.

        Checked before every batch. Order: cost (``budget.exceeded:cost``), tokens
        (``budget.exceeded:tokens``), wall clock since ``budget.started_at`` (``budget.exceeded:wall_clock``).
        Cost and tokens are accumulated by ``_apply`` from ``Success`` outcomes.
        """
        b = ctx.policy.budgets
        elapsed = state.budget.elapsed_minutes()
        if state.budget.cost_usd > b.max_cost_usd_per_run:
            return "budget.exceeded:cost"
        if state.budget.tokens_used > b.max_tokens_per_run:
            return "budget.exceeded:tokens"
        if elapsed > b.max_wall_clock_minutes:
            return "budget.exceeded:wall_clock"
        return None

    async def _halt(self, ctx: RunContext, state: RunState, reason: str) -> RunState:
        """Safe-stop: mark the run HALTED with ``reason`` as ``halt_reason``, emit ``RUN_HALTED``, persist.

        ``reason`` is one of the ``policy.safe_stop.triggers`` names or a node-specific string
        (``<node>.blocked``, ``<node>.unknown_route:<r>``). The state is saved so the run is resumable
        through ``approve`` after human review; nothing is rolled back here.
        """
        state.status = RunStatus.HALTED
        state.halt_reason = reason
        state.budget.pause()  # the review that follows a safe-stop is human time
        ctx.emit(
            Kind.RUN_HALTED, status="HALTED", payload={"trigger": reason, "lineage": ctx.lineage_snapshot()}
        )
        await self.store.save(state)
        return state
