from __future__ import annotations

from pathlib import Path

import pytest

from orchestrator.engine.graph import Graph, NodeDef
from orchestrator.models.state import NodeStatus


def test_loads_and_is_acyclic(graph: Graph) -> None:
    assert set(graph.nodes) >= {"a", "b", "c1", "c2", "approve", "impl", "validate", "diagnose", "release"}
    assert graph.topological_order().index("a") < graph.topological_order().index("release")


REPO_WORKFLOW = Path(__file__).resolve().parent.parent / "workflow.yaml"


def test_committed_workflow_loads() -> None:
    g = Graph.load(REPO_WORKFLOW)
    assert g.entry == "requirement"
    assert g.nodes["implementation"].rollback == "git_revert_task_commits"
    assert g.nodes["validation"].fallback == "diagnose"
    for n in g.nodes.values():
        if n.kind in ("agent", "executor") and n.id != g.entry:
            assert n.produces, f"{n.id} must declare produces"
    order = g.topological_order()
    assert order.index("requirement") < order.index("implementation") < order.index("approval_release")


def test_unknown_node_key_is_rejected(tmp_path: Path) -> None:
    p = tmp_path / "workflow.yaml"
    p.write_text("version: 1\nentry: a\nnodes:\n  a: {kind: agent, agent: x, bogus: 1}\n")
    with pytest.raises(ValueError, match="unknown keys on node a"):
        Graph.load(p)


def test_cycle_is_rejected() -> None:
    with pytest.raises(ValueError, match="cycle"):
        Graph([NodeDef("x", "agent", depends_on=("y",)), NodeDef("y", "agent", depends_on=("x",))], "x")


def test_approval_requires_action() -> None:
    with pytest.raises(ValueError, match="high_impact"):
        Graph([NodeDef("ap", "approval")], "ap")


def test_readiness_and_parallel_group(graph: Graph, state) -> None:  # type: ignore[no-untyped-def]
    assert [n.id for n in graph.ready(state)] == ["a"]
    state.mark("a", NodeStatus.PASSED)
    state.mark("clarify", NodeStatus.SKIPPED)
    state.mark("b", NodeStatus.PASSED)
    assert [n.id for n in graph.ready(state)] == ["c1", "c2"]  # parallel siblings ready together
    state.mark("c1", NodeStatus.PASSED)
    assert graph.ready(state) and graph.ready(state)[0].id == "c2"  # join waits for both
    assert "approve" not in [n.id for n in graph.ready(state)]


def test_fallback_edge_only_runs_on_failure(graph: Graph, state) -> None:  # type: ignore[no-untyped-def]
    for n in ("a", "clarify", "b", "c1", "c2", "approve", "impl"):
        state.mark(n, NodeStatus.PASSED)
    state.mark("validate", NodeStatus.FAILED)
    ids = [n.id for n in graph.ready(state)]
    assert "diagnose" in ids and "release" not in ids


def test_invalidation_propagates_downstream(graph: Graph) -> None:
    hit = graph.invalidated_by_artifacts({"plan"})
    assert {"c1", "c2", "impl", "validate", "diagnose", "release", "approve"} <= hit
    assert "b" not in hit and "a" not in hit


def test_mermaid_renders(graph: Graph) -> None:
    m = graph.to_mermaid()
    assert "flowchart TD" in m and "validate -. fail .-> diagnose" in m
