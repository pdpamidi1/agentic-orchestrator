"""Thin CLI over the running API (uvicorn orchestrator.main:app) so run state lives in one process.
`sdlc graph` and `sdlc metrics` work offline against the runs/ directory.

Position in the pipeline: a client of ``api`` (over HTTP via httpx); it never imports the engine. Live
runs are kept in memory by the API process, so ``run``, ``status``, ``approve``, ``reject``, ``answer`` and
``deliver`` must all target the same server (``SDLC_API``, default ``http://localhost:8080``). Every
command prints the server's JSON reply (indented) and exits non-zero on an HTTP error status.
"""

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
# base URL of the API process; overridable so the CLI can drive a remote or non-default port
BASE = os.environ.get("SDLC_API", "http://localhost:8080")
# how long a blocking call (run/approve/answer) waits for the next pause; the run outlives the client
TIMEOUT = float(os.environ.get("SDLC_CLI_TIMEOUT", "3600"))


def _post(path: str, body: dict) -> None:  # type: ignore[type-arg]
    """POST ``body`` as JSON to ``BASE + path`` and print the reply.

    The request blocks until the run pauses or finishes, so the read timeout is ``SDLC_CLI_TIMEOUT``
    seconds (default one hour). Hitting it does not stop the run: the server keeps executing after the
    client disconnects, so the CLI says how to follow the run instead of failing. A 4xx/5xx reply (e.g.
    404 unknown run, 409 deliver on a non-COMPLETED run) raises ``httpx.HTTPStatusError``.
    """
    try:
        r = httpx.post(f"{BASE}{path}", json=body, timeout=TIMEOUT)
    except httpx.ReadTimeout:
        typer.echo(
            f"still running after {TIMEOUT:.0f}s; the run continues on the server. "
            "Follow it with `sdlc status <run>` (or SDLC_CLI_TIMEOUT=<seconds> to wait longer)."
        )
        return
    r.raise_for_status()
    data = r.json()
    brief = data.pop("pending_approval", None) if isinstance(data, dict) else None
    typer.echo(json.dumps(data, indent=2))
    if brief:  # the run is waiting for a human: show the full proposal, not just the status
        typer.echo("\n" + brief["markdown"])


@app.command()
def run(
    scenario: str = typer.Option(..., help="greenfield | brownfield | ambiguous"),
    replay: bool = typer.Option(False, help="cached LLM responses + recorded patches; no API key"),
    record: bool = typer.Option(False, help="live run that also writes the replay cache"),
    workspace: str = typer.Option(None, help="brownfield: existing project copied into the sandbox"),
) -> None:
    """Start a run (``POST /runs``) and print its id, status, node statuses and pending questions.

    ``--replay`` and ``--record`` are mutually exclusive in effect: the server rejects ``--record`` when
    the resolved mode is replay (nothing to record).
    """
    _post("/runs", {"scenario": scenario, "replay": replay, "record": record, "workspace": workspace})


@app.command()
def status(run_id: str) -> None:
    """Print the live ``RunState`` (``GET /runs/{run_id}``); prints the 404 body for an unknown run."""
    typer.echo(json.dumps(httpx.get(f"{BASE}/runs/{run_id}").json(), indent=2))


@app.command()
def brief(run_id: str, node_id: str = "approval_design") -> None:
    """Print the detailed brief for a pending approval (``GET .../approvals/{node}``)."""
    r = httpx.get(f"{BASE}/runs/{run_id}/approvals/{node_id}")
    typer.echo(r.json()["markdown"] if r.status_code == 200 else json.dumps(r.json(), indent=2))


@app.command()
def approve(run_id: str, node_id: str, who: str = "human") -> None:
    """Approve the node waiting in AWAITING_APPROVAL and resume the run (``POST .../approvals/{node}``)."""
    _post(f"/runs/{run_id}/approvals/{node_id}", {"who": who})


@app.command()
def resume(run_id: str, who: str = "human") -> None:
    """Continue a run whose API process died mid-run (``POST .../resume``); paused runs use approve/answer."""
    _post(f"/runs/{run_id}/resume", {"who": who})


@app.command()
def reject(run_id: str, node_id: str, reason: str = "", who: str = "human") -> None:
    """Reject a pending checkpoint; the run halts with ``human.reject`` (``POST .../rejections/{node}``)."""
    _post(f"/runs/{run_id}/rejections/{node_id}", {"who": who, "reason": reason})


@app.command()
def answer(run_id: str, answers_json: str) -> None:
    """Answer clarification questions: ``answers_json`` is a JSON object of ambiguity id -> answer."""
    _post(f"/runs/{run_id}/answers", {"answers": json.loads(answers_json)})


@app.command()
def deliver(
    run_id: str, to: str = typer.Option(None, help="destination dir; default SDLC_WORKSPACE")
) -> None:
    """Copy a COMPLETED run's sandbox tree into the workspace (the release.merge outcome)."""
    _post(f"/runs/{run_id}/deliver", {"to": to})


@app.command()
def metrics(run_id: str) -> None:
    """Compute and print ``RunMetrics`` offline from ``runs/<id>/trace.jsonl`` (no API needed)."""
    s = Settings()
    typer.echo(json.dumps(compute(run_id, JsonlSink(s.runs_dir).events(run_id)).as_dict(), indent=2))


@app.command()
def report(run_id: str) -> None:
    """Render ``runs/<id>/run_report.md`` offline from the run directory (artifacts, trace, approvals)."""
    from .reports.run_report import write

    typer.echo(str(write(Settings().runs_dir, run_id)))


@app.command()
def graph() -> None:
    """Print ``workflow.yaml`` as a Mermaid diagram, offline (loaded from ``Settings.workflow_path``)."""
    typer.echo(Graph.load(Settings().workflow_path).to_mermaid())


if __name__ == "__main__":
    app()
