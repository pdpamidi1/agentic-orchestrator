from __future__ import annotations

from datetime import UTC, datetime
from enum import StrEnum

from pydantic import BaseModel, ConfigDict


def utcnow() -> datetime:
    return datetime.now(UTC)


class Frozen(BaseModel):
    """All artifacts are immutable values; new versions are new objects (lineage, not mutation)."""

    model_config = ConfigDict(frozen=True, extra="forbid")


class ImpactLevel(StrEnum):
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"
