"""T2: the offline loop. FakeClient satisfies every agent schema and drives the service to approval_design."""

from __future__ import annotations

from pathlib import Path

import pytest

from orchestrator.agents.catalog import AGENTS
from orchestrator.config import Settings
from orchestrator.llm.fake import CANNED, FakeClient
from orchestrator.models.spec import Spec
from orchestrator.models.state import NodeStatus, RunStatus
from orchestrator.models.trace import Kind
from orchestrator.service import OrchestratorService

REPO = Path(__file__).resolve().parent.parent


def settings(tmp_path: Path, **kw: object) -> Settings:
    base: dict[str, object] = {
        "llm": "fake",
        "anthropic_api_key": None,
        "runs_dir": tmp_path / "runs",
        "cache_dir": tmp_path / "runs" / "cache",
        "workspace": tmp_path
        / "no-workspace",  # greenfield: the repo's workspace/url-shortener must not seed
        "policy_path": REPO / "policy.yaml",
        "workflow_path": REPO / "workflow.yaml",
        "specs_dir": REPO / "specs" / "scenarios",
    }
    base.update(kw)
    return Settings(**base)  # type: ignore[arg-type]


@pytest.mark.parametrize("agent", list(AGENTS.values()), ids=lambda a: a.name)
async def test_fake_client_satisfies_every_agent_schema(agent) -> None:  # type: ignore[no-untyped-def]
    out, usage = await FakeClient().structured("sys", "prompt", agent.output)
    assert isinstance(out, agent.output)
    assert usage.cost_usd == 0 and usage.tokens_in == 0


async def test_fake_client_override_is_validated_and_unknown_schema_is_an_error() -> None:
    amb = {"id": "q1", "question": "which?", "options": ["a", "b"], "default_if_unanswered": "a"}
    fake = FakeClient({"Spec": {**CANNED["Spec"], "ambiguities": [amb]}})
    spec, _ = await fake.structured("", "", Spec)
    assert spec.needs_clarification and fake.calls == ["Spec"]

    class Unknown(Spec):
        pass

    with pytest.raises(ValueError, match="no canned artifact"):
        await fake.structured("", "", Unknown)


def test_mode_resolution(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    assert OrchestratorService(settings(tmp_path, llm="fake"))._mode(replay=False) == "fake"
    assert OrchestratorService(settings(tmp_path, llm="fake"))._mode(replay=True) == "replay"
    assert OrchestratorService(settings(tmp_path, llm="auto"))._mode(replay=False) == "replay"
    assert (
        OrchestratorService(settings(tmp_path, llm="auto", anthropic_api_key="k"))._mode(False) == "anthropic"
    )
    with pytest.raises(RuntimeError, match="requires ANTHROPIC_API_KEY"):
        OrchestratorService(settings(tmp_path, llm="anthropic"))._runner("anthropic", False, "greenfield")


async def test_greenfield_reaches_approval_design_offline(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    svc = OrchestratorService(settings(tmp_path))
    live = await svc.start("greenfield")
    st = live.state
    assert st.status == RunStatus.AWAITING_APPROVAL, st
    assert st.nodes["approval_design"] == NodeStatus.AWAITING_APPROVAL
    for n in ("requirement", "planning", "architecture", "security_review", "risk_analysis"):
        assert st.nodes[n] == NodeStatus.PASSED, n
    assert st.nodes["clarify"] == NodeStatus.SKIPPED  # canned spec has no ambiguities
    assert st.nodes["impact"] == NodeStatus.SKIPPED  # greenfield sandbox has no code
    assert st.nodes["implementation"] == NodeStatus.PENDING  # a fake run never auto-approves
    assert live.ctx.replay is False
    assert live.ctx.get("plan").run_id == live.state.run_id
    events = svc.trace.events(st.run_id)
    kinds = {e.kind for e in events}
    assert Kind.APPROVAL_REQUESTED in kinds and Kind.APPROVAL_GRANTED not in kinds
    assert sum(1 for e in events if e.kind == Kind.LLM_CALL) == 5
    assert (tmp_path / "runs" / st.run_id / "state.json").exists()
