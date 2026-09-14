"""An agent = a prompt template + an output schema + the artifacts it reads. Nothing else.

Where it sits: `engine/handlers.py#agent_handler` looks an agent up in `agents/catalog.AGENTS` by the
node's `agent` name and awaits it with the `NodeDef` and `RunContext`; the agent returns an engine
`Outcome`. Prompt templates live in `prompts/<name>.md`; their `{placeholders}` are the artifact names
listed in `Agent.reads` plus the fixed set `feedback`, `answers`, `run_id`, `target_stack`, `conventions`.

Invariants:
- Every LLM call is `structured()` with the agent's Pydantic `output` schema; no free-text parsing.
- Agents implement from typed artifacts already in context, never from anything they fetch themselves.
- An LLM failure (`ValueError` from a refusal or exhausted repairs, `FileNotFoundError` from a replay
  miss) is a `Retry` outcome, never an exception out of the agent.
- The artifact stored under `produces` is whatever `post()` returns, so subclasses can stamp run id and
  versions without touching the prompt or the call.

Trace events: one `LLM_CALL` per successful call (actor = agent name, tokens, cost, schema name).
No files are written here; the handler/store persists the produced artifact.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any, ClassVar

from pydantic import BaseModel

from ..engine.context import RunContext
from ..engine.conventions import render_conventions
from ..engine.graph import NodeDef
from ..engine.outcomes import Outcome, Retry, Success
from ..llm.client import LLMClient
from ..models.trace import Kind

# `src/orchestrator/prompts/`: one Markdown template per agent, named `<Agent.name>.md`.
PROMPTS = Path(__file__).resolve().parent.parent / "prompts"


class Agent[T: BaseModel]:
    """Base class for every concrete agent; `T` is the Pydantic schema the LLM must return.

    Subclasses set the class attributes and optionally override `post()`. The instance holds only the
    `LLMClient`, so one instance per agent per run is enough and calls are independent.
    Class attributes:
    - `name`      prompt file stem and the `actor` in trace events;
    - `output`    the schema passed to `structured()`;
    - `reads`     context artifact names rendered into the template (missing ones render as "(none)");
    - `produces`  the context key the result is stored under;
    - `system`    the system prompt shared by all agents (return only the object; unclear points are
                  ambiguities, not assumptions).
    """

    name: ClassVar[str]
    output: type[T]
    reads: ClassVar[tuple[str, ...]] = ()
    produces: ClassVar[str]
    system: ClassVar[str] = (
        "You are a senior software engineer inside a governed SDLC pipeline. Return ONLY the requested "
        "structured object. Do not invent requirements; unclear points are ambiguities, not assumptions."
    )

    def __init__(self, llm: LLMClient) -> None:
        """Bind the LLM backing (anthropic, replay, recording or fake) this agent will call."""
        self.llm = llm

    def render(self, ctx: RunContext) -> str:
        """Fill `prompts/<name>.md` with the artifacts in `reads` and the fixed context placeholders.

        Artifacts are dumped as indented JSON (`_dump`); `feedback` is this agent's entry in
        `ctx.feedback` (the diagnoser's or the runner's structured findings from the last attempt),
        `answers` the human answers to ambiguities, and `conventions` the governance/build facts rendered
        from `policy.yaml` for the target stack. Uses `str.format`, so a template must not contain stray
        braces. Reads the template file on every call (no caching).
        """
        template = (PROMPTS / f"{self.name}.md").read_text(encoding="utf-8")
        parts = {k: _dump(ctx.get(k)) for k in self.reads}
        parts["feedback"] = _dump(ctx.feedback.get(self.name))
        parts["answers"] = _dump(ctx.answers)
        parts["run_id"] = ctx.run_id
        parts["target_stack"] = ctx.target_stack
        parts["conventions"] = render_conventions(ctx.policy, ctx.target_stack)
        return template.format(**parts)

    async def __call__(self, node: NodeDef, ctx: RunContext) -> Outcome:
        """Run the agent once: render, call `structured()`, emit `LLM_CALL`, store via `post()`.

        Returns `Retry("llm: ...")` when the client raises `ValueError` (refusal, invalid after repairs,
        unknown fake schema) or `FileNotFoundError` (no recorded response); the runner then schedules
        another attempt with the reason as feedback. Otherwise returns `Success({produces: post(out)})`
        carrying the token/cost usage so the runner can account for it.
        """
        prompt = self.render(ctx)
        try:
            out, usage = await self.llm.structured(self.system, prompt, self.output)
        except (ValueError, FileNotFoundError) as e:
            return Retry(f"llm: {e}")
        ctx.emit(
            Kind.LLM_CALL,
            node_id=node.id,
            actor=self.name,
            tokens_in=usage.tokens_in,
            tokens_out=usage.tokens_out,
            cost_usd=usage.cost_usd,
            payload={"schema": self.output.__name__},
        )
        return Success(
            {self.produces: self.post(out, ctx)}, usage.tokens_in, usage.tokens_out, usage.cost_usd
        )

    def post(self, out: T, ctx: RunContext) -> Any:
        """Hook to shape the validated output before it is stored under `produces`.

        Default: return it unchanged. Subclasses use it to stamp `run_id`/versions or to resolve answered
        ambiguities; they must return a new object (artifacts are frozen), never mutate `out`.
        """
        return out


def _dump(v: Any) -> str:
    """Render a context value for a prompt: "(none)" for None, indented JSON for Pydantic models, else str."""
    if v is None:
        return "(none)"
    if isinstance(v, BaseModel):
        return v.model_dump_json(indent=2)
    return str(v)
