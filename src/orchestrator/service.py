"""Composition root: builds a Runner and RunContext from settings; keeps live runs in memory (prototype)."""

from __future__ import annotations

import os
import uuid
from dataclasses import dataclass
from pathlib import Path

from .config import Settings
from .engine.context import RunContext
from .engine.graph import Graph, NodeDef
from .engine.handlers import build_handlers
from .engine.runner import Runner
from .executors.base import CodeExecutor
from .executors.claude_code_cli import ClaudeCodeCliExecutor
from .executors.fake import FakeExecutor
from .executors.replay import ReplayExecutor
from .llm.client import AnthropicClient, LLMClient, RecordingClient, ReplayClient
from .llm.fake import FakeClient
from .models import RunState, load_policy
from .sandbox.git import GitSandbox
from .store.file_store import FileStore
from .trace.metrics import RunMetrics, compute
from .trace.sink import JsonlSink


class GitRollback:
    async def rollback(self, ctx: RunContext, node: NodeDef) -> None:
        cs = ctx.get("changeset")
        if not cs:
            return
        git = GitSandbox(ctx.sandbox)
        for task_id, sha in reversed(list(cs.commits.items())):
            await git.export_patch(
                f"{sha}~1",
                Path(ctx.policy.observability.get("runs_dir", "runs"))
                / ctx.run_id
                / "rejected"
                / f"{task_id}.patch",
            )
            await git.revert(sha)


@dataclass
class LiveRun:
    ctx: RunContext
    state: RunState


class OrchestratorService:
    def __init__(self, settings: Settings | None = None) -> None:
        self.s = settings or Settings()
        self.policy = load_policy(str(self.s.policy_path))
        self.graph = Graph.load(self.s.workflow_path)
        self.store = FileStore(self.s.runs_dir)
        self.trace = JsonlSink(self.s.runs_dir)
        self.runs: dict[str, LiveRun] = {}

    def _api_key(self) -> str | None:
        return self.s.anthropic_api_key or os.environ.get("ANTHROPIC_API_KEY")

    def _mode(self, replay: bool) -> str:
        """How agents are backed for this run: --replay wins, then SDLC_LLM, then key presence.

        Only "replay" enables replay semantics (auto-approve, default answers); "fake" is a live run
        whose agents happen to be canned, so every human checkpoint still pauses.
        """
        if replay:
            return "replay"
        if self.s.llm != "auto":
            return self.s.llm
        return "anthropic" if self._api_key() else "replay"

    def _runner(self, mode: str, record: bool) -> Runner:
        llm: LLMClient
        executor: CodeExecutor
        if mode == "fake":
            llm = FakeClient()
            executor = FakeExecutor()
        elif mode == "replay":
            llm = ReplayClient(self.s.cache_dir / "llm")
            executor = ReplayExecutor(self.s.cache_dir / "changesets")
        elif mode == "anthropic":
            api_key = self._api_key()
            if not api_key:
                raise RuntimeError("SDLC_LLM=anthropic requires ANTHROPIC_API_KEY (or use SDLC_LLM=fake)")
            llm = AnthropicClient(self.s.model, api_key)
            if record:
                llm = RecordingClient(llm, self.s.cache_dir / "llm")
            executor = ClaudeCodeCliExecutor()
        else:
            raise ValueError(f"unknown llm mode {mode!r}")
        return Runner(self.graph, build_handlers(llm, executor), self.store, GitRollback())

    async def start(
        self,
        scenario: str,
        requirement_text: str | None = None,
        *,
        replay: bool = False,
        record: bool = False,
        extra: dict[str, str] | None = None,
    ) -> LiveRun:
        run_id = f"{scenario}-{uuid.uuid4().hex[:8]}"
        mode = self._mode(replay)
        text = requirement_text or (self.s.specs_dir / f"{scenario}.md").read_text(encoding="utf-8")
        sandbox = self.s.runs_dir / run_id / "sandbox"
        sandbox.mkdir(parents=True, exist_ok=True)
        ctx = RunContext(
            run_id=run_id,
            scenario=scenario,
            policy=self.policy,
            sandbox=sandbox,
            trace=self.trace,
            target_stack=self.s.target_stack,
            replay=mode == "replay",
        )
        ctx.put("requirement_text", text, "intake")
        for k, v in (extra or {}).items():
            ctx.put(k, v, "intake")
        state = RunState(run_id=run_id, scenario=scenario, nodes=self.graph.initial_statuses())
        live = LiveRun(ctx, state)
        self.runs[run_id] = live
        live.state = await self._runner(mode, record).run(ctx, state)
        await self._persist_artifacts(live)
        return live

    async def approve(self, run_id: str, node_id: str, who: str = "human") -> LiveRun:
        live = self.runs[run_id]
        node = self.graph.nodes.get(node_id)
        action = node.high_impact if node is not None and node.high_impact else "task.high_impact"
        await self.store.record_approval(run_id, node_id, action, "APPROVED", who)
        live.state = await self._runner(self._mode(live.ctx.replay), False).approve(
            live.ctx, live.state, node_id, who
        )
        await self._persist_artifacts(live)
        return live

    async def reject(self, run_id: str, node_id: str, reason: str, who: str = "human") -> LiveRun:
        live = self.runs[run_id]
        await self.store.record_approval(run_id, node_id, "-", "REJECTED", who)
        live.state = await self._runner(self._mode(live.ctx.replay), False).reject(
            live.ctx, live.state, node_id, who, reason
        )
        return live

    async def answer(self, run_id: str, answers: dict[str, str], who: str = "human") -> LiveRun:
        live = self.runs[run_id]
        node_id = next(n for n, st in live.state.nodes.items() if st.value == "AWAITING_INPUT")
        live.state = await self._runner(self._mode(live.ctx.replay), False).answer(
            live.ctx, live.state, node_id, answers, who
        )
        await self._persist_artifacts(live)
        return live

    def metrics(self, run_id: str) -> RunMetrics:
        return compute(run_id, self.trace.events(run_id))

    async def _persist_artifacts(self, live: LiveRun) -> None:
        for a in live.ctx.artifacts.values():
            await self.store.save_artifact(live.ctx.run_id, a.name, a.version, a.value)
