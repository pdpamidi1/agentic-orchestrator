"""HTTP surface of the orchestrator (FastAPI router mounted by ``orchestrator.main``).

Position in the pipeline: ``api -> service -> engine``. Every handler here is a thin adapter: it validates
the request body with a Pydantic model, delegates to the single module-level ``OrchestratorService``
(``svc``) and serialises the resulting ``RunState``/artifact/metrics to JSON. No orchestration logic lives
in this module.

Key invariants:
- Live runs are held in memory by the service (``svc.runs``); a run started in another process is
  unknown here, hence the 404 texts. This is a documented prototype limitation (TASKS: resume from store).
- Human checkpoints (``AWAITING_APPROVAL`` / ``AWAITING_INPUT``) only advance through the approval,
  rejection and answer routes below; nothing in the API auto-approves.
- Metrics are computed on request from ``runs/<id>/trace.jsonl``; nothing is stored per request.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

from fastapi import APIRouter, BackgroundTasks, HTTPException
from pydantic import BaseModel

from ..service import OrchestratorService

router = APIRouter()
# One service (and therefore one in-memory run registry) per API process; the CLI talks to this process.
svc = OrchestratorService()


class StartRun(BaseModel):
    """Body of ``POST /runs``.

    ``scenario`` selects ``specs/scenarios/<scenario>.md`` unless ``requirement_text`` is given.
    ``replay`` forces cached LLM responses and recorded patches (no API key, auto-approve semantics);
    ``record`` runs live (or fake) and writes the replay cache. Both false = mode chosen by ``Settings.llm``.
    """

    scenario: str
    requirement_text: str | None = None
    replay: bool = False
    record: bool = False
    workspace: str | None = None  # brownfield: directory copied into the sandbox as the baseline


class Answers(BaseModel):
    """Body of ``POST /runs/{run_id}/answers``: ambiguity id -> the human's answer (free text)."""

    answers: dict[str, str]


class Decision(BaseModel):
    """Body of the approval and rejection routes: who decided and (for rejections) why."""

    reason: str = ""
    who: str = "human"


@router.post("/runs", status_code=202)
async def start_run(body: StartRun, bg: BackgroundTasks) -> dict[str, Any]:
    """Start a run and drive it until it completes, halts, or pauses on a human checkpoint.

    The call is synchronous with respect to the run: it returns once the runner yields (the ``bg``
    parameter is accepted but not used). Side effects: creates ``runs/<id>/`` (sandbox, state.json,
    artifacts, trace.jsonl) via the service. Returns 202 with the run id, run status, node statuses and any
    pending clarification questions. A ``--record`` request in replay mode raises ``RuntimeError`` in the
    service, which FastAPI surfaces as a 500.
    """
    live = await svc.start(
        body.scenario,
        body.requirement_text,
        replay=body.replay,
        record=body.record,
        workspace=Path(body.workspace) if body.workspace else None,
    )
    return {
        "run_id": live.state.run_id,
        "status": live.state.status,
        "nodes": live.state.nodes,
        "pending_questions": live.state.pending_questions,
    }


@router.get("/runs/{run_id}")
async def get_run(run_id: str) -> dict[str, Any]:
    """Return the full ``RunState`` of a live run as JSON; 404 if this process does not hold the run."""
    live = svc.runs.get(run_id)
    if not live:
        raise HTTPException(404, "unknown run (prototype keeps live runs in memory)")
    return live.state.model_dump(mode="json")


@router.post("/runs/{run_id}/approvals/{node_id}")
async def approve(run_id: str, node_id: str, body: Decision) -> dict[str, Any]:
    """Approve the node paused in ``AWAITING_APPROVAL`` and resume the run.

    Side effects: appends to ``runs/<id>/approvals.jsonl``, emits ``APPROVAL_GRANTED`` (and ``RUN_RESUMED``
    after a safe-stop), then continues execution until the next pause or the end. An unknown run id raises
    ``KeyError`` in the service (500). Returns the new run status and node statuses.
    """
    live = await svc.approve(run_id, node_id, body.who)
    return {"status": live.state.status, "nodes": live.state.nodes}


@router.post("/runs/{run_id}/rejections/{node_id}")
async def reject(run_id: str, node_id: str, body: Decision) -> dict[str, Any]:
    """Reject a pending checkpoint: the run safe-stops with ``halt_reason='human.reject'``.

    Side effects: appends a REJECTED line to ``approvals.jsonl``, emits ``APPROVAL_REJECTED`` and
    ``RUN_HALTED``. Returns the run status (HALTED) and the halt reason.
    """
    live = await svc.reject(run_id, node_id, body.reason, body.who)
    return {"status": live.state.status, "halt_reason": live.state.halt_reason}


@router.post("/runs/{run_id}/answers")
async def answer(run_id: str, body: Answers) -> dict[str, Any]:
    """Answer the clarification questions of the node in ``AWAITING_INPUT`` and resume the run.

    The answers feed the requirements agent, which produces a new ``spec`` version; that version bump
    invalidates every downstream node. Emits ``INPUT_RECEIVED``. Returns the run status plus the spec and
    plan versions now in force. If no node is awaiting input the service raises ``StopIteration`` (500).
    """
    live = await svc.answer(run_id, body.answers)
    return {
        "status": live.state.status,
        "spec_version": live.ctx.version("spec"),
        "plan_version": live.state.plan_version,
    }


class Deliver(BaseModel):
    """Body of ``POST /runs/{run_id}/deliver``: destination directory; ``None`` = ``Settings.workspace``."""

    to: str | None = None


@router.post("/runs/{run_id}/deliver")
async def deliver(run_id: str, body: Deliver) -> dict[str, Any]:
    """Copy a COMPLETED run's sandbox tree into the workspace (the ``release.merge`` outcome).

    Status codes: 404 when the run is not in memory; 409 when the run is not COMPLETED (the service raises
    ``RuntimeError`` because ``approval_release`` has not been passed). Side effects: file copy and an
    ``ARTIFACT_WRITTEN`` event with artifact ``delivery``.
    """
    if run_id not in svc.runs:
        raise HTTPException(404, "unknown run (prototype keeps live runs in memory)")
    try:
        dest = await svc.deliver(run_id, Path(body.to) if body.to else None)
    except RuntimeError as e:
        raise HTTPException(409, str(e)) from e
    return {"run_id": run_id, "delivered_to": str(dest)}


@router.get("/runs/{run_id}/metrics")
async def metrics(run_id: str) -> dict[str, Any]:
    """Compute ``RunMetrics`` from the run's trace events on demand (nothing is stored).

    Works for any run with a ``trace.jsonl`` on disk, not only live ones; an unknown run yields zeroed
    metrics.
    """
    return svc.metrics(run_id).as_dict()


@router.get("/runs/{run_id}/trace")
async def trace(run_id: str, kind: str | None = None) -> list[dict[str, Any]]:
    """Return the run's trace events in file order, optionally filtered by ``Kind`` value (``?kind=``)."""
    return [e.model_dump(mode="json") for e in svc.trace.events(run_id) if kind is None or e.kind == kind]


@router.get("/runs/{run_id}/artifacts/{name}")
async def artifact(run_id: str, name: str) -> Any:
    """Return the current version of a named artifact from the live run's context.

    404 when the run is not in memory or the artifact has not been produced yet. Pydantic artifacts are
    dumped to JSON; plain values (e.g. ``requirement_text``) are returned as-is.
    """
    live = svc.runs.get(run_id)
    if not live or name not in live.ctx.artifacts:
        raise HTTPException(404)
    a = live.ctx.artifacts[name]
    val = a.value
    return {
        "name": name,
        "version": a.version,
        "produced_by": a.produced_by,
        "value": val.model_dump(mode="json") if isinstance(val, BaseModel) else val,
    }


@router.get("/workflow/mermaid")
async def workflow_mermaid() -> dict[str, str]:
    """Render the loaded ``workflow.yaml`` DAG as a Mermaid diagram (used by ``sdlc graph`` and docs)."""
    return {"mermaid": svc.graph.to_mermaid()}
