"""``Plan`` and ``TaskSpec``: the task DAG the implementation node executes.

Produced by the planner agent (node ``planning``) from ``spec`` (plus ``impact`` and ``diagnosis`` when
present). Consumed by the executor handler (task ordering, parallel groups, file scope, approval pauses),
the reviewer, diagnoser and documenter agents, and the policy engine (``allowed_files`` is the per-task
write scope). A new ``Plan`` version voids all task-level approvals (``POLICY_DECISION=APPROVAL_REVOKED``).

Every reference to design elements is by id/name (operation ids, table names, FQCNs) rather than by
embedding the objects, so the executor prompt can slice ``Design`` per task without duplication.
"""

from __future__ import annotations

from pydantic import model_validator

from .common import Frozen, ImpactLevel


class TaskSpec(Frozen):
    """One unit of implementation work; the executor prompt is built from this plus its ``Design`` slice.

    Engine semantics: ``depends_on`` orders tasks (validated acyclic by ``Plan``); tasks sharing a
    ``parallel_group`` run one at a time in the shared tree but are scheduled together; ``impact_level=HIGH``
    pauses the node for approval before the task runs; ``allowed_files`` is the write scope the policy
    engine enforces after every task commit (a BLOCKED task naming extra files pauses for a
    ``task.scope_change`` approval). ``definition_of_done`` and ``acceptance_criteria_ids`` drive the
    reviewer and the acceptance gate.
    """

    id: str
    title: str
    depends_on: list[str] = []
    parallel_group: str | None = None
    impact_level: ImpactLevel = ImpactLevel.LOW
    allowed_files: list[str]
    contract_slice: list[str] = []  # operation_ids
    data_model_slice: list[str] = []  # table names
    class_structure: list[str] = []  # FQCNs
    acceptance_criteria_ids: list[str] = []
    definition_of_done: list[str]
    risk_notes: str = ""
    rollback_note: str = ""

    @property
    def requires_approval(self) -> bool:
        """True for HIGH-impact tasks: the executor handler pauses the node until a human approves."""
        return self.impact_level == ImpactLevel.HIGH


class Plan(Frozen):
    """Ordered, acyclic set of tasks for one spec version.

    ``version``/``previous_version`` form the plan lineage; ``spec_version`` records which ``Spec`` the plan
    was derived from. ``invalidated_task_ids`` lets a re-plan (after ``diagnose -> replan``) tell the
    executor which tasks must be redone. Validation rejects unknown or cyclic ``depends_on`` references at
    construction time, so a malformed LLM reply is repaired rather than executed.
    """

    run_id: str
    version: int = 1
    previous_version: int | None = None
    spec_version: int
    tasks: list[TaskSpec]
    invalidated_task_ids: list[str] = []  # populated on re-plan: tasks whose inputs changed
    rationale: str = ""

    @model_validator(mode="after")
    def _acyclic_and_known_deps(self) -> Plan:
        """Reject a plan whose tasks depend on unknown ids or form a cycle (``ValueError`` -> repair loop)."""
        ids = {t.id for t in self.tasks}
        for t in self.tasks:
            unknown = set(t.depends_on) - ids
            if unknown:
                raise ValueError(f"task {t.id} depends on unknown tasks {unknown}")
        visiting: set[str] = set()
        done: set[str] = set()
        by_id = {t.id: t for t in self.tasks}

        def dfs(tid: str) -> None:
            # iterative-colouring DFS: `visiting` = on the current path (grey), `done` = finished (black)
            if tid in done:
                return
            if tid in visiting:
                raise ValueError(f"cycle in plan at {tid}")
            visiting.add(tid)
            for d in by_id[tid].depends_on:
                dfs(d)
            visiting.discard(tid)
            done.add(tid)

        for tid in ids:
            dfs(tid)
        return self
