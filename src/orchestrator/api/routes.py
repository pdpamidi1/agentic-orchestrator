from __future__ import annotations

from pathlib import Path
from typing import Any

from fastapi import APIRouter, BackgroundTasks, HTTPException
from pydantic import BaseModel

from ..service import OrchestratorService

router = APIRouter()
svc = OrchestratorService()


class StartRun(BaseModel):
    scenario: str
    requirement_text: str | None = None
    replay: bool = False
    record: bool = False


class Answers(BaseModel):
    answers: dict[str, str]


class Decision(BaseModel):
    reason: str = ""
    who: str = "human"


@router.post("/runs", status_code=202)
async def start_run(body: StartRun, bg: BackgroundTasks) -> dict[str, Any]:
    live = await svc.start(body.scenario, body.requirement_text, replay=body.replay, record=body.record)
    return {
        "run_id": live.state.run_id,
        "status": live.state.status,
        "nodes": live.state.nodes,
        "pending_questions": live.state.pending_questions,
    }


@router.get("/runs/{run_id}")
async def get_run(run_id: str) -> dict[str, Any]:
    live = svc.runs.get(run_id)
    if not live:
        raise HTTPException(404, "unknown run (prototype keeps live runs in memory)")
    return live.state.model_dump(mode="json")


@router.post("/runs/{run_id}/approvals/{node_id}")
async def approve(run_id: str, node_id: str, body: Decision) -> dict[str, Any]:
    live = await svc.approve(run_id, node_id, body.who)
    return {"status": live.state.status, "nodes": live.state.nodes}


@router.post("/runs/{run_id}/rejections/{node_id}")
async def reject(run_id: str, node_id: str, body: Decision) -> dict[str, Any]:
    live = await svc.reject(run_id, node_id, body.reason, body.who)
    return {"status": live.state.status, "halt_reason": live.state.halt_reason}


@router.post("/runs/{run_id}/answers")
async def answer(run_id: str, body: Answers) -> dict[str, Any]:
    live = await svc.answer(run_id, body.answers)
    return {
        "status": live.state.status,
        "spec_version": live.ctx.version("spec"),
        "plan_version": live.state.plan_version,
    }


class Deliver(BaseModel):
    to: str | None = None


@router.post("/runs/{run_id}/deliver")
async def deliver(run_id: str, body: Deliver) -> dict[str, Any]:
    if run_id not in svc.runs:
        raise HTTPException(404, "unknown run (prototype keeps live runs in memory)")
    try:
        dest = await svc.deliver(run_id, Path(body.to) if body.to else None)
    except RuntimeError as e:
        raise HTTPException(409, str(e)) from e
    return {"run_id": run_id, "delivered_to": str(dest)}


@router.get("/runs/{run_id}/metrics")
async def metrics(run_id: str) -> dict[str, Any]:
    return svc.metrics(run_id).as_dict()


@router.get("/runs/{run_id}/trace")
async def trace(run_id: str, kind: str | None = None) -> list[dict[str, Any]]:
    return [e.model_dump(mode="json") for e in svc.trace.events(run_id) if kind is None or e.kind == kind]


@router.get("/runs/{run_id}/artifacts/{name}")
async def artifact(run_id: str, name: str) -> Any:
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
    return {"mermaid": svc.graph.to_mermaid()}
