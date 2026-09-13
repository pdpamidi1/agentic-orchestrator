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
    return {"status": "ok"}
