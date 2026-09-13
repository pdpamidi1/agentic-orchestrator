"""Thin CLI over the running API (uvicorn orchestrator.main:app) so run state lives in one process.
`sdlc graph` and `sdlc metrics` work offline against the runs/ directory."""

from __future__ import annotations

import json
import os

import httpx
import typer

from .config import Settings
from .engine.graph import Graph
from .trace.metrics import compute
from .trace.sink import JsonlSink

app = typer.Typer(no_args_is_help=True)
BASE = os.environ.get("SDLC_API", "http://localhost:8080")


def _post(path: str, body: dict) -> None:  # type: ignore[type-arg]
    r = httpx.post(f"{BASE}{path}", json=body, timeout=3600)
    r.raise_for_status()
    typer.echo(json.dumps(r.json(), indent=2))


@app.command()
def run(
    scenario: str = typer.Option(..., help="greenfield | brownfield | ambiguous"),
    replay: bool = typer.Option(False, help="cached LLM responses + recorded patches; no API key"),
    record: bool = typer.Option(False, help="live run that also writes the replay cache"),
) -> None:
    _post("/runs", {"scenario": scenario, "replay": replay, "record": record})


@app.command()
def status(run_id: str) -> None:
    typer.echo(json.dumps(httpx.get(f"{BASE}/runs/{run_id}").json(), indent=2))


@app.command()
def approve(run_id: str, node_id: str, who: str = "human") -> None:
    _post(f"/runs/{run_id}/approvals/{node_id}", {"who": who})


@app.command()
def reject(run_id: str, node_id: str, reason: str = "", who: str = "human") -> None:
    _post(f"/runs/{run_id}/rejections/{node_id}", {"who": who, "reason": reason})


@app.command()
def answer(run_id: str, answers_json: str) -> None:
    _post(f"/runs/{run_id}/answers", {"answers": json.loads(answers_json)})


@app.command()
def metrics(run_id: str) -> None:
    s = Settings()
    typer.echo(json.dumps(compute(run_id, JsonlSink(s.runs_dir).events(run_id)).as_dict(), indent=2))


@app.command()
def graph() -> None:
    typer.echo(Graph.load(Settings().workflow_path).to_mermaid())


if __name__ == "__main__":
    app()
