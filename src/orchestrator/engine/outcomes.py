"""
Closed set of node outcomes: what a node handler may return.

Where it sits: ``api -> service -> engine -> {agents, executors, sandbox, trace, store}``. Handlers in
``engine/handlers.py`` (and the entry gates in ``engine/runner.py``) return one of these values;
``Runner._apply`` turns it into a ``RunState`` transition plus trace events with a single ``match`` statement.

Invariants
- The set is closed on purpose (docs/architecture.md, "Key decisions"): every transition the engine can make
  is enumerable and testable. A new behaviour is a new dataclass here AND a new ``case`` in ``Runner._apply``,
  never a status string or an exception.
- Only ``NeedsApproval`` and ``NeedsInput`` can pause a run; only a human (or replay auto-approve) resumes it.
- Every outcome is a frozen dataclass: once returned it cannot be mutated by the caller.

This module emits no trace events itself; the runner emits them when it applies an outcome
(``NODE_PASSED``, ``NODE_SKIPPED``, ``APPROVAL_REQUESTED``, ``INPUT_REQUESTED``, ``NODE_FAILED``, ...).
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass(frozen=True)
class Success:
    """The node did its work; the run may proceed.

    ``artifacts`` maps artifact name -> value and is written into the ``RunContext`` by the runner via
    ``ctx.put``. A name that already exists in context gets a new version, which invalidates its consumers
    and everything downstream (re-planning by invalidation). The token/cost fields are accounted into
    ``RunState.budget`` so the budget safe-stop can trip.
    """

    artifacts: dict[str, Any] = field(default_factory=dict)  # artifact name -> value; keys drive invalidation
    tokens_in: int = 0  # LLM usage of this outcome; folded into RunState.budget by the runner
    tokens_out: int = 0
    cost_usd: float = 0.0


@dataclass(frozen=True)
class Retry:
    """The attempt failed in a way another attempt might fix (gate failure, transient executor error).

    The runner stores ``feedback`` (plus ``reason`` and the attempt number) under ``ctx.feedback[node_id]``
    so the next attempt sees structured findings, then backs off exponentially. When the node's attempts
    are exhausted the *last* ``Retry`` reaches ``Runner._apply``, which takes the fallback edge, or rolls
    back and continues/halts per ``policy.retries.on_exhausted``.
    """

    reason: str
    feedback: dict[str, Any] = field(default_factory=dict)  # structured; fed to the next attempt


@dataclass(frozen=True)
class Blocked:
    """Non-retryable failure: the node is marked FAILED and the run safe-stops.

    A reason containing "policy" halts with trigger ``policy.violation``; anything else halts with
    ``<node>.blocked``. Used for unsatisfiable task dependencies, a task that exhausted its own retry
    allowance, and non-transient executor errors.
    """

    reason: str  # non-retryable (policy violation, unrecoverable)


@dataclass(frozen=True)
class NeedsApproval:
    """Pause the run at a human checkpoint (``AWAITING_APPROVAL``).

    ``action`` is a name from ``policy.autonomy.high_impact_actions`` (``plan.approve``, ``release.merge``)
    or one of the executor's own (``task.high_impact``, ``task.scope_change``). ``summary`` is what the
    approver sees; for approval nodes the runner builds it from ``summary_from`` artifacts (500 chars each).
    """

    action: str
    summary: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class NeedsInput:
    """Pause the run until a human answers questions (``AWAITING_INPUT``).

    Each question is a dumped ambiguity from the ``Spec`` (``id``, ``question``, ...); answers arrive keyed by
    ``id`` through ``Runner.answer`` and land in ``ctx.answers``.
    """

    questions: list[dict[str, Any]]


@dataclass(frozen=True)
class Skip:
    """The node does not apply this run (its ``when:`` predicate was false).

    Marked SKIPPED, which counts as terminal-OK: dependents proceed as if the node had passed.
    """

    reason: str


@dataclass(frozen=True)
class Route:
    """Used by the diagnose node: choose a branch defined in workflow.yaml `on_result`.

    ``result`` must be a key of the node's ``on_result`` map; an unknown key halts the run with trigger
    ``<node>.unknown_route:<result>``. The branch body says what happens: ``invalidate: [artifacts]`` re-runs
    their producers and consumers with ``feedback`` attached, ``safe_stop: <trigger>`` halts. A routing
    node is re-entrant: the runner marks it PENDING again after routing so it can judge the next round.
    """

    result: str  # retry | replan | halt
    feedback: dict[str, Any] = field(default_factory=dict)  # merged into ctx.feedback of the re-run producers


Outcome = (
    Success | Retry | Blocked | NeedsApproval | NeedsInput | Skip | Route
)  # closed; matched exhaustively
