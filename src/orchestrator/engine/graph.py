"""Explicit dependency graph loaded from workflow.yaml. Pure data + readiness rules; no execution here."""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml

from ..models.state import RUNNABLE, TERMINAL_OK, NodeStatus, RunState


@dataclass(frozen=True)
class NodeDef:
    id: str
    kind: str  # agent | executor | gate | approval | input
    depends_on: tuple[str, ...] = ()
    produces: tuple[str, ...] = ()
    invalidated_by: tuple[str, ...] = ()
    parallel_group: str | None = None
    fallback: str | None = None
    rollback: str | None = None  # strategy applied by the RollbackHook on exhaustion
    high_impact: str | None = None
    when: str | None = None
    agent: str | None = None
    gates: tuple[str, ...] = ()
    rolls_up: tuple[str, ...] = ()  # gate nodes whose recorded results count toward this gate node's verdict
    advisory: bool = False
    retries: dict[str, Any] = field(default_factory=dict)
    on_result: dict[str, Any] = field(default_factory=dict)
    summary_from: tuple[str, ...] = ()
    doc: str = ""


class Graph:
    def __init__(self, nodes: list[NodeDef], entry: str) -> None:
        self.nodes: dict[str, NodeDef] = {n.id: n for n in nodes}
        self.entry = entry
        self._validate()

    # ---------------------------------------------------------------- loading
    @classmethod
    def load(cls, path: str | Path = "workflow.yaml") -> Graph:
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
        visiting: set[str] = set()
        done: set[str] = set()

        def dfs(nid: str) -> None:
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
        return {nid: NodeStatus.PENDING for nid in self.nodes}

    def _dep_satisfied(self, dependent: NodeDef, dep_id: str, state: RunState) -> bool:
        st = state.nodes[dep_id]
        if st in TERMINAL_OK:
            return True
        # fallback edge: a node's declared fallback may run when the node FAILED
        return st == NodeStatus.FAILED and self.nodes[dep_id].fallback == dependent.id

    def ready(self, state: RunState) -> list[NodeDef]:
        out = [
            n
            for n in self.nodes.values()
            if state.nodes[n.id] in RUNNABLE and all(self._dep_satisfied(n, d, state) for d in n.depends_on)
        ]
        return sorted(out, key=lambda n: n.id)

    def downstream(self, node_id: str) -> set[str]:
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
        return {n.id for n in self.nodes.values() if set(n.produces) & artifacts}

    def reproduce_targets(self, changed: set[str]) -> set[str]:
        """For an explicit re-run request (diagnose -> retry/replan): the producers of the artifacts,
        their consumers, and everything downstream of both."""
        hit = self.producers_of(changed) | {
            n.id for n in self.nodes.values() if set(n.invalidated_by) & changed
        }
        out = set(hit)
        for h in hit:
            out |= self.downstream(h)
        return out

    def all_done(self, state: RunState) -> bool:
        return all(state.nodes[nid] in TERMINAL_OK for nid in self.nodes)

    def topological_order(self) -> list[str]:
        order: list[str] = []
        seen: set[str] = set()

        def visit(nid: str) -> None:
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
        lines = ["flowchart TD"]
        for n in self.nodes.values():
            shape = {"approval": "{{%s}}", "input": "[/%s/]", "gate": "[[%s]]"}.get(n.kind, "[%s]")
            lines.append(f"  {n.id}{shape % n.id}")
            for d in n.depends_on:
                lines.append(f"  {d} --> {n.id}")
            if n.fallback:
                lines.append(f"  {n.id} -. fail .-> {n.fallback}")
        return "\n".join(lines)
