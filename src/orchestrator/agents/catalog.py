"""Concrete agents. Prompts live in prompts/<name>.md and are formatted with the artifacts in `reads`.

Where it sits: `AGENTS` (name -> class) is what `engine/handlers.py#agent_handler` instantiates for a
node whose kind is `agent`; the node's `agent:` field in `workflow.yaml` must be a key of this map, and
the node's `produces` must match the agent's `produces`. Nine agents cover the pipeline: requirements,
planner, architecture, impact, security, risk, reviewer, diagnoser, documenter.

Invariants:
- Each agent declares exactly `name`, `output`, `reads`, `produces`; behaviour beyond the base class is
  limited to `post()` stamping lineage (run id, version, previous_version, spec_version).
- Output schemas that do not already live in `models/` are defined here as frozen models with every
  field fully typed (see the comment above `SecurityFinding`): structured outputs cannot express
  free-form maps, and `tests/` asserts no agent schema degrades into an empty object.
- `post()` never mutates: artifacts are frozen, so a new version is a `model_copy(update=...)`.

No trace events or files beyond what `agents/base.py` emits.
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel

from ..engine.context import RunContext
from ..models import Design, Impact, Plan, Risk, Spec
from ..models.common import Frozen
from .base import Agent


class RequirementsAgent(Agent[Spec]):
    """requirement_text -> `Spec` (stories, acceptance criteria, non-goals, ambiguities, assumptions).

    Runs first, and again after the `clarify` input node when a human has answered ambiguities; the
    second run yields spec v2, which invalidates every consumer downstream.
    """

    name = "requirements"
    output = Spec
    reads = ("requirement_text",)
    produces = "spec"

    def post(self, out: Spec, ctx: RunContext) -> Spec:
        """Stamp run id and the next spec version; fold human answers into the spec.

        With `ctx.answers` present, every ambiguity whose id was answered is removed from `ambiguities`
        and recorded as an assumption `"<id>: <answer>"`, so an answered spec carries no open questions
        and the `clarify` node does not pause again. Version is always `current spec version + 1`.
        """
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
    """spec (+ impact, diagnosis) -> `Plan`: the task DAG the implementation node executes.

    Re-runs on `diagnose -> replan`, reading the `Diagnosis` feedback; a new plan version voids task-level
    approvals (`POLICY_DECISION=APPROVAL_REVOKED`).
    """

    name = "planner"
    output = Plan
    reads = ("spec", "impact", "diagnosis")
    produces = "plan"

    def post(self, out: Plan, ctx: RunContext) -> Plan:
        """Stamp lineage: run id, next plan version, `previous_version` (None for the first plan) and
        the spec version this plan was made from."""
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
    """spec + plan (+ impact, notes, tech stack) -> `Design`: API contract, data model, class layering.

    The Design is the executor's contract slice and the source of the generated architecture test.
    """

    name = "architecture"
    output = Design
    reads = ("spec", "plan", "impact", "system_design_notes", "tech_stack")
    produces = "design"

    def post(self, out: Design, ctx: RunContext) -> Design:
        """Stamp run id and the spec version the design satisfies."""
        return out.model_copy(update={"run_id": ctx.run_id, "spec_version": ctx.version("spec")})


class ImpactAgent(Agent[Impact]):
    """spec + repo_map -> `Impact` (brownfield only): impacted/new packages, endpoints, data flows, risks.

    Reads the static `repo_map` built by `sandbox/repo_map.py`, never the raw tree.
    """

    name = "impact"
    output = Impact
    reads = ("spec", "repo_map")
    produces = "impact"


# Structured outputs cannot express free-form maps (the SDK turns `dict` into an empty object), so every
# agent output is fully typed. New fields are new fields, never `dict[str, Any]`.
class SecurityFinding(Frozen):
    """One security concern the implementation must address, tied to a concrete requirement."""

    id: str
    severity: str  # low | medium | high | critical
    area: str  # input | authz | secrets | pii | logging | dependencies | infra
    description: str
    requirement: str  # the concrete requirement implementation must satisfy


class SecurityFindings(Frozen):
    """Output of the security review: findings, controls to add, and high-impact actions to expect."""

    findings: list[SecurityFinding]
    required_controls: list[str]
    high_impact_actions_expected: list[str]  # e.g. ["schema.migration"] -> pre-announced approvals


class SecurityAgent(Agent[SecurityFindings]):
    """spec + design -> `SecurityFindings`; runs in parallel with the risk agent after architecture."""

    name = "security"
    output = SecurityFindings
    reads = ("spec", "design")
    produces = "security_findings"


class RiskRegister(Frozen):
    """Output of the risk analysis: risks (shared `Risk` model), trade-offs and failure scenarios."""

    risks: list[Risk]
    trade_offs: list[str]
    failure_scenarios: list[str]


class RiskAgent(Agent[RiskRegister]):
    """spec + design (+ impact) -> `RiskRegister`; runs in parallel with the security agent."""

    name = "risk"
    output = RiskRegister
    reads = ("spec", "design", "impact")
    produces = "risk_register"


class CriterionVerdict(Frozen):
    """The reviewer's verdict on one acceptance criterion, with where the evidence lives."""

    id: str  # acceptance criterion id
    verdict: str  # PASS | FAIL
    evidence: str  # test name or file:line


class Review(Frozen):
    """Output of the code review: per-criterion verdicts, free-text concerns, and a recommendation."""

    criteria: list[CriterionVerdict]
    concerns: list[str]
    recommendation: str  # APPROVE | REVISE


class ReviewerAgent(Agent[Review]):
    """spec + plan + changeset -> `Review`; advisory acceptance review, humans own quality."""

    name = "reviewer"
    output = Review
    reads = ("spec", "plan", "changeset")
    produces = "review"

    def post(self, out: Review, ctx: RunContext) -> dict[str, Any]:
        """Store the review as a plain dict (`model_dump()`) instead of the frozen model."""
        return out.model_dump()


class FeedbackItem(Frozen):
    """One actionable instruction from the diagnoser, addressed to a file, task or area."""

    target: str  # file path, task id or area the instruction applies to
    instruction: str  # precise, actionable


class Diagnosis(BaseModel):
    """Output of the diagnoser after a failed validation: root cause, route, and feedback for that route.

    `decision` drives the fail path: `retry` re-runs implementation with `feedback`, `replan` re-runs
    planning (bounded by `max_replans_per_run`), `halt` safe-stops the run. Not frozen, unlike the other
    schemas here; the handler reads it and copies `feedback` into context.
    """

    root_cause: str
    decision: str  # retry | replan | halt
    feedback: list[FeedbackItem] = []  # handed to implementation (retry) or planner (replan)


class DiagnoserAgent(Agent[Diagnosis]):
    """validation_result + review + plan + changeset -> `Diagnosis`; the only agent on the fail path."""

    name = "diagnoser"
    output = Diagnosis
    reads = ("validation_result", "review", "plan", "changeset")
    produces = "diagnosis"


class Docs(Frozen):
    """Output of the documenter: README, changelog, one or more ADRs and a runbook, all as Markdown."""

    readme_md: str
    changelog_md: str
    adr_md: list[str]
    runbook_md: str


class DocumenterAgent(Agent[Docs]):
    """spec + design + plan + changeset + validation_result -> `Docs`; runs after validation passes."""

    name = "documenter"
    output = Docs
    reads = ("spec", "design", "plan", "changeset", "validation_result")
    produces = "docs"


# Registry consumed by the agent handler: `workflow.yaml` node `agent:` values must be keys here.
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
