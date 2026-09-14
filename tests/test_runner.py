from __future__ import annotations

from orchestrator.engine.outcomes import Retry, Route, Skip, Success
from orchestrator.engine.runner import Runner
from orchestrator.models.spec import Ambiguity, Spec
from orchestrator.models.state import NodeStatus, RunStatus
from orchestrator.models.trace import Kind


def spec(ambiguous: bool = False) -> Spec:
    amb = (
        [Ambiguity(id="q1", question="which?", options=["a", "b"], default_if_unanswered="a")]
        if ambiguous
        else []
    )
    return Spec(run_id="r1", summary="s", stories=[], acceptance_criteria=[], ambiguities=amb)


async def no_sleep(_: float) -> None:
    return None


def make_handlers(script: dict[str, list]) -> dict:  # type: ignore[type-arg]
    """script: node_id -> list of outcomes returned on successive attempts (last one repeats)."""
    calls: dict[str, int] = {}

    async def h(node, ctx):  # type: ignore[no-untyped-def]
        calls[node.id] = calls.get(node.id, 0) + 1
        seq = script.get(node.id, [Success()])
        out = seq[min(calls[node.id], len(seq)) - 1]
        return out

    return {"agent": h, "executor": h, "gate": h, "input": h, "approval": h, "_calls": calls}  # type: ignore[dict-item]


async def test_happy_path_pauses_at_approvals_then_completes(graph, ctx, state, store):  # type: ignore[no-untyped-def]
    hs = make_handlers(
        {
            "a": [Success({"spec": spec()})],
            "b": [Success({"plan": "p1"})],
            "impl": [Success({"changeset": "cs"})],
        }
    )
    r = Runner(graph, hs, store, sleep=no_sleep)
    st = await r.run(ctx, state)
    assert st.status == RunStatus.AWAITING_APPROVAL and st.nodes["approve"] == NodeStatus.AWAITING_APPROVAL
    assert st.nodes["clarify"] == NodeStatus.SKIPPED  # when-condition false
    assert st.nodes["c1"] == st.nodes["c2"] == NodeStatus.PASSED
    st = await r.approve(ctx, st, "approve", "pdp")
    assert st.status == RunStatus.AWAITING_APPROVAL and st.nodes["release"] == NodeStatus.AWAITING_APPROVAL
    assert st.nodes["diagnose"] == NodeStatus.SKIPPED  # fail-path skipped when validate passed
    st = await r.approve(ctx, st, "release", "pdp")
    assert st.status == RunStatus.COMPLETED
    kinds = [e.kind for e in ctx.trace.events("r1")]
    assert kinds[0] == Kind.RUN_STARTED and kinds[-1] == Kind.RUN_COMPLETED
    assert Kind.APPROVAL_REQUESTED in kinds and Kind.APPROVAL_GRANTED in kinds


async def test_parallel_siblings_run_concurrently(graph, ctx, state, store):  # type: ignore[no-untyped-def]
    import asyncio

    order: list[str] = []

    async def h(node, ctx):  # type: ignore[no-untyped-def]
        order.append(f"{node.id}:start")
        await asyncio.sleep(0.01)
        order.append(f"{node.id}:end")
        return Success({"spec": spec()} if node.id == "a" else {})

    r = Runner(graph, {"agent": h, "executor": h, "gate": h, "input": h}, store, sleep=no_sleep)
    await r.run(ctx, state)
    assert order.index("c2:start") < order.index("c1:end")  # c2 started before c1 finished


async def test_retry_then_pass_records_attempts(graph, ctx, state, store):  # type: ignore[no-untyped-def]
    hs = make_handlers(
        {"a": [Success({"spec": spec()})], "b": [Retry("bad json", {"hint": "fix"}), Success({"plan": "p"})]}
    )
    r = Runner(graph, hs, store, sleep=no_sleep)
    st = await r.run(ctx, state)
    assert st.nodes["b"] == NodeStatus.PASSED
    attempts = [e for e in ctx.trace.events("r1") if e.node_id == "b" and e.kind == Kind.ATTEMPT_STARTED]
    assert len(attempts) == 2
    assert ctx.feedback["b"]["hint"] == "fix"  # structured feedback kept for attempt 2


async def test_exhausted_retries_take_fallback_and_route_retry(graph, ctx, state, store):  # type: ignore[no-untyped-def]
    ctx.replay = True  # auto-approve so we reach validate
    hs = make_handlers(
        {
            "a": [Success({"spec": spec()})],
            "b": [Success({"plan": "p"})],
            "impl": [Success({"changeset": "cs1"})],
            "validate": [Retry("scope"), Retry("scope"), Retry("scope"), Success()],
            "diagnose": [Route("retry", {"fix": "x"})],
        }
    )
    r = Runner(graph, hs, store, sleep=no_sleep)
    st = await r.run(ctx, state)
    kinds = [e.kind for e in ctx.trace.events("r1")]
    assert Kind.FALLBACK_TAKEN in kinds and Kind.NODE_INVALIDATED in kinds
    assert st.status == RunStatus.COMPLETED
    assert hs["_calls"]["impl"] == 2  # implementation re-ran after diagnose->retry
    assert st.nodes["validate"] == NodeStatus.PASSED


async def test_replan_limit_triggers_safe_stop(graph, ctx, state, store):  # type: ignore[no-untyped-def]
    ctx.replay = True
    hs = make_handlers(
        {
            "a": [Success({"spec": spec()})],
            "b": [Success({"plan": "p"})],
            "impl": [Success({"changeset": "cs"})],
            "validate": [Retry("x")],  # always fails
            "diagnose": [Route("replan")],
        }
    )
    r = Runner(graph, hs, store, sleep=no_sleep)
    st = await r.run(ctx, state)
    assert st.status == RunStatus.HALTED and st.halt_reason == "replan.limit_reached"
    assert st.budget.replans == 2  # 1 allowed, 2nd trips the limit


async def test_ambiguity_pauses_for_input_then_invalidates_downstream(graph, ctx, state, store):  # type: ignore[no-untyped-def]
    hs = make_handlers(
        {
            "a": [Success({"spec": spec(ambiguous=True)})],
            "clarify": [Success({"spec": spec()})],  # spec v2 without ambiguities
            "b": [Success({"plan": "p"})],
            "impl": [Success({"changeset": "cs"})],
        }
    )
    r = Runner(graph, hs, store, sleep=no_sleep)
    st = await r.run(ctx, state)
    assert st.status == RunStatus.AWAITING_INPUT and st.pending_questions == ["which?"]
    st = await r.answer(ctx, st, "clarify", {"q1": "a"}, "pdp")
    assert ctx.version("spec") == 2
    assert Kind.REPLAN_TRIGGERED in [e.kind for e in ctx.trace.events("r1")]
    assert st.status == RunStatus.AWAITING_APPROVAL  # progressed to the approval gate


async def test_budget_trip_halts(graph, ctx, state, store):  # type: ignore[no-untyped-def]
    hs = make_handlers({"a": [Success({"spec": spec()}, tokens_in=2000)]})  # > max_tokens_per_run
    r = Runner(graph, hs, store, sleep=no_sleep)
    st = await r.run(ctx, state)
    assert st.status == RunStatus.HALTED and st.halt_reason.startswith("budget.exceeded")


async def test_skip_outcome(graph, ctx, state, store):  # type: ignore[no-untyped-def]
    hs = make_handlers({"a": [Skip("nothing to do")]})
    r = Runner(graph, hs, store, sleep=no_sleep)
    st = await r.run(ctx, state)
    assert st.nodes["a"] == NodeStatus.SKIPPED


async def test_executor_node_timeout_scales_with_the_plan(graph, ctx, state, store):  # type: ignore[no-untyped-def]
    import asyncio

    from orchestrator.engine.runner import Runner
    from orchestrator.models.plan import Plan

    ctx.policy = ctx.policy.model_copy(
        update={"budgets": ctx.policy.budgets.model_copy(update={"node_timeout_seconds": 1})}
    )
    tasks = [
        {"id": f"t{i}", "title": "x", "allowed_files": ["src/**"], "definition_of_done": ["d"]}
        for i in range(3)
    ]
    ctx.put("plan", Plan.model_validate({"run_id": "r1", "spec_version": 1, "tasks": tasks}), "b")
    impl, agent = graph.nodes["impl"], graph.nodes["a"]
    assert Runner._timeout(impl, ctx) == 3.0 and Runner._timeout(agent, ctx) == 1.0

    async def slow(node, ctx):  # type: ignore[no-untyped-def]
        await asyncio.sleep(1.5)
        return Success({"changeset": "cs"})

    r = Runner(graph, {"executor": slow, "agent": slow}, store, sleep=no_sleep)
    assert isinstance(await r._execute(impl, ctx, state), Success)  # 1.5 s < 3 tasks x 1 s
    out = await r._execute(agent, ctx, state)
    assert isinstance(out, Retry) and "timeout" in out.reason  # an agent gets the plain budget


def test_executor_max_attempts_scale_with_the_plan(graph, ctx) -> None:  # type: ignore[no-untyped-def]
    from orchestrator.engine.runner import Runner
    from orchestrator.models.plan import Plan

    tasks = [
        {"id": f"t{i}", "title": "x", "allowed_files": ["src/**"], "definition_of_done": ["d"]}
        for i in range(4)
    ]
    ctx.put("plan", Plan.model_validate({"run_id": "r1", "spec_version": 1, "tasks": tasks}), "b")
    per_task = ctx.policy.budgets.max_attempts_per_task
    assert Runner._max_attempts(graph.nodes["impl"], ctx) == per_task * 4
    assert Runner._max_attempts(graph.nodes["a"], ctx) == per_task


async def test_halted_run_resumes_on_approval_with_a_fresh_task_allowance(graph, ctx, state, store):  # type: ignore[no-untyped-def]
    from orchestrator.engine.outcomes import Blocked

    hs = make_handlers(
        {
            "a": [Success({"spec": spec()})],
            "b": [Success({"plan": "p"})],
            "impl": [Blocked("task t1 failed 3 times: spend limit"), Success({"changeset": "cs"})],
        }
    )
    ctx.replay = True  # auto-approve the design so we reach impl
    r = Runner(graph, hs, store, sleep=no_sleep)
    st = await r.run(ctx, state)
    assert st.status == RunStatus.HALTED and st.halt_reason == "impl.blocked"
    ctx.feedback["impl"] = {"task_attempts": {"t1": 3}, "reason": "x", "attempt": 1}
    st = await r.approve(ctx, st, "impl", "reviewer")  # the human resumes after review
    kinds = [e.kind for e in ctx.trace.events("r1")]
    assert Kind.RUN_RESUMED in kinds and "task_attempts" not in ctx.feedback["impl"]
    assert (
        st.status in (RunStatus.COMPLETED, RunStatus.AWAITING_APPROVAL)
        and st.nodes["impl"] == NodeStatus.PASSED
    )
