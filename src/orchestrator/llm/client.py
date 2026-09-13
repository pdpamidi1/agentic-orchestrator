"""
Structured-output LLM client. Every agent call goes through `structured()` and returns a validated
Pydantic model.
- AnthropicClient: forces a tool call whose input schema is the Pydantic model's JSON schema (no free text)
- ReplayClient: serves cached responses from runs/cache/<sha>.json so demos run with no API key
- RecordingClient: AnthropicClient that also writes the cache (record once, replay forever)
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from pathlib import Path
from typing import TYPE_CHECKING, Protocol, TypeVar

from pydantic import BaseModel, ValidationError

if TYPE_CHECKING:
    from anthropic.types import MessageParam, ToolChoiceToolParam, ToolParam

T = TypeVar("T", bound=BaseModel)


@dataclass(frozen=True)
class Usage:
    tokens_in: int = 0
    tokens_out: int = 0
    cost_usd: float = 0.0


class LLMClient(Protocol):
    async def structured(
        self, system: str, prompt: str, schema: type[T], *, max_repairs: int = 2
    ) -> tuple[T, Usage]: ...


def _key(system: str, prompt: str, schema: type[BaseModel]) -> str:
    return hashlib.sha256(f"{schema.__name__}\n{system}\n{prompt}".encode()).hexdigest()[:24]


class ReplayClient:
    def __init__(self, cache_dir: Path) -> None:
        self.cache_dir = cache_dir

    async def structured(
        self, system: str, prompt: str, schema: type[T], *, max_repairs: int = 2
    ) -> tuple[T, Usage]:
        p = self.cache_dir / f"{_key(system, prompt, schema)}.json"
        if not p.exists():
            raise FileNotFoundError(
                f"no cached response for {schema.__name__}; run live once with --record ({p.name})"
            )
        data = json.loads(p.read_text(encoding="utf-8"))
        return schema.model_validate(data["output"]), Usage(**data.get("usage", {}))


class AnthropicClient:
    # pricing per 1M tokens; adjust to the model you pin in .env
    PRICES: dict[str, tuple[float, float]] = {
        "claude-sonnet-4-5": (3.0, 15.0),
        "claude-opus-4-1": (15.0, 75.0),
    }

    def __init__(self, model: str, api_key: str | None = None) -> None:
        import anthropic

        self.model = model
        self.client = anthropic.AsyncAnthropic(api_key=api_key)

    async def structured(
        self, system: str, prompt: str, schema: type[T], *, max_repairs: int = 2
    ) -> tuple[T, Usage]:
        tool: ToolParam = {
            "name": f"emit_{schema.__name__}",
            "description": f"Return a {schema.__name__}",
            "input_schema": schema.model_json_schema(),
        }
        tool_choice: ToolChoiceToolParam = {"type": "tool", "name": tool["name"]}
        messages: list[MessageParam] = [{"role": "user", "content": prompt}]
        usage = Usage()
        for _ in range(max_repairs + 1):
            resp = await self.client.messages.create(
                model=self.model,
                max_tokens=8000,
                system=system,
                messages=messages,
                tools=[tool],
                tool_choice=tool_choice,
            )
            pin, pout = self.PRICES.get(self.model, (3.0, 15.0))
            usage = Usage(
                usage.tokens_in + resp.usage.input_tokens,
                usage.tokens_out + resp.usage.output_tokens,
                round(
                    usage.cost_usd
                    + resp.usage.input_tokens * pin / 1e6
                    + resp.usage.output_tokens * pout / 1e6,
                    5,
                ),
            )
            block = next(b for b in resp.content if b.type == "tool_use")
            try:
                return schema.model_validate(block.input), usage
            except ValidationError as e:  # schema repair loop: feed the errors back, once or twice
                messages += [
                    {"role": "assistant", "content": resp.content},
                    {
                        "role": "user",
                        "content": [
                            {
                                "type": "tool_result",
                                "tool_use_id": block.id,
                                "content": f"Validation failed, fix and re-emit:\n{e}",
                            }
                        ],
                    },
                ]
        raise ValueError(f"{schema.__name__}: schema invalid after {max_repairs} repairs")


class RecordingClient:
    def __init__(self, inner: LLMClient, cache_dir: Path) -> None:
        self.inner, self.cache_dir = inner, cache_dir
        cache_dir.mkdir(parents=True, exist_ok=True)

    async def structured(
        self, system: str, prompt: str, schema: type[T], *, max_repairs: int = 2
    ) -> tuple[T, Usage]:
        out, usage = await self.inner.structured(system, prompt, schema, max_repairs=max_repairs)
        p = self.cache_dir / f"{_key(system, prompt, schema)}.json"
        p.write_text(
            json.dumps(
                {"schema": schema.__name__, "output": out.model_dump(mode="json"), "usage": usage.__dict__},
                indent=2,
            ),
            encoding="utf-8",
        )
        return out, usage
