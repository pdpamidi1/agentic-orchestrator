"""Concrete agents. Prompts live in prompts/<name>.md and are formatted with the artifacts in `reads`."""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel

from ..engine.context import RunContext
from ..models import Design, Impact, Plan, Spec
from ..models.common import Frozen
from .base import Agent


class RequirementsAgent(Agent[Spec]):
    name = "requirements"
    output = Spec
    reads = ("requirement_text",)
    produces = "spec"

    def post(self, out: Spec, ctx: RunContext) -> Spec:
        # apply human answers (clarify node re-runs this agent with `answers` filled)
        if ctx.answers:
            resolved = [a for a in out.ambiguities if a.id not in ctx.answers]
            assumptions = list(out.assumptions) + [
                f"{a.id}: {ctx.answers[a.id]}" for a in out.ambiguities if a.id in ctx.answers
            ]
            return out.model_copy(
                update={
                    "ambiguities": resolved,
                    "assumptions": assumptions,
                    "version": ctx.version("spec") + 1,
                    "run_id": ctx.run_id,
                }
            )
        return out.model_copy(update={"run_id": ctx.run_id, "version": ctx.version("spec") + 1})


class PlannerAgent(Agent[Plan]):
    name = "planner"
    output = Plan
    reads = ("spec", "impact", "diagnosis")
    produces = "plan"

    def post(self, out: Plan, ctx: RunContext) -> Plan:
        prev = ctx.version("plan")
        return out.model_copy(
            update={
                "run_id": ctx.run_id,
                "version": prev + 1,
                "previous_version": prev or None,
                "spec_version": ctx.version("spec"),
            }
        )


class ArchitectureAgent(Agent[Design]):
    name = "architecture"
    output = Design
    reads = ("spec", "plan", "impact", "system_design_notes", "tech_stack")
    produces = "design"

    def post(self, out: Design, ctx: RunContext) -> Design:
        return out.model_copy(update={"run_id": ctx.run_id, "spec_version": ctx.version("spec")})


class ImpactAgent(Agent[Impact]):
    name = "impact"
    output = Impact
    reads = ("spec", "repo_map")
    produces = "impact"


class SecurityFindings(Frozen):
    findings: list[dict[str, Any]]  # {id, severity, area, description, requirement}
    required_controls: list[str]
    high_impact_actions_expected: list[str]  # e.g. ["schema.migration"] -> pre-announced approvals


class SecurityAgent(Agent[SecurityFindings]):
    name = "security"
    output = SecurityFindings
    reads = ("spec", "design")
    produces = "security_findings"


class RiskRegister(Frozen):
    risks: list[dict[str, Any]]  # {id, description, likelihood, severity, mitigation, detection}
    trade_offs: list[str]
    failure_scenarios: list[str]


class RiskAgent(Agent[RiskRegister]):
    name = "risk"
    output = RiskRegister
    reads = ("spec", "design", "impact")
    produces = "risk_register"


class Review(Frozen):
    criteria: list[dict[str, Any]]  # {id, verdict: PASS|FAIL, evidence}
    concerns: list[str]
    recommendation: str  # APPROVE | REVISE


class ReviewerAgent(Agent[Review]):
    name = "reviewer"
    output = Review
    reads = ("spec", "plan", "changeset")
    produces = "review"

    def post(self, out: Review, ctx: RunContext) -> dict[str, Any]:
        return out.model_dump()


class Diagnosis(BaseModel):
    root_cause: str
    decision: str  # retry | replan | halt
    feedback: dict[str, Any] = {}  # handed to implementation (retry) or planner (replan)


class DiagnoserAgent(Agent[Diagnosis]):
    name = "diagnoser"
    output = Diagnosis
    reads = ("validation_result", "review", "plan", "changeset")
    produces = "diagnosis"


class Docs(Frozen):
    readme_md: str
    changelog_md: str
    adr_md: list[str]
    runbook_md: str


class DocumenterAgent(Agent[Docs]):
    name = "documenter"
    output = Docs
    reads = ("spec", "design", "plan", "changeset", "validation_result")
    produces = "docs"


AGENTS = {
    a.name: a
    for a in (
        RequirementsAgent,
        PlannerAgent,
        ArchitectureAgent,
        ImpactAgent,
        SecurityAgent,
        RiskAgent,
        ReviewerAgent,
        DiagnoserAgent,
        DocumenterAgent,
    )
}
