"""ASGI entry point: ``uvicorn orchestrator.main:app`` (``make run``, port 8080).

Builds the FastAPI application, mounts the run/approval/metrics router from ``api.routes`` and adds a
liveness probe. Importing this module instantiates the ``OrchestratorService`` (via ``api.routes.svc``),
which loads ``policy.yaml`` and ``workflow.yaml`` once per process. The CLI (``orchestrator.cli``) talks to
this process so that in-memory live runs and their approvals share one registry.
"""

from __future__ import annotations

from fastapi import FastAPI

from .api.routes import router

app = FastAPI(
    title="agentic-sdlc orchestrator",
    version="0.1.0",
    description="Agents propose; gates verify; humans approve.",
)
app.include_router(router)


@app.get("/healthz")
async def healthz() -> dict[str, str]:
    """Liveness probe: always ``{"status": "ok"}`` once the app has imported (policy and workflow loaded)."""
    return {"status": "ok"}
