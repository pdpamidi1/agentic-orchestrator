"""``Spec``: the normalised engineering problem, first artifact of every run.

Produced by the requirements agent (``agents/catalog.py#RequirementsAgent``, node ``requirement``) from
``requirement_text`` only. Consumed by the planner, architecture, impact, security, risk and documenter
agents; the executor never sees it directly (it works from ``Plan.TaskSpec`` and ``Design``).

Versioning: the ``clarify`` input node pauses the run on ``ambiguities``; once answered, the agent
re-runs and emits ``Spec`` v2, which invalidates every consumer downstream. Every field is a typed list
because structured outputs cannot express free-form dicts.
"""

from __future__ import annotations

from .common import Frozen


class UserStory(Frozen):
    """One "as a / I want / so that" story; ``id`` is referenced by acceptance criteria and prompts."""

    id: str
    as_a: str
    i_want: str
    so_that: str


class AcceptanceCriterion(Frozen):
    """Given/when/then criterion. ``id`` is what ``TaskSpec.acceptance_criteria_ids`` points at and what
    the acceptance gate maps onto test names."""

    id: str
    given: str
    when: str
    then: str


class Ambiguity(Frozen):
    """An open question the requirements agent could not resolve from the text.

    Surfaces to the human as ``RunState.pending_questions`` (``AWAITING_INPUT``); answers are keyed by
    ``id``. In replay mode the ``clarify`` node does not pause at all (the recorded spec v2 is replayed).
    ``options`` and ``default_if_unanswered`` are guidance for the human and the prompt; the engine itself
    does not read them.
    """

    id: str
    question: str
    options: list[str]
    default_if_unanswered: str


class Spec(Frozen):
    """Normalized engineering problem. version increments each time clarification changes it.

    ``run_id`` ties the artifact to its run; ``version`` starts at 1 and is bumped by re-runs after
    answers. ``non_goals`` and ``assumptions`` bound the planner; ``ambiguities`` non-empty means the run
    must pause for input before planning (``needs_clarification``).
    """

    run_id: str
    version: int = 1
    summary: str
    stories: list[UserStory]
    acceptance_criteria: list[AcceptanceCriterion]
    non_goals: list[str] = []
    ambiguities: list[Ambiguity] = []
    assumptions: list[str] = []

    @property
    def needs_clarification(self) -> bool:
        """True when at least one ``Ambiguity`` is open; the ``clarify`` node keys off this."""
        return bool(self.ambiguities)
