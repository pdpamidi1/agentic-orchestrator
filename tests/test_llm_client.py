"""T6 prep (offline): prompts render for every agent, schemas serialise, the Anthropic client repairs and
prices correctly, record -> replay round-trips, and --record refuses to run without live agents."""

from __future__ import annotations

import asyncio
import json
import string
from pathlib import Path
from types import SimpleNamespace

import pytest
from pydantic import ValidationError
from test_fake_llm import settings

from orchestrator.agents.base import PROMPTS
from orchestrator.agents.catalog import AGENTS
from orchestrator.engine.context import RunContext
from orchestrator.llm.client import AnthropicClient, RecordingClient, ReplayClient, _key
from orchestrator.llm.fake import CANNED, FakeClient
from orchestrator.models import Plan, Spec
from orchestrator.service import OrchestratorService

STANDARD_KEYS = {"feedback", "answers", "run_id", "target_stack"}


@pytest.mark.parametrize("agent_cls", list(AGENTS.values()), ids=lambda a: a.name)
def test_prompt_placeholders_match_reads(agent_cls) -> None:  # type: ignore[no-untyped-def]
    template = (PROMPTS / f"{agent_cls.name}.md").read_text()
    placeholders = {f[1] for f in string.Formatter().parse(template) if f[1]}
    assert placeholders <= set(agent_cls.reads) | STANDARD_KEYS, (
        placeholders - set(agent_cls.reads) - STANDARD_KEYS
    )
    assert set(agent_cls.reads) <= placeholders  # every artifact the agent reads reaches the prompt


@pytest.mark.parametrize("agent_cls", list(AGENTS.values()), ids=lambda a: a.name)
async def test_prompts_render_with_real_artifacts(agent_cls, ctx: RunContext) -> None:  # type: ignore[no-untyped-def]
    fake = FakeClient()
    ctx.put("requirement_text", "Build a URL shortener.", "intake")
    for name, cls in AGENTS.items():
        out, _ = await fake.structured("", "", cls.output)
        ctx.put(cls.produces, out.model_dump() if name == "reviewer" else out, name)
    ctx.feedback[agent_cls.name] = {"hint": "previous attempt feedback"}
    rendered = agent_cls(fake).render(ctx)
    assert rendered.startswith("Run: r1. Target stack: python.")
    assert "previous attempt feedback" in rendered and "{" + agent_cls.reads[0] + "}" not in rendered


@pytest.mark.parametrize("agent_cls", list(AGENTS.values()), ids=lambda a: a.name)
def test_output_schema_is_json_serialisable(agent_cls) -> None:  # type: ignore[no-untyped-def]
    schema = agent_cls.output.model_json_schema()
    assert schema["type"] == "object" and json.dumps(schema)


def fake_anthropic(responses: list) -> tuple[AnthropicClient, list]:  # type: ignore[type-arg]
    """AnthropicClient with `client.messages.parse` replaced by a scripted stub; returns (client, calls)."""
    ac = AnthropicClient.__new__(AnthropicClient)
    ac.model = "claude-opus-5"
    ac.workspace_id = None
    calls: list = []  # type: ignore[type-arg]

    async def parse(**kw):  # type: ignore[no-untyped-def]
        calls.append(kw)
        r = responses.pop(0)
        if isinstance(r, Exception):
            raise r
        return r

    ac.client = SimpleNamespace(messages=SimpleNamespace(parse=parse))  # type: ignore[assignment]
    return ac, calls


def ok_response(parsed: object, tokens_in: int = 1000, tokens_out: int = 200) -> SimpleNamespace:
    return SimpleNamespace(
        parsed_output=parsed,
        stop_reason="end_turn",
        usage=SimpleNamespace(input_tokens=tokens_in, output_tokens=tokens_out),
    )


def validation_error() -> ValidationError:
    try:
        Spec.model_validate({})
    except ValidationError as e:
        return e
    raise AssertionError("unreachable")


async def test_anthropic_client_repairs_once_and_prices_usage() -> None:
    spec = Spec.model_validate(CANNED["Spec"])
    ac, calls = fake_anthropic([validation_error(), ok_response(spec)])
    out, usage = await ac.structured("sys", "prompt", Spec, max_repairs=2)
    assert out == spec and len(calls) == 2
    first, second = calls
    assert first["output_format"] is Spec and first["system"] == "sys" and first["max_tokens"] == 16000
    assert "thinking" not in first and "tool_choice" not in first  # adaptive thinking, no forced tool use
    assert (
        second["messages"][-1]["role"] == "user" and "failed validation" in second["messages"][-1]["content"]
    )
    assert (usage.tokens_in, usage.tokens_out) == (1000, 200)
    assert usage.cost_usd == round(1000 * 5.0 / 1e6 + 200 * 25.0 / 1e6, 5)  # claude-opus-5 rates


def test_anthropic_client_sends_the_workspace_header_when_configured() -> None:
    with_ws = AnthropicClient("claude-opus-5", "sk-test", "wrkspc_123")
    assert with_ws.client.default_headers.get("anthropic-workspace-id") == "wrkspc_123"
    assert "anthropic-workspace-id" not in AnthropicClient("claude-opus-5", "sk-test").client.default_headers


async def test_anthropic_client_gives_up_after_max_repairs_and_surfaces_refusals() -> None:
    ac, calls = fake_anthropic([validation_error(), validation_error()])
    with pytest.raises(ValueError, match="schema invalid after 1 repairs"):
        await ac.structured("sys", "prompt", Spec, max_repairs=1)
    assert len(calls) == 2
    refused = SimpleNamespace(
        parsed_output=None,
        stop_reason="refusal",
        stop_details=SimpleNamespace(category="cyber"),
        usage=SimpleNamespace(input_tokens=10, output_tokens=0),
    )
    ac, _ = fake_anthropic([refused])
    with pytest.raises(ValueError, match="refused \\(cyber\\)"):
        await ac.structured("sys", "prompt", Spec)


async def test_recording_then_replay_round_trips(tmp_path: Path) -> None:
    cache = tmp_path / "llm"
    recorder = RecordingClient(FakeClient(), cache)
    spec, _ = await recorder.structured("sys", "prompt", Spec)
    files = list(cache.glob("*.json"))
    assert len(files) == 1 and json.loads(files[0].read_text())["schema"] == "Spec"
    replayed, _ = await ReplayClient(cache).structured("sys", "prompt", Spec)
    assert replayed == spec
    # residual prompt drift falls back to the only recorded Spec; a schema never recorded is a miss
    drifted, _ = await ReplayClient(cache).structured("sys", "a different prompt", Spec)
    assert drifted == spec
    with pytest.raises(FileNotFoundError, match="no cached response for Plan"):
        await ReplayClient(cache).structured("sys", "prompt", Plan)


async def test_record_refuses_without_live_agents(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    svc = OrchestratorService(settings(tmp_path, llm="auto"))
    with pytest.raises(RuntimeError, match="nothing to record in replay mode"):
        await svc.start("greenfield", record=True)
    assert not (tmp_path / "runs" / "cache").exists()


def _empty_objects(node: object, path: str = "") -> list[str]:
    """Paths in a transformed schema that became `{}`-only objects (a dict the API cannot express)."""
    found: list[str] = []
    if isinstance(node, dict):
        if (
            node.get("type") == "object"
            and node.get("properties") == {}
            and not node.get("additionalProperties")
        ):
            found.append(path or "<root>")
        for k, v in node.items():
            found += _empty_objects(v, f"{path}.{k}" if path else k)
    elif isinstance(node, list):
        for i, v in enumerate(node):
            found += _empty_objects(v, f"{path}[{i}]")
    return found


@pytest.mark.parametrize("agent_cls", list(AGENTS.values()), ids=lambda a: a.name)
def test_no_agent_schema_degrades_to_an_empty_object(agent_cls) -> None:  # type: ignore[no-untyped-def]
    from anthropic.lib._parse._transform import transform_schema

    assert _empty_objects(transform_schema(agent_cls.output.model_json_schema())) == []


async def test_recording_client_reuses_valid_hits_and_rerecords_stale_ones(tmp_path: Path) -> None:
    inner = FakeClient()
    rec = RecordingClient(inner, tmp_path)
    await rec.structured("sys", "prompt", Spec)
    await rec.structured("sys", "prompt", Spec)
    assert inner.calls == ["Spec"]  # second call served from the cache, no live call
    stale = tmp_path / f"{_key('sys', 'prompt', Spec)}.json"
    await asyncio.to_thread(
        stale.write_text, json.dumps({"schema": "Spec", "output": {"broken": True}, "usage": {}})
    )
    spec, _ = await rec.structured("sys", "prompt", Spec)
    assert inner.calls == ["Spec", "Spec"] and spec.summary  # re-recorded
    assert json.loads(await asyncio.to_thread(stale.read_text))["output"]["summary"] == spec.summary
