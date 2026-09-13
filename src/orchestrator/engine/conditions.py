"""Named predicates usable in workflow.yaml `when:` clauses. Keep them tiny and testable."""

from __future__ import annotations

from collections.abc import Callable

from .context import RunContext


def _spec_has_ambiguities(ctx: RunContext) -> bool:
    spec = ctx.get("spec")
    return bool(spec and getattr(spec, "ambiguities", []))


def _workspace_has_code(ctx: RunContext) -> bool:
    src = ctx.sandbox / "src"
    if not src.exists():
        return False
    return any(src.rglob("*.java")) or any(src.rglob("*.py"))


PREDICATES: dict[str, Callable[[RunContext], bool]] = {
    "spec.ambiguities is non-empty": _spec_has_ambiguities,
    "workspace.has_code": _workspace_has_code,
}


def evaluate(expr: str, ctx: RunContext) -> bool:
    try:
        return PREDICATES[expr](ctx)
    except KeyError as e:
        raise ValueError(f"unknown when-condition {expr!r}; add it to engine/conditions.PREDICATES") from e
