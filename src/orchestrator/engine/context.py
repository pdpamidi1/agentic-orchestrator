"""Cross-stage context: versioned artifacts, lineage, policy, sandbox, trace. Shared by all nodes in a run.

Where it sits: an ``engine`` leaf that every handler, gate, agent and executor receives. The composition root
(``service.py``) builds one ``RunContext`` per run; the ``Runner`` and the handlers mutate it as the graph
executes. It is not persisted as a whole: artifact versions are written to ``runs/<id>/artifacts/`` by the
store, and approvals, answers and feedback are reconstructable from the trace.

Invariants
- Artifact values are frozen models; a new version is a new ``Artifact`` record, never a mutation. ``put`` is
  the only writer and reports whether it replaced a version, which is what drives invalidation in the runner.
- ``lineage_snapshot`` (artifact name -> version in force) is attached to every ``RUN_*`` and ``NODE_PASSED``
  event so the audit log can reconstruct which versions each decision was made against.

Emits ``ARTIFACT_WRITTEN`` (from ``put``); ``emit`` is the single funnel every other event goes through.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from ..models.policy import Policy
from ..models.trace import Kind, TraceEvent
from ..trace.sink import TraceSink


@dataclass
class Artifact:
    """One version of one named artifact in context.

    ``version`` starts at 1 and increments on every ``RunContext.put`` of the same name; ``produced_by`` is
    the id of the node that wrote it (lineage). ``value`` is the typed artifact itself (``Spec``, ``Plan``,
    ``Design``, ``Changeset``, ``ValidationResult``, ...).
    """

    name: str
    version: int
    value: Any
    produced_by: str


@dataclass
class RunContext:
    """Everything a node needs to do its work, and the run-wide human decisions.

    Lifecycle: created once per run by ``service.py``; the runner adds artifacts, feedback, answers and
    approvals while the graph executes; handlers read artifacts named in ``Agent.reads`` / ``produces``.

    Relation to configuration: ``policy`` is the parsed ``policy.yaml`` (read-only here); node definitions
    from ``workflow.yaml`` arrive as ``NodeDef`` objects, not through this context.
    """

    run_id: str
    scenario: str  # scenario name (greenfield, brownfield, ambiguous, ...); recorded on RUN_STARTED
    policy: Policy  # parsed policy.yaml; never mutated by the engine
    sandbox: Path  # working tree of the target codebase (a git repo with one branch per run)
    trace: TraceSink  # append-only event sink (jsonl and/or memory)
    target_stack: str = "java"  # "java" | "python": selects gate commands, banned patterns, contract dumps
    replay: bool = False  # replay mode: recorded LLM/executor output; auto-approval allowed by policy
    artifacts: dict[str, Artifact] = field(default_factory=dict)  # name -> latest version only
    # node_id -> structured feedback for that node's next attempt. Shapes seen in practice:
    # {"reason", "attempt", "findings"} from a Retry; {"task", "task_attempts": {task: n}} from the executor;
    # {"scope_request": {"task", "files", "reason"}} while paused on a scope approval, renamed to
    # "scope_change" once granted; the diagnoser's {"items", "root_cause"} merged in on a Route.
    feedback: dict[str, dict[str, Any]] = field(default_factory=dict)  # node_id -> structured feedback
    answers: dict[str, str] = field(default_factory=dict)  # ambiguity id -> human answer
    # Approval tokens, all in one set so the policy engine and the gates see the same grants:
    #   "<node_id>"            a human approved that node (approval nodes; or the executor node while paused)
    #   "task:<task_id>"       a HIGH-impact task was approved (mapped from the node approval by the handler)
    #   "scope:<task>:<path>"  a human granted one extra file to one task (task.scope_change)
    #   "<action>"             a high-impact action name (schema.migration, ...) an approved task's protected
    #                          paths required; lets the run-level scope/contract gates accept those writes
    # Invalidation revokes tokens: an invalidated approval node loses its grant, a new plan voids every
    # non-node token (POLICY_DECISION=APPROVAL_REVOKED).
    approvals: set[str] = field(default_factory=set)  # node ids approved by a human

    def emit(self, kind: Kind, **kw: Any) -> None:
        """Append one ``TraceEvent`` of ``kind`` for this run.

        ``kw`` are the remaining ``TraceEvent`` fields (``node_id``, ``task_id``, ``attempt``, ``status``,
        ``actor``, ``payload``, token/cost counters). This is the only place events are constructed, so the
        ``run_id`` is always stamped.
        """
        self.trace.emit(TraceEvent(run_id=self.run_id, kind=kind, **kw))

    def get(self, name: str, default: Any = None) -> Any:
        """Return the current value of artifact ``name`` (latest version), or ``default`` when absent."""
        a = self.artifacts.get(name)
        return a.value if a else default

    def version(self, name: str) -> int:
        """Return the current version number of artifact ``name``; 0 when it has never been produced."""
        a = self.artifacts.get(name)
        return a.version if a else 0

    def put(self, name: str, value: Any, produced_by: str) -> bool:
        """Store a new artifact version.

        Returns True if this REPLACES an existing version (=> invalidation).

        Side effects: replaces ``artifacts[name]`` with a new ``Artifact`` (version+1, or 1 for a first
        write) and emits ``ARTIFACT_WRITTEN`` carrying the artifact name, version and value type, with
        ``produced_by`` as both ``node_id`` and ``actor``. The runner uses the return value to trigger
        ``REPLAN_TRIGGERED`` + ``invalidate`` for downstream consumers.
        """
        prev = self.artifacts.get(name)
        version = (prev.version + 1) if prev else 1
        self.artifacts[name] = Artifact(name, version, value, produced_by)
        self.emit(
            Kind.ARTIFACT_WRITTEN,
            node_id=produced_by,
            actor=produced_by,
            payload={"artifact": name, "version": version, "type": type(value).__name__},
        )
        return prev is not None

    def lineage_snapshot(self) -> dict[str, int]:
        """Artifact name -> version currently in force; attached to RUN_* and NODE_PASSED events."""
        return {k: v.version for k, v in self.artifacts.items()}
