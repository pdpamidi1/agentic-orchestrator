"""Named predicates usable in workflow.yaml `when:` clauses. Keep them tiny and testable.

Where it sits: an ``engine`` leaf called only by ``Runner._execute`` before a node runs. A false predicate
turns the node into a ``Skip`` outcome (status SKIPPED; dependents proceed as if it had passed).

Design note (docs/architecture.md, "Risks, trade-offs"): ``when:`` is a closed dictionary of named predicates,
not an expression language, so the graph stays auditable: every condition that can gate a stage is listed
here by its literal YAML string and unit-tested. Adding a predicate is a reviewed code change.

Emits no trace events; the runner records the resulting ``NODE_SKIPPED``.
"""

from __future__ import annotations

from collections.abc import Callable

from .context import RunContext


def _spec_has_ambiguities(ctx: RunContext) -> bool:
    """True when the ``Spec`` in context lists at least one ambiguity.

    Gates the ``clarify`` input node: with no open questions the human-input loop is skipped. Missing spec
    (or a stubbed one without the attribute) counts as "no ambiguities".
    """
    spec = ctx.get("spec")
    return bool(spec and getattr(spec, "ambiguities", []))


def _workspace_has_code(ctx: RunContext) -> bool:
    """True when the sandbox already holds Java or Python sources under ``src/``.

    Distinguishes brownfield (the ``impact`` agent runs against the repo map) from greenfield (``impact`` is
    SKIPPED). Reads the filesystem synchronously; the tree is small at this point of the run.
    """
    src = ctx.sandbox / "src"
    if not src.exists():
        return False
    return any(src.rglob("*.java")) or any(src.rglob("*.py"))


# Keys are the exact strings written after `when:` in workflow.yaml; there is no parsing or expression syntax.
PREDICATES: dict[str, Callable[[RunContext], bool]] = {
    "spec.ambiguities is non-empty": _spec_has_ambiguities,
    "workspace.has_code": _workspace_has_code,
}


def evaluate(expr: str, ctx: RunContext) -> bool:
    """Evaluate a ``when:`` clause by exact name lookup in ``PREDICATES``.

    Args:
        expr: the literal string from workflow.yaml.
        ctx: the run context the predicate inspects.

    Returns:
        The predicate's verdict.

    Raises:
        ValueError: for an unknown name. This propagates out of ``Runner._execute`` (it is evaluated before
        the attempt loop, so it is not converted into a ``Retry``): a misspelled condition in the graph fails
        loudly rather than silently skipping or running a stage.
    """
    try:
        return PREDICATES[expr](ctx)
    except KeyError as e:
        raise ValueError(f"unknown when-condition {expr!r}; add it to engine/conditions.PREDICATES") from e
