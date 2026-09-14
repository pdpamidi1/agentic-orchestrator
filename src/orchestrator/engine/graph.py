"""Explicit dependency graph loaded from workflow.yaml. Pure data + readiness rules; no execution here.

Where it sits: ``api -> service -> engine -> ...``; the ``Graph`` is built once by the composition root
(``service.py``) and shared by the ``Runner`` (readiness, invalidation targets) and the handlers (roll-ups).
It knows nothing about ``RunContext``, policy or LLMs: its only runtime input is the ``RunState`` node
status map, so every readiness decision is a pure function of persisted state and can be unit-tested.

Invariants (checked at load time, see ``Graph._validate``)
- every ``depends_on`` / ``fallback`` / ``rolls_up`` target exists
- no cycles: the ``depends_on`` relation is a DAG (fallback edges are not dependencies and may point back)
- an approval node names the ``high_impact`` action it guards, so the entry gate can log it
- a rolled-up node is a gate node with ``produces`` and is also a declared dependency

This module emits no trace events; the runner does when it acts on the graph's answers.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml

from ..models.state import RUNNABLE, TERMINAL_OK, NodeStatus, RunState


@dataclass(frozen=True)
class NodeDef:
    """One node of workflow.yaml, field for field. Immutable configuration; never carries run state.

    Every field maps to a YAML key with the same name (see the header comment of workflow.yaml). The
    runner interprets ``kind``, ``when``, ``high_impact``, ``fallback``, ``retries`` and ``on_result``; the
    handlers interpret ``agent``, ``gates``, ``rolls_up`` and ``produces``; ``invalidated_by`` drives
    re-planning.
    """

    id: str
    kind: str  # agent | executor | gate | approval | input
    depends_on: tuple[str, ...] = ()  # explicit edges; all must be terminal-OK before this node is READY
    produces: tuple[str, ...] = ()  # artifact names this node writes into RunContext (a new version each run)
    invalidated_by: tuple[str, ...] = ()  # a new version of any of these artifacts re-runs this node
    parallel_group: str | None = None  # documentation of intended fan-out; readiness alone decides batches
    fallback: str | None = None  # node run when this one exhausts retries (fail-path edge, e.g. diagnose)
    rollback: str | None = None  # strategy applied by the RollbackHook on exhaustion
    high_impact: str | None = None  # policy.autonomy.high_impact_actions name -> approval entry gate
    when: str | None = None  # named predicate from engine/conditions.py; false -> SKIPPED
    agent: str | None = None  # key into agents.catalog.AGENTS for kind=agent (and the input node's re-run)
    gates: tuple[str, ...] = ()  # gate ids from policy.yaml#gates, run in this order by kind=gate
    rolls_up: tuple[str, ...] = ()  # gate nodes whose recorded results count toward this gate node's verdict
    advisory: bool = False  # informational only; the engine does not read it (e.g. code_review)
    retries: dict[str, Any] = field(default_factory=dict)  # {"max_attempts": n, ...}; overrides policy
    on_result: dict[str, Any] = field(
        default_factory=dict
    )  # Route branches: result -> {invalidate|safe_stop}
    summary_from: tuple[str, ...] = ()  # artifacts shown (truncated) to the approver of an approval node
    doc: str = ""  # free text for humans and the mermaid/API views


class Graph:
    """The validated DAG plus the readiness and invalidation queries the runner needs.

    ``nodes`` preserves YAML order (dict insertion order); ``entry`` is informational (readiness, not the
    entry pointer, decides what runs first). Construction validates the graph and raises ``ValueError`` on
    any structural problem, so a broken workflow.yaml fails at startup rather than mid-run.
    """

    def __init__(self, nodes: list[NodeDef], entry: str) -> None:
        """Index ``nodes`` by id, remember ``entry`` and validate; raises ``ValueError`` on a bad graph."""
        self.nodes: dict[str, NodeDef] = {n.id: n for n in nodes}
        self.entry = entry
        self._validate()

    # ---------------------------------------------------------------- loading
    @classmethod
    def load(cls, path: str | Path = "workflow.yaml") -> Graph:
        """Parse workflow.yaml into a ``Graph``.

        Every YAML key is popped into a ``NodeDef`` field; anything left over is an unknown key and raises
        ``ValueError`` (a typo in the graph must not be silently ignored). ``entry`` defaults to the first
        node. ``retries`` / ``on_result`` given as YAML ``null`` become empty dicts.
        """
        raw = yaml.safe_load(Path(path).read_text(encoding="utf-8"))
        nodes = []
        for nid, body in raw["nodes"].items():
            body = dict(body)
            nodes.append(
                NodeDef(
                    id=nid,
                    kind=body.pop("kind"),
                    depends_on=tuple(body.pop("depends_on", [])),
                    produces=tuple(body.pop("produces", [])),
                    invalidated_by=tuple(body.pop("invalidated_by", [])),
                    parallel_group=body.pop("parallel_group", None),
                    fallback=body.pop("fallback", None),
                    rollback=body.pop("rollback", None),
                    high_impact=body.pop("high_impact", None),
                    when=body.pop("when", None),
                    agent=body.pop("agent", None),
                    gates=tuple(body.pop("gates", [])),
                    rolls_up=tuple(body.pop("rolls_up", [])),
                    advisory=bool(body.pop("advisory", False)),
                    retries=body.pop("retries", {}) or {},
                    on_result=body.pop("on_result", {}) or {},
                    summary_from=tuple(body.pop("summary_from", [])),
                    doc=body.pop("doc", ""),
                )
            )
            if body:
                raise ValueError(f"unknown keys on node {nid}: {sorted(body)}")
        return cls(nodes, raw.get("entry", nodes[0].id))

    # ---------------------------------------------------------------- validation
    def _validate(self) -> None:
        """Enforce the structural invariants listed in the module docstring; raises ``ValueError``.

        Checks referential integrity of ``depends_on``/``fallback``/``rolls_up``, that approval nodes name a
        ``high_impact`` action, that rolled-up nodes are gate nodes with ``produces`` and are dependencies,
        and finally that ``depends_on`` is acyclic (iterative DFS with a "visiting" set).
        """
        for n in self.nodes.values():
            for d in n.depends_on:
                if d not in self.nodes:
                    raise ValueError(f"node {n.id} depends on unknown node {d}")
            if n.fallback and n.fallback not in self.nodes:
                raise ValueError(f"node {n.id} has unknown fallback {n.fallback}")
            if n.kind == "approval" and not n.high_impact:
                raise ValueError(f"approval node {n.id} must name a high_impact action")
            for r in n.rolls_up:
                if r not in self.nodes or self.nodes[r].kind != "gate" or not self.nodes[r].produces:
                    raise ValueError(f"node {n.id} rolls up {r}, which must be a gate node with produces")
                if r not in n.depends_on:
                    raise ValueError(f"node {n.id} rolls up {r} but does not depend on it")
        visiting: set[str] = set()  # nodes on the current DFS path; re-entering one means a cycle
        done: set[str] = set()  # nodes whose whole dependency subtree is known acyclic

        def dfs(nid: str) -> None:
            """Walk ``depends_on`` edges from ``nid``; raise on a back edge."""
            if nid in done:
                return
            if nid in visiting:
                raise ValueError(f"cycle detected at {nid}")
            visiting.add(nid)
            for d in self.nodes[nid].depends_on:
                dfs(d)
            visiting.discard(nid)
            done.add(nid)

        for nid in self.nodes:
            dfs(nid)

    # ---------------------------------------------------------------- queries
    def initial_statuses(self) -> dict[str, NodeStatus]:
        """Node id -> PENDING for every node; the starting ``RunState.nodes`` of a new run."""
        return {nid: NodeStatus.PENDING for nid in self.nodes}

    def _dep_satisfied(self, dependent: NodeDef, dep_id: str, state: RunState) -> bool:
        """Is dependency ``dep_id`` satisfied from the point of view of ``dependent``?

        PASSED or SKIPPED always satisfies. FAILED satisfies only when ``dependent`` is the failed node's
        declared fallback: that is how the fail-path edge (validation -> diagnose) becomes runnable while
        every other dependent of the failed node stays blocked.
        """
        st = state.nodes[dep_id]
        if st in TERMINAL_OK:
            return True
        # fallback edge: a node's declared fallback may run when the node FAILED
        return st == NodeStatus.FAILED and self.nodes[dep_id].fallback == dependent.id

    def ready(self, state: RunState) -> list[NodeDef]:
        """Nodes that may run now: PENDING/INVALIDATED with every dependency satisfied.

        Sorted by id so batches are deterministic (tests and replay rely on this); the runner truncates the
        list to ``policy.budgets.parallelism`` and runs the batch concurrently.
        """
        out = [
            n
            for n in self.nodes.values()
            if state.nodes[n.id] in RUNNABLE and all(self._dep_satisfied(n, d, state) for d in n.depends_on)
        ]
        return sorted(out, key=lambda n: n.id)

    def downstream(self, node_id: str) -> set[str]:
        """All transitive dependents of ``node_id`` (``depends_on`` followed forward); excludes itself."""
        out: set[str] = set()
        stack = [node_id]
        while stack:
            cur = stack.pop()
            for n in self.nodes.values():
                if cur in n.depends_on and n.id not in out:
                    out.add(n.id)
                    stack.append(n.id)
        return out

    def invalidated_by_artifacts(self, changed: set[str]) -> set[str]:
        """Nodes that must re-run because an artifact they consume changed, plus everything downstream."""
        hit = {n.id for n in self.nodes.values() if set(n.invalidated_by) & changed}
        out = set(hit)
        for h in hit:
            out |= self.downstream(h)
        return out

    def producers_of(self, artifacts: set[str]) -> set[str]:
        """Node ids whose ``produces`` intersects ``artifacts`` (used to attach diagnoser feedback)."""
        return {n.id for n in self.nodes.values() if set(n.produces) & artifacts}

    def reproduce_targets(self, changed: set[str]) -> set[str]:
        """For an explicit re-run request (diagnose -> retry/replan): the producers of the artifacts,
        their consumers, and everything downstream of both.

        Differs from ``invalidated_by_artifacts`` by including the producers: a retry must re-run
        ``implementation`` itself, not only the nodes that consume ``changeset``.
        """
        hit = self.producers_of(changed) | {
            n.id for n in self.nodes.values() if set(n.invalidated_by) & changed
        }
        out = set(hit)
        for h in hit:
            out |= self.downstream(h)
        return out

    def all_done(self, state: RunState) -> bool:
        """True when every node is PASSED or SKIPPED: the run can be marked COMPLETED."""
        return all(state.nodes[nid] in TERMINAL_OK for nid in self.nodes)

    def topological_order(self) -> list[str]:
        """Node ids in dependency order (dependencies first), ties broken by id; for display and tests."""
        order: list[str] = []
        seen: set[str] = set()

        def visit(nid: str) -> None:
            """Post-order DFS: append ``nid`` after all of its dependencies."""
            if nid in seen:
                return
            seen.add(nid)
            for d in self.nodes[nid].depends_on:
                visit(d)
            order.append(nid)

        for nid in sorted(self.nodes):
            visit(nid)
        return order

    def to_mermaid(self) -> str:
        """Render the graph as a Mermaid ``flowchart TD`` (``/workflow/mermaid`` and ``sdlc graph``).

        Node shape encodes kind (hexagon = approval, parallelogram = input, subroutine = gate, box = other);
        solid arrows are ``depends_on`` edges, the dotted ``fail`` arrow is the fallback edge.
        """
        lines = ["flowchart TD"]
        for n in self.nodes.values():
            shape = {"approval": "{{%s}}", "input": "[/%s/]", "gate": "[[%s]]"}.get(n.kind, "[%s]")
            lines.append(f"  {n.id}{shape % n.id}")
            for d in n.depends_on:
                lines.append(f"  {d} --> {n.id}")
            if n.fallback:
                lines.append(f"  {n.id} -. fail .-> {n.fallback}")
        return "\n".join(lines)
