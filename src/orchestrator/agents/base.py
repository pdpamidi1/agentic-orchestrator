"""An agent = a prompt template + an output schema + the artifacts it reads. Nothing else."""

from __future__ import annotations

from pathlib import Path
from typing import Any, ClassVar

from pydantic import BaseModel

from ..engine.context import RunContext
from ..engine.graph import NodeDef
from ..engine.outcomes import Outcome, Retry, Success
from ..llm.client import LLMClient
from ..models.trace import Kind

PROMPTS = Path(__file__).resolve().parent.parent / "prompts"


class Agent[T: BaseModel]:
    name: ClassVar[str]
    output: type[T]
    reads: ClassVar[tuple[str, ...]] = ()
    produces: ClassVar[str]
    system: ClassVar[str] = (
        "You are a senior software engineer inside a governed SDLC pipeline. Return ONLY the requested "
        "structured object. Do not invent requirements; unclear points are ambiguities, not assumptions."
    )

    def __init__(self, llm: LLMClient) -> None:
        self.llm = llm

    def render(self, ctx: RunContext) -> str:
        template = (PROMPTS / f"{self.name}.md").read_text(encoding="utf-8")
        parts = {k: _dump(ctx.get(k)) for k in self.reads}
        parts["feedback"] = _dump(ctx.feedback.get(self.name))
        parts["answers"] = _dump(ctx.answers)
        parts["run_id"] = ctx.run_id
        parts["target_stack"] = ctx.target_stack
        return template.format(**parts)

    async def __call__(self, node: NodeDef, ctx: RunContext) -> Outcome:
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
        return out


def _dump(v: Any) -> str:
    if v is None:
        return "(none)"
    if isinstance(v, BaseModel):
        return v.model_dump_json(indent=2)
    return str(v)
