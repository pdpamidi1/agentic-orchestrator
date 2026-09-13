from __future__ import annotations

from .common import Frozen


class UserStory(Frozen):
    id: str
    as_a: str
    i_want: str
    so_that: str


class AcceptanceCriterion(Frozen):
    id: str
    given: str
    when: str
    then: str


class Ambiguity(Frozen):
    id: str
    question: str
    options: list[str]
    default_if_unanswered: str


class Spec(Frozen):
    """Normalized engineering problem. version increments each time clarification changes it."""

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
        return bool(self.ambiguities)
