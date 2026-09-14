"""
Structured-output LLM client. Every agent call goes through `structured()` and returns a validated
Pydantic model.
- AnthropicClient: structured outputs (the API constrains the reply to the schema) + a repair loop
- ReplayClient: serves cached responses from runs/cache/<sha>.json so demos run with no API key
- RecordingClient: AnthropicClient that also writes the cache (record once, replay forever)
"""

from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import TYPE_CHECKING, Protocol, TypeVar

from pydantic import BaseModel

if TYPE_CHECKING:
    from anthropic.types import MessageParam

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


# Prompts embed volatile tokens that differ between a record run and its replay: run ids
# (<scenario>-<8 hex>), commit shas, gate timings ("4 passed in 0.67s", took_seconds). None of them carry
# meaning for the cache, so they are normalised out; template text and artifact content still
# distinguish keys.
RUN_ID_RE = re.compile(r"\b[a-z_]+-[0-9a-f]{8}\b")
HEX_RE = re.compile(r"\b[0-9a-f]{7,40}\b")
NUMBER_RE = re.compile(r"\d+(?:\.\d+)?")


def _normalise(prompt: str) -> str:
    return NUMBER_RE.sub("<n>", HEX_RE.sub("<hex>", RUN_ID_RE.sub("<run_id>", prompt)))


def _key(system: str, prompt: str, schema: type[BaseModel]) -> str:
    return hashlib.sha256(f"{schema.__name__}\n{system}\n{_normalise(prompt)}".encode()).hexdigest()[:24]


class ReplayClient:
    def __init__(self, cache_dir: Path) -> None:
        self.cache_dir = cache_dir

    def _lookup(self, key: str, schema_name: str) -> Path:
        p = self.cache_dir / f"{key}.json"
        if p.exists():
            return p
        # residual prompt drift (e.g. tool output wording): fall back to the recorded response for this
        # schema when there is exactly one, so a golden replay is not derailed by cosmetic differences
        same = [
            f
            for f in sorted(self.cache_dir.glob("*.json"))
            if json.loads(f.read_text(encoding="utf-8")).get("schema") == schema_name
        ]
        if len(same) == 1:
            return same[0]
        raise FileNotFoundError(
            f"no cached response for {schema_name}; run live once with --record ({p.name}, "
            f"{len(same)} candidates by schema)"
        )

    async def structured[T: BaseModel](
        self, system: str, prompt: str, schema: type[T], *, max_repairs: int = 2
    ) -> tuple[T, Usage]:
        p = self._lookup(_key(system, prompt, schema), schema.__name__)
        data = json.loads(p.read_text(encoding="utf-8"))
        return schema.model_validate(data["output"]), Usage(**data.get("usage", {}))


class AnthropicClient:
    """Structured outputs: `messages.parse(output_format=schema)` makes the API constrain the reply to the
    schema's JSON and the SDK validate it into the Pydantic model. Model-agnostic (no forced tool use, which
    Claude Fable 5.1 rejects) and compatible with adaptive thinking, which is on by default on Claude Opus 5.
    Model-level validators (e.g. Plan acyclicity) can still fail -> errors are fed back, up to max_repairs.
    """

    # USD per 1M tokens (input, output); Anthropic first-party rates, cached 2026-06
    PRICES: dict[str, tuple[float, float]] = {
        "claude-opus-5": (5.0, 25.0),
        "claude-opus-4-8": (5.0, 25.0),
        "claude-sonnet-5": (2.0, 10.0),
        "claude-sonnet-4-6": (3.0, 15.0),
        "claude-haiku-4-5": (1.0, 5.0),
        "claude-fable-5-1": (10.0, 50.0),
        "claude-fable-5": (10.0, 50.0),
    }
    MAX_TOKENS = 16000

    def __init__(self, model: str, api_key: str | None = None, workspace_id: str | None = None) -> None:
        import anthropic

        self.model = model
        self.workspace_id = workspace_id
        # keys not scoped to a workspace must name one on every request (anthropic-workspace-id)
        headers = {"anthropic-workspace-id": workspace_id} if workspace_id else None
        self.client = anthropic.AsyncAnthropic(api_key=api_key, default_headers=headers)

    def _cost(self, tokens_in: int, tokens_out: int) -> float:
        pin, pout = self.PRICES.get(self.model, (5.0, 25.0))
        return tokens_in * pin / 1e6 + tokens_out * pout / 1e6

    async def structured[T: BaseModel](
        self, system: str, prompt: str, schema: type[T], *, max_repairs: int = 2
    ) -> tuple[T, Usage]:
        messages: list[MessageParam] = [{"role": "user", "content": prompt}]
        usage = Usage()
        last_error = ""
        for _ in range(max_repairs + 1):
            try:
                resp = await self.client.messages.parse(
                    model=self.model,
                    max_tokens=self.MAX_TOKENS,
                    system=system,
                    messages=messages,
                    output_format=schema,
                )
            except ValueError as e:  # pydantic ValidationError / bad JSON: repair with the errors as feedback
                last_error = str(e)
                messages.append(
                    {
                        "role": "user",
                        "content": f"Your previous {schema.__name__} failed validation:\n{last_error}\n"
                        "Re-emit the complete, corrected object.",
                    }
                )
                continue
            usage = Usage(
                usage.tokens_in + resp.usage.input_tokens,
                usage.tokens_out + resp.usage.output_tokens,
                round(usage.cost_usd + self._cost(resp.usage.input_tokens, resp.usage.output_tokens), 5),
            )
            if resp.stop_reason == "refusal":
                details = getattr(resp, "stop_details", None)
                raise ValueError(f"{schema.__name__}: refused ({getattr(details, 'category', None)})")
            if resp.parsed_output is None:
                last_error = f"no structured output (stop_reason={resp.stop_reason})"
                messages.append(
                    {"role": "user", "content": f"{last_error}. Emit the {schema.__name__} object."}
                )
                continue
            return resp.parsed_output, usage
        raise ValueError(f"{schema.__name__}: schema invalid after {max_repairs} repairs: {last_error[:500]}")


class RecordingClient:
    def __init__(self, inner: LLMClient, cache_dir: Path) -> None:
        self.inner, self.cache_dir = inner, cache_dir
        cache_dir.mkdir(parents=True, exist_ok=True)

    async def structured(
        self, system: str, prompt: str, schema: type[T], *, max_repairs: int = 2
    ) -> tuple[T, Usage]:
        p = self.cache_dir / f"{_key(system, prompt, schema)}.json"
        if p.exists():  # record once: an exact hit that still validates is reused, a stale one is re-recorded
            try:
                data = json.loads(p.read_text(encoding="utf-8"))
                return schema.model_validate(data["output"]), Usage(**data.get("usage", {}))
            except (ValueError, KeyError):
                pass
        out, usage = await self.inner.structured(system, prompt, schema, max_repairs=max_repairs)
        p.write_text(
            json.dumps(
                {"schema": schema.__name__, "output": out.model_dump(mode="json"), "usage": usage.__dict__},
                indent=2,
            ),
            encoding="utf-8",
        )
        return out, usage
