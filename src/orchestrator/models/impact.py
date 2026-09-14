"""``Impact``: brownfield change-impact analysis over the existing codebase.

Produced by the impact agent (node ``impact``, brownfield scenario only) from ``spec`` and ``repo_map``;
it never reads the raw tree. Consumed by the planner (task scoping), the architecture agent and the risk
agent. ``Risk`` is the shared risk-row shape reused by the risk register.
"""

from __future__ import annotations

from .common import Frozen


class Risk(Frozen):
    """One identified risk with how it is mitigated and how its occurrence would be detected.

    ``likelihood``/``severity`` are free-text low|medium|high rather than enums so the agent prompt and
    schema stay simple; consumers treat them as labels only.
    """

    id: str
    description: str
    likelihood: str  # low | medium | high
    severity: str  # low | medium | high
    mitigation: str
    detection: str  # how we would know it happened


class Impact(Frozen):
    """Brownfield codebase reasoning output.

    Package and endpoint names refer to entries of the ``RepoMap`` the agent was given; ``new_packages``
    are the ones the change will introduce. ``data_flows`` are arrow-notation strings describing how data
    moves after the change (see the field comment).
    """

    run_id: str
    impacted_packages: list[str]
    new_packages: list[str] = []
    impacted_endpoints: list[str] = []
    data_flows: list[str] = []  # "redirect -> url.clicked (kafka) -> analytics consumer -> click_stats"
    risks: list[Risk] = []
