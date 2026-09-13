from __future__ import annotations

from .common import Frozen


class Risk(Frozen):
    id: str
    description: str
    likelihood: str  # low | medium | high
    severity: str  # low | medium | high
    mitigation: str
    detection: str  # how we would know it happened


class Impact(Frozen):
    """Brownfield codebase reasoning output."""

    run_id: str
    impacted_packages: list[str]
    new_packages: list[str] = []
    impacted_endpoints: list[str] = []
    data_flows: list[str] = []  # "redirect -> url.clicked (kafka) -> analytics consumer -> click_stats"
    risks: list[Risk] = []
