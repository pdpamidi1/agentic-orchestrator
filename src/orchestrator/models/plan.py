from __future__ import annotations

from pydantic import model_validator

from .common import Frozen, ImpactLevel


class TaskSpec(Frozen):
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
        return self.impact_level == ImpactLevel.HIGH


class Plan(Frozen):
    run_id: str
    version: int = 1
    previous_version: int | None = None
    spec_version: int
    tasks: list[TaskSpec]
    invalidated_task_ids: list[str] = []  # populated on re-plan: tasks whose inputs changed
    rationale: str = ""

    @model_validator(mode="after")
    def _acyclic_and_known_deps(self) -> Plan:
        ids = {t.id for t in self.tasks}
        for t in self.tasks:
            unknown = set(t.depends_on) - ids
            if unknown:
                raise ValueError(f"task {t.id} depends on unknown tasks {unknown}")
        visiting: set[str] = set()
        done: set[str] = set()
        by_id = {t.id: t for t in self.tasks}

        def dfs(tid: str) -> None:
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
