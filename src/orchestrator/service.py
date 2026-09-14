"""Composition root: builds a Runner and RunContext from settings; live runs in memory, reloadable from disk.

Position in the pipeline: ``api -> service -> engine -> {agents, executors, sandbox, trace, store}``. This is
the only module that knows how to wire an LLM client, a code executor, the file store, the trace sink and
the git rollback strategy into a ``Runner``. The API routes and the CLI never touch the engine directly.

Key invariants:
- Live runs (``LiveRun``: context + state) live in ``OrchestratorService.runs``, a per-process dict, and
  are rebuilt from ``runs/<id>`` (state, artifacts, ``context.json``) by ``load`` when another process
  asks for them (TASKS T12), so approve/answer/deliver survive an API restart.
- Mode selection is ``--replay`` > ``SDLC_LLM`` > key presence (``_mode``). Only ``replay`` auto-approves.
- ``--record`` wraps whichever live/fake client and executor are chosen and writes the replay cache under
  ``Settings.cache_dir``; it is an error in replay mode (nothing to record).
- Artifacts are persisted as ``runs/<id>/artifacts/<name>.v<n>.json`` after every start/approve/answer;
  approvals are appended to ``runs/<id>/approvals.jsonl``; state and trace are written by the engine.
- Delivery (copying the sandbox into the workspace) is only possible for a COMPLETED run.
"""

from __future__ import annotations

import ast
import asyncio
import json
import os
import shutil
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .agents.catalog import AGENTS
from .config import Settings
from .engine.context import Artifact, RunContext
from .engine.graph import Graph, NodeDef
from .engine.handlers import build_handlers
from .engine.runner import Runner
from .executors.base import Changeset, CodeExecutor
from .executors.claude_code_cli import ClaudeCodeCliExecutor
from .executors.fake import FakeExecutor
from .executors.recording import RecordingExecutor
from .executors.replay import ReplayExecutor
from .llm.client import AnthropicClient, LLMClient, RecordingClient, ReplayClient
from .llm.fake import PLANTED, FakeClient
from .models import Kind, RepoMap, RunState, RunStatus, ValidationResult, load_policy
from .sandbox.git import GitSandbox
from .sandbox.repo_map import SKIP_DIRS, build_repo_map
from .store.file_store import FileStore
from .trace.metrics import RunMetrics, compute
from .trace.sink import JsonlSink


class GitRollback:
    """Rollback strategy handed to the ``Runner``: undo a node's task commits with ``git revert``.

    The runner calls ``rollback`` when a node's attempts are exhausted or a policy violation must be
    undone. Each reverted commit is first exported as a patch so the rejected work stays inspectable.
    """

    async def rollback(self, ctx: RunContext, node: NodeDef) -> None:
        """Revert every task commit recorded in the ``changeset`` artifact, newest first.

        No-op when the node produced no changeset. Side effects: writes
        ``runs/<id>/rejected/<task>.patch`` per commit and adds a revert commit per task to the run branch.
        """
        cs = ctx.get("changeset")
        if not cs:
            return
        git = GitSandbox(ctx.sandbox)
        for task_id, sha in reversed(list(cs.commits.items())):
            # runs/<id>/rejected/<task>.patch, next to the sandbox (never a cwd-relative path)
            await git.export_patch(f"{sha}~1", ctx.sandbox.parent / "rejected" / f"{task_id}.patch")
            await git.revert(sha)


def _copy_tree(src: Path, dest: Path) -> int:
    """Copy ``src`` over ``dest`` (creating it), skipping ``.git`` and tool/cache dirs; return the file count.

    Used by ``deliver``: the destination is the workspace, so the run branch's ``.git`` must not travel.
    The count is the total number of files under ``dest`` afterwards, not only those copied.
    """
    dest.mkdir(parents=True, exist_ok=True)
    shutil.copytree(src, dest, dirs_exist_ok=True, ignore=shutil.ignore_patterns(".git", *SKIP_DIRS))
    return sum(1 for p in dest.rglob("*") if p.is_file())


def _seed(workspace: Path, sandbox: Path) -> bool:
    """Copy the target workspace (if configured and present) into the run's sandbox.

    Greenfield = no workspace.

    Returns ``True`` when something was copied (brownfield), ``False`` for a missing or empty workspace.
    ``SKIP_DIRS`` (which includes ``.git``) is not copied: the sandbox gets its own repository, branch and
    ``sdlc/base`` tag from the engine.
    """
    if not workspace.is_dir() or not any(workspace.iterdir()):
        return False
    shutil.copytree(workspace, sandbox, dirs_exist_ok=True, ignore=shutil.ignore_patterns(*SKIP_DIRS))
    return True


# Artifact name -> model used to rebuild a context from ``runs/<id>/artifacts`` (resume, TASKS T12).
# Agent outputs come from the catalog; the executor's Changeset (a dataclass) and the gates' result are
# added by hand. ``review`` is stored as a plain dict by ReviewerAgent.post and stays one. Names missing
# here (requirement_text, approval_brief, intake extras) are loaded as the raw JSON value.
ARTIFACT_TYPES: dict[str, type[Any]] = {
    **{a.produces: a.output for a in AGENTS.values() if a.produces != "review"},
    "changeset": Changeset,
    "validation_result": ValidationResult,
    "repo_map": RepoMap,
}


def _revive(name: str, raw: Any) -> Any:
    """Turn the JSON of one saved artifact back into the object the handlers expect.

    ``changeset`` files written before the store knew dataclasses hold the ``repr`` string
    (``Changeset(branch='...', commits={...}, ...)``); it is parsed with ``ast`` (a call whose keyword
    values are literals), never evaluated.
    """
    typ = ARTIFACT_TYPES.get(name)
    if typ is None:
        return raw
    if typ is Changeset:
        if isinstance(raw, str):
            call = ast.parse(raw, mode="eval").body
            if not isinstance(call, ast.Call):
                raise ValueError(f"unreadable legacy changeset: {raw[:40]!r}")
            raw = {kw.arg: ast.literal_eval(kw.value) for kw in call.keywords if kw.arg}
        return Changeset(**raw)
    return typ.model_validate(raw)


@dataclass
class LiveRun:
    """An in-flight run as held by the service: its mutable ``RunContext`` and latest ``RunState``.

    ``state`` is replaced (not mutated) by the service after each runner call; ``ctx`` accumulates
    artifacts, feedback, answers and approvals across pauses.
    """

    ctx: RunContext
    state: RunState
    record: bool = False  # keep recording after approvals/answers resume the run


class OrchestratorService:
    """Facade used by the API and (indirectly, over HTTP) by the CLI.

    Loads ``policy.yaml`` and ``workflow.yaml`` once; owns the file store, the jsonl trace sink and the
    in-memory run registry. A fresh ``Runner`` is built per call so that a resumed run uses the same mode
    and recording flag it started with.
    """

    def __init__(self, settings: Settings | None = None) -> None:
        """Wire the service from ``settings`` (default: read from env/.env). Reads both YAML files."""
        self.s = settings or Settings()
        self.policy = load_policy(str(self.s.policy_path))
        self.graph = Graph.load(self.s.workflow_path)
        self.store = FileStore(self.s.runs_dir)
        self.trace = JsonlSink(self.s.runs_dir)
        # run_id -> LiveRun; the prototype's only global mutable state (documented limitation)
        self.runs: dict[str, LiveRun] = {}

    def _api_key(self) -> str | None:
        """Anthropic key from settings (``ANTHROPIC_API_KEY``/``SDLC_ANTHROPIC_API_KEY``) or the raw env."""
        return self.s.anthropic_api_key or os.environ.get("ANTHROPIC_API_KEY")

    def _mode(self, replay: bool) -> str:
        """How agents are backed for this run: --replay wins, then SDLC_LLM, then key presence.

        Only "replay" enables replay semantics (auto-approve, default answers); "fake" is a live run
        whose agents happen to be canned, so every human checkpoint still pauses.

        Returns one of ``"replay"``, ``"fake"``, ``"anthropic"``.
        """
        if replay:
            return "replay"
        if self.s.llm != "auto":
            return self.s.llm
        return "anthropic" if self._api_key() else "replay"

    def _runner(self, mode: str, record: bool, scenario: str) -> Runner:
        """Build a ``Runner`` whose handlers use the LLM client and code executor for ``mode``.

        ``fake``: canned artifacts per schema plus a fake executor that plants one policy failure for the
        scenario (``PLANTED``). ``replay``: recorded responses and patches from ``cache_dir``.
        ``anthropic``: structured-output SDK client plus the Claude Code CLI executor. With ``record`` the
        chosen pair is wrapped so every response/patch is written to the cache.

        Raises ``RuntimeError`` for ``anthropic`` without a key and ``ValueError`` for an unknown mode.
        """
        llm: LLMClient
        executor: CodeExecutor
        if mode == "fake":
            llm = FakeClient(scenario=scenario)
            executor = FakeExecutor(plant=PLANTED.get(scenario, "scope"))
        elif mode == "replay":
            llm = ReplayClient(self.s.cache_dir / "llm")
            executor = ReplayExecutor(self.s.cache_dir / "changesets")
        elif mode == "anthropic":
            api_key = self._api_key()
            if not api_key:
                raise RuntimeError("SDLC_LLM=anthropic requires ANTHROPIC_API_KEY (or use SDLC_LLM=fake)")
            llm = AnthropicClient(self.s.model, api_key, self.s.anthropic_workspace_id)
            executor = ClaudeCodeCliExecutor()
        else:
            raise ValueError(f"unknown llm mode {mode!r}")
        if record:  # record once (live or fake), replay forever
            llm = RecordingClient(llm, self.s.cache_dir / "llm")
            executor = RecordingExecutor(executor, self.s.cache_dir / "changesets")
        return Runner(self.graph, build_handlers(llm, executor, self.graph), self.store, GitRollback())

    async def start(
        self,
        scenario: str,
        requirement_text: str | None = None,
        *,
        replay: bool = False,
        record: bool = False,
        extra: dict[str, str] | None = None,
        workspace: Path | None = None,
    ) -> LiveRun:
        """Create and execute a new run until it completes, halts or pauses on a checkpoint.

        Parameters: ``scenario`` names the spec file and the replay/fake cache slot; ``requirement_text``
        overrides ``specs/scenarios/<scenario>.md``; ``replay``/``record`` select the mode (see ``_mode``);
        ``extra`` adds intake artifacts to the context before the graph starts.

        Side effects: ``runs/<id>/sandbox`` is created and seeded from the workspace (brownfield), the
        ``requirement_text`` and optional ``repo_map`` artifacts are put into context under producer
        ``intake``, the run is registered in ``self.runs`` before executing, and all artifacts are persisted
        afterwards. The engine writes ``state.json`` and ``trace.jsonl``.

        Raises ``RuntimeError`` when ``record`` is requested in replay mode (nothing live to record) and
        ``FileNotFoundError`` when neither a requirement text nor a scenario file exists.
        """
        run_id = f"{scenario}-{uuid.uuid4().hex[:8]}"
        mode = self._mode(replay)
        if record and mode == "replay":
            raise RuntimeError(
                "--record has nothing to record in replay mode: set ANTHROPIC_API_KEY or SDLC_LLM=fake"
            )
        text = requirement_text or (self.s.specs_dir / f"{scenario}.md").read_text(encoding="utf-8")
        sandbox = self.s.runs_dir / run_id / "sandbox"
        sandbox.mkdir(parents=True, exist_ok=True)
        # brownfield only when the caller names a workspace: Settings.workspace is the delivery default and
        # must never turn a greenfield run into a seeded one just because a project was delivered earlier
        seeded = await asyncio.to_thread(_seed, workspace, sandbox) if workspace is not None else False
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
        if seeded:  # brownfield: the agents reason over a map of the existing code, never the raw tree
            ctx.put(
                "repo_map", await asyncio.to_thread(build_repo_map, sandbox, self.s.target_stack), "intake"
            )
        for k, v in (extra or {}).items():
            ctx.put(k, v, "intake")
        state = RunState(run_id=run_id, scenario=scenario, nodes=self.graph.initial_statuses())
        live = LiveRun(ctx, state, record=record)
        self.runs[run_id] = live
        live.state = await self._runner(mode, record, scenario).run(ctx, state)
        await self._persist_artifacts(live)
        return live

    async def live(self, run_id: str) -> LiveRun | None:
        """The in-memory run, or the run rebuilt from ``runs/<id>`` if this process never held it.

        None when nothing was ever saved for ``run_id``. Every API route that used to read ``svc.runs``
        directly goes through here, so approve/answer/deliver survive an API restart (TASKS T12).
        """
        if run_id in self.runs:
            return self.runs[run_id]
        if not self.store.exists(run_id):
            return None
        return await self.load(run_id)

    async def load(self, run_id: str) -> LiveRun:
        """Rebuild a ``LiveRun`` from disk and register it (resume from store, TASKS T12).

        ``state.json`` gives the ``RunState``; the latest version of every ``artifacts/<name>.v<n>.json``
        is revived through ``ARTIFACT_TYPES``; ``context.json`` restores feedback, answers, approval
        tokens, producers and the mode flags. Runs saved before ``context.json`` existed are reconstructed
        from ``trace.jsonl`` (``_context_from_trace``): approvals are replayed decision by decision, so a
        consumed node token is not mistaken for a fresh grant. The sandbox is used where it is; the
        executor handler already skips tasks recorded in the ``changeset``.

        Raises ``FileNotFoundError`` when there is no ``state.json``.
        """
        state = await self.store.load(run_id)
        saved = await self.store.load_context(run_id)
        if saved is None:
            saved = self._context_from_trace(run_id, state)
        ctx = RunContext(
            run_id=run_id,
            scenario=str(saved.get("scenario") or state.scenario),
            policy=self.policy,
            sandbox=self.s.runs_dir / run_id / "sandbox",
            trace=self.trace,
            target_stack=str(saved.get("target_stack") or self.s.target_stack),
            replay=bool(saved.get("replay", False)),
        )
        producers: dict[str, str] = dict(saved.get("producers") or {})
        for name, (version, path) in self.store.latest_artifacts(run_id).items():
            raw = json.loads(path.read_text(encoding="utf-8"))
            ctx.artifacts[name] = Artifact(name, version, _revive(name, raw), producers.get(name, "intake"))
        ctx.feedback.update(saved.get("feedback") or {})
        ctx.answers.update(saved.get("answers") or {})
        ctx.approvals |= set(saved.get("approvals") or ())
        live = LiveRun(ctx, state, record=bool(saved.get("record", False)))
        self.runs[run_id] = live
        return live

    def _context_from_trace(self, run_id: str, state: RunState) -> dict[str, Any]:
        """Best-effort ``context.json`` equivalent for a run that predates it, replayed from the trace.

        Producers: the last ``ARTIFACT_WRITTEN`` per artifact. Approvals follow the same transitions the
        engine made: ``APPROVAL_GRANTED`` adds the node token; a ``POLICY_DECISION`` of ``APPROVED``
        (task.high_impact) or ``SCOPE_APPROVED`` consumes it into ``task:``/``scope:`` tokens, ``OK`` /
        ``VIOLATION`` add ``approved_actions``, ``APPROVAL_REVOKED`` removes what it lists. A scope request
        still waiting for its grant is restored as ``feedback[node]["scope_request"]``. ``record`` is
        inferred from recorded patches (``changeset_patch`` artifacts); ``replay`` from the configured mode.
        Attempt feedback is not reconstructed (a resume after a halt clears it anyway).
        """
        producers: dict[str, str] = {}
        approvals: set[str] = set()
        feedback: dict[str, dict[str, Any]] = {}
        answers: dict[str, str] = {}
        record = False
        for e in self.trace.events(run_id):
            p = e.payload or {}
            if e.kind == Kind.ARTIFACT_WRITTEN:
                if p.get("artifact") == "changeset_patch":
                    record = True
                elif e.node_id and "artifact" in p and "version" in p:
                    producers[str(p["artifact"])] = e.node_id
            elif e.kind == Kind.APPROVAL_REQUESTED and e.node_id and p.get("action") == "task.scope_change":
                summ = p.get("summary") or {}
                feedback.setdefault(e.node_id, {})["scope_request"] = {
                    k: summ.get(k) for k in ("task", "files", "reason")
                }
            elif e.kind == Kind.APPROVAL_GRANTED and e.node_id:
                approvals.add(e.node_id)
            elif e.kind == Kind.INPUT_RECEIVED:
                answers.update({str(k): str(v) for k, v in (p.get("answers") or {}).items()})
            elif e.kind == Kind.POLICY_DECISION:
                if e.status == "APPROVED" and p.get("action") == "task.high_impact" and e.node_id:
                    approvals.discard(e.node_id)
                    approvals.add(f"task:{p.get('task') or e.task_id}")
                elif e.status == "SCOPE_APPROVED" and e.node_id:
                    approvals.discard(e.node_id)
                    approvals |= {f"scope:{e.task_id}:{f}" for f in p.get("files") or []}
                    fb = feedback.get(e.node_id) or {}
                    fb.pop("scope_request", None)
                elif e.status in ("OK", "VIOLATION"):
                    approvals |= set(p.get("approved_actions") or [])
                elif e.status == "APPROVAL_REVOKED":
                    approvals -= set(p.get("revoked") or [])
                elif e.status == "AUTO_APPROVED" and e.node_id:
                    approvals.add(e.node_id)
        return {
            "scenario": state.scenario,
            "replay": self._mode(False) == "replay",
            "record": record,
            "target_stack": self.s.target_stack,
            "producers": producers,
            "feedback": feedback,
            "answers": answers,
            "approvals": sorted(approvals),
        }

    async def approve(self, run_id: str, node_id: str, who: str = "human") -> LiveRun:
        """Record a human approval for ``node_id`` and resume the run.

        The approval is logged to ``approvals.jsonl`` with the node's configured high-impact action (or
        ``task.high_impact`` for a task-level pause) before the runner is resumed with the run's original
        mode and recording flag. Artifacts are persisted afterwards. ``KeyError`` for an unknown run.
        """
        live = await self._get(run_id)
        node = self.graph.nodes.get(node_id)
        # a node without its own high_impact action is pausing on behalf of a HIGH task
        action = node.high_impact if node is not None and node.high_impact else "task.high_impact"
        await self.store.record_approval(run_id, node_id, action, "APPROVED", who)
        live.state = await self._runner(self._mode(live.ctx.replay), live.record, live.ctx.scenario).approve(
            live.ctx, live.state, node_id, who
        )
        await self._persist_artifacts(live)
        return live

    async def reject(self, run_id: str, node_id: str, reason: str, who: str = "human") -> LiveRun:
        """Record a human rejection and safe-stop the run (``halt_reason='human.reject'``).

        Appends a REJECTED line (action ``-``) to ``approvals.jsonl``; the runner emits
        ``APPROVAL_REJECTED`` and ``RUN_HALTED``. Artifacts are not re-persisted (nothing new is produced).
        """
        live = await self._get(run_id)
        await self.store.record_approval(run_id, node_id, "-", "REJECTED", who)
        live.state = await self._runner(self._mode(live.ctx.replay), live.record, live.ctx.scenario).reject(
            live.ctx, live.state, node_id, who, reason
        )
        return live

    async def answer(self, run_id: str, answers: dict[str, str], who: str = "human") -> LiveRun:
        """Deliver clarification answers to the single node in ``AWAITING_INPUT`` and resume the run.

        The node is located by status (``StopIteration`` if none is waiting). Answers are merged into the
        context; the requirements agent then produces the next ``spec`` version, invalidating downstream
        nodes. Artifacts are persisted afterwards.
        """
        live = await self._get(run_id)
        node_id = next(n for n, st in live.state.nodes.items() if st.value == "AWAITING_INPUT")
        live.state = await self._runner(self._mode(live.ctx.replay), live.record, live.ctx.scenario).answer(
            live.ctx, live.state, node_id, answers, who
        )
        await self._persist_artifacts(live)
        return live

    async def deliver(self, run_id: str, dest: Path | None = None) -> Path:
        """Copy the run's sandbox tree (the run branch's working tree, no .git) into the workspace.

        Only a COMPLETED run can be delivered: approval_release is the human 'release.merge' checkpoint.

        ``dest`` defaults to ``Settings.workspace``. Emits ``ARTIFACT_WRITTEN`` (artifact ``delivery``) with
        the destination, the sandbox HEAD sha and the file count. Raises ``RuntimeError`` for any other run
        status (the API maps it to 409) and ``KeyError`` for an unknown run. Returns the destination path.
        """
        live = await self._get(run_id)
        if live.state.status != RunStatus.COMPLETED:
            raise RuntimeError(
                f"deliver needs a COMPLETED run (approval_release); {run_id} is {live.state.status}"
            )
        target = dest or self.s.workspace
        head = await GitSandbox(live.ctx.sandbox).head()
        files = await asyncio.to_thread(_copy_tree, live.ctx.sandbox, target)
        live.ctx.emit(
            Kind.ARTIFACT_WRITTEN,
            node_id="approval_release",
            actor="orchestrator",
            payload={"artifact": "delivery", "path": str(target), "commit": head, "files": files},
        )
        return target

    def metrics(self, run_id: str) -> RunMetrics:
        """Derive ``RunMetrics`` from ``runs/<id>/trace.jsonl``; also works for runs of earlier processes."""
        return compute(run_id, self.trace.events(run_id))

    async def _get(self, run_id: str) -> LiveRun:
        """``live`` that raises ``KeyError`` for an unknown run (what the mutating calls promise)."""
        live = await self.live(run_id)
        if live is None:
            raise KeyError(run_id)
        return live

    async def _persist_artifacts(self, live: LiveRun) -> None:
        """Write every artifact in context as ``artifacts/<name>.v<n>.json`` plus ``context.json``.

        Idempotent: existing versions are rewritten with identical content; older versions are never
        removed. ``context.json`` (feedback, answers, approval tokens, producers, mode flags) is what
        ``load`` needs beyond the artifacts to resume the run in another process.
        """
        ctx = live.ctx
        for a in ctx.artifacts.values():
            await self.store.save_artifact(ctx.run_id, a.name, a.version, a.value)
        await self.store.save_context(
            ctx.run_id,
            {
                "scenario": ctx.scenario,
                "replay": ctx.replay,
                "record": live.record,
                "target_stack": ctx.target_stack,
                "producers": {a.name: a.produced_by for a in ctx.artifacts.values()},
                "feedback": ctx.feedback,
                "answers": ctx.answers,
                "approvals": sorted(ctx.approvals),
            },
        )
