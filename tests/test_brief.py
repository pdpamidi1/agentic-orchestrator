"""Approval brief: the human decides on the real proposal: design, plan, cost, contract/structure changes."""

from __future__ import annotations

from pathlib import Path

import pytest
from test_fake_llm import settings

from orchestrator.engine.brief import build_brief, render_markdown
from orchestrator.engine.context import RunContext
from orchestrator.engine.graph import Graph
from orchestrator.llm.fake import CANNED, SCENARIOS
from orchestrator.models import Design, Plan, RepoMap, Spec, TraceEvent, load_policy
from orchestrator.models.state import RunStatus
from orchestrator.models.trace import Kind
from orchestrator.sandbox.repo_map import build_repo_map
from orchestrator.service import OrchestratorService
from orchestrator.trace.sink import InMemorySink

REPO = Path(__file__).resolve().parent.parent
POLICY = load_policy(str(REPO / "policy.yaml"))
FIXTURE = REPO / "tests" / "fixtures" / "brownfield_ws"


def ctx_with(tmp_path: Path, scenario: str, repo: RepoMap | None) -> RunContext:
    ctx = RunContext(
        run_id="r1",
        scenario=scenario,
        policy=POLICY,
        sandbox=tmp_path / "sb",
        trace=InMemorySink(),
        target_stack="java",
    )
    over = SCENARIOS.get(scenario, {})
    ctx.put("spec", Spec.model_validate(over.get("Spec", CANNED["Spec"])), "requirement")
    ctx.put("plan", Plan.model_validate(over.get("Plan", CANNED["Plan"])), "planning")
    ctx.put("design", Design.model_validate(over.get("Design", CANNED["Design"])), "architecture")
    if repo is not None:
        ctx.put("repo_map", repo, "intake")
    ctx.trace.emit(TraceEvent(run_id="r1", kind=Kind.LLM_CALL, actor="planner", cost_usd=0.4))
    ctx.trace.emit(TraceEvent(run_id="r1", kind=Kind.EXECUTOR_CALL, actor="claude-code", cost_usd=2.5))
    return ctx


def test_greenfield_brief_lists_everything_as_new_with_cost(tmp_path: Path) -> None:
    ctx = ctx_with(tmp_path, "greenfield", None)
    node = Graph.load(REPO / "workflow.yaml").nodes["approval_design"]
    b = build_brief(node, ctx, "plan.approve")
    assert b["cost"] == {"llm_usd": 0.4, "executor_usd": 2.5, "total_usd": 2.9}
    assert (
        b["changes"]["endpoints_added"] == ["GET /{}", "POST /api/v1/urls"]
        and b["changes"]["endpoints_removed"] == []
    )
    assert b["changes"]["tables_added"] == ["urls"] and "shortener.api" in b["changes"]["packages_added"]
    high = [t for t in b["tasks"] if t["impact_level"] == "HIGH"]
    assert high and high[0]["protected_actions"] == ["infrastructure.change", "release.config"]
    md = b["markdown"]
    assert "# Approval requested: approval_design (plan.approve)" in md and "2.9 USD" in md
    assert "Endpoints added: GET /{}, POST /api/v1/urls" in md and "| T4 | HIGH |" in md and "Decisions" in md


def test_brownfield_brief_diffs_against_the_repo_map(tmp_path: Path) -> None:
    repo = build_repo_map(FIXTURE, "python")
    ctx = ctx_with(tmp_path, "brownfield", repo)
    node = Graph.load(REPO / "workflow.yaml").nodes["approval_design"]
    b = build_brief(node, ctx, "plan.approve")
    ch = b["changes"]
    assert ch["endpoints_added"] == ["GET /api/v1/urls/{}/stats"]  # the new stats endpoint
    assert sorted(ch["endpoints_changed"]) == ["GET /{}", "POST /api/v1/urls"]  # already served today
    assert ch["tables_added"] == ["click_events", "click_stats"] and ch["endpoints_removed"] == []
    md = render_markdown(b)
    assert "Tables added (migration): click_events, click_stats" in md
    assert "| B1 | HIGH |" in md and "schema.migration" in md and "dependency.major_version" in md


async def test_runner_pauses_with_the_brief_and_persists_it(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    svc = OrchestratorService(settings(tmp_path, target_stack="python"))
    live = await svc.start("greenfield")
    assert live.state.status == RunStatus.AWAITING_APPROVAL
    req = [e for e in svc.trace.events(live.state.run_id) if e.kind == Kind.APPROVAL_REQUESTED][-1]
    assert req.payload["summary"]["node"] == "approval_design" and "markdown" in req.payload["summary"]
    brief = live.ctx.get("approval_brief")
    assert brief["action"] == "plan.approve" and brief["changes"]["tables_added"] == ["urls"]
    assert (tmp_path / "runs" / live.state.run_id / "artifacts" / "approval_brief.v1.json").exists()
