"""Typed artifacts and run state shared by every layer (``models`` is a leaf: it imports nothing else).

Everything an agent produces or the engine persists is one of these Pydantic models. Artifacts derive from
``common.Frozen`` (immutable, ``extra="forbid"``) so a new version is always a new object, which is what
gives lineage and invalidation for free. ``RunState`` and ``Budget`` are the deliberate exception: the
engine mutates them and persists them after every transition. ``Policy`` mirrors ``policy.yaml``.

Agent outputs (``Spec``, ``Plan``, ``Design``, ``Impact``) are also the JSON schemas sent to the LLM as
structured outputs, so their fields are fully typed lists of models rather than free-form dicts.
This package re-exports the public names so callers import ``orchestrator.models`` only.
"""

from .common import ImpactLevel
from .design import ApiContract, ClassStructure, DataModel, Design
from .impact import Impact, Risk
from .plan import Plan, TaskSpec
from .policy import Policy, load_policy
from .repo_map import Endpoint, RepoMap
from .spec import AcceptanceCriterion, Ambiguity, Spec, UserStory
from .state import Budget, NodeStatus, RunState, RunStatus
from .trace import Kind, TraceEvent
from .validation import Finding, GateOutcome, GateStatus, ValidationResult

__all__ = [
    "AcceptanceCriterion",
    "Ambiguity",
    "ApiContract",
    "Budget",
    "ClassStructure",
    "DataModel",
    "Design",
    "Endpoint",
    "Finding",
    "GateOutcome",
    "GateStatus",
    "Impact",
    "ImpactLevel",
    "Kind",
    "NodeStatus",
    "Plan",
    "Policy",
    "RepoMap",
    "Risk",
    "RunState",
    "RunStatus",
    "Spec",
    "TaskSpec",
    "TraceEvent",
    "UserStory",
    "ValidationResult",
    "load_policy",
]
