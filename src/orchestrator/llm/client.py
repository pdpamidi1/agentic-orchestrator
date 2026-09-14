"""
Structured-output LLM client. Every agent call goes through `structured()` and returns a validated
Pydantic model.
- AnthropicClient: structured outputs (the API constrains the reply to the schema) + a repair loop
- ReplayClient: serves cached responses from runs/cache/<sha>.json so demos run with no API key
- RecordingClient: AnthropicClient that also writes the cache (record once, replay forever)

Where it sits: `agents/base.py#Agent.__call__` is the only caller; `service.py#_mode` picks the backing
(`--replay` -> ReplayClient, `SDLC_LLM=fake` -> `llm/fake.FakeClient`, else AnthropicClient, optionally
wrapped in RecordingClient under `--record`). The `LLMClient` protocol is the single call shape all four
share: `structured(system, prompt, schema, max_repairs=2) -> (model, Usage)`.

Invariants:
- No call without a schema: the return value is always an instance of the requested Pydantic model.
- Failures surface as `ValueError` (refusal, schema still invalid after repairs) or `FileNotFoundError`
  (no cached response); `Agent.__call__` turns both into a `Retry` outcome, never a crash.
- Cache keys hash `schema name + system + normalised prompt`; run ids, hex shas and numbers are replaced
  by placeholders (`RUN_ID_RE`, `HEX_RE`, `NUMBER_RE`) so a record run and its replay hit the same key.
- The cache is record-once: an exact hit that still validates against the schema is reused, a stale one
  is overwritten.

Files: `runs/cache/llm/<key>.json` (the caller passes the directory) with `{"schema", "output", "usage"}`.
No trace events are emitted here; `Agent.__call__` emits `LLM_CALL` with the returned `Usage`.
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
    """Token and cost accounting for one `structured()` call, summed over its repair rounds.

    Zero for replay and fake clients (no network). `cost_usd` is estimated from `AnthropicClient.PRICES`,
    rounded to 5 decimals; it feeds the `LLM_CALL` trace event and the metrics derived from it.
    """

    tokens_in: int = 0
    tokens_out: int = 0
    cost_usd: float = 0.0


class LLMClient(Protocol):
    """One call shape for every backing: a system prompt, a user prompt, a Pydantic schema -> validated model.

    `max_repairs` is the number of extra rounds a live client may spend feeding validation errors back;
    replay/fake clients accept and ignore it.
    """

    async def structured(
        self, system: str, prompt: str, schema: type[T], *, max_repairs: int = 2
    ) -> tuple[T, Usage]: ...


# Prompts embed volatile tokens that differ between a record run and its replay: run ids
# (<scenario>-<8 hex>), commit shas, gate timings ("4 passed in 0.67s", took_seconds). None of them carry
# meaning for the cache, so they are normalised out; template text and artifact content still
# distinguish keys.
RUN_ID_RE = re.compile(r"\b[a-z_]+-[0-9a-f]{8}\b")  # e.g. greenfield-3fa9c1e2
HEX_RE = re.compile(r"\b[0-9a-f]{7,40}\b")  # abbreviated to full git shas
NUMBER_RE = re.compile(r"\d+(?:\.\d+)?")  # integers and decimals: versions, counts, durations


def _normalise(prompt: str) -> str:
    """Replace run ids, hex shas and numbers with `<run_id>`, `<hex>`, `<n>` placeholders, in that order.

    Order matters: run ids are matched before their hex tail would be eaten by `HEX_RE`, and hex before
    `NUMBER_RE` would split a sha into digit runs.
    """
    return NUMBER_RE.sub("<n>", HEX_RE.sub("<hex>", RUN_ID_RE.sub("<run_id>", prompt)))


def _key(system: str, prompt: str, schema: type[BaseModel]) -> str:
    """Cache key: first 24 hex chars of sha256 over `schema name \\n system \\n normalised prompt`.

    Including the schema name keeps two agents with identical prompts but different outputs apart.
    """
    return hashlib.sha256(f"{schema.__name__}\n{system}\n{_normalise(prompt)}".encode()).hexdigest()[:24]


class ReplayClient:
    """Serves recorded responses from a cache directory; never touches the network.

    Lookup is by cache key, with a fallback to the single recorded response for the same schema when the
    key misses (residual prompt drift such as tool output wording). A miss with zero or several
    candidates raises `FileNotFoundError`, which the agent reports as a `Retry`.
    """

    def __init__(self, cache_dir: Path) -> None:
        """`cache_dir` is the LLM cache folder (`runs/cache/llm`); it is not created here."""
        self.cache_dir = cache_dir

    def _lookup(self, key: str, schema_name: str) -> Path:
        """Return the cache file for `key`, or the unique file recorded for `schema_name`, or raise.

        The fallback scans every `*.json` in the cache and parses it to read its `schema` field, so it is
        O(cache size) but only runs on a miss. Raises `FileNotFoundError` with the expected file name and
        the number of same-schema candidates, so the message tells the operator what to record.
        """
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
        """Load the cached `output` for this call and validate it into `schema`.

        `max_repairs` is ignored. The recorded `usage` (if any) is returned as-is so replayed metrics show
        what the original call cost. Raises `FileNotFoundError` on a miss and pydantic `ValidationError`
        (a `ValueError`) if the recording no longer matches the schema.
        """
        p = self._lookup(_key(system, prompt, schema), schema.__name__)
        data = json.loads(p.read_text(encoding="utf-8"))
        return schema.model_validate(data["output"]), Usage(**data.get("usage", {}))


class AnthropicClient:
    """Structured outputs: `messages.stream(output_format=schema)` makes the API constrain the reply to the
    schema's JSON and the SDK validate it into the Pydantic model. Model-agnostic (no forced tool use, which
    Claude Fable 5.1 rejects) and compatible with adaptive thinking, which is on by default on Claude Opus 5.
    Model-level validators (e.g. Plan acyclicity) can still fail -> errors are fed back, up to max_repairs.
    """

    # USD per 1M tokens (input, output); Anthropic first-party rates, cached 2026-06
    # Used only to estimate `Usage.cost_usd`; an unknown model falls back to the (5.0, 25.0) Opus rate.
    PRICES: dict[str, tuple[float, float]] = {
        "claude-opus-5": (5.0, 25.0),
        "claude-opus-4-8": (5.0, 25.0),
        "claude-sonnet-5": (2.0, 10.0),
        "claude-sonnet-4-6": (3.0, 15.0),
        "claude-haiku-4-5": (1.0, 5.0),
        "claude-fable-5-1": (10.0, 50.0),
        "claude-fable-5": (10.0, 50.0),
    }
    MAX_TOKENS = 16000  # first attempt; a truncated reply doubles it up to MAX_TOKENS_CAP
    MAX_TOKENS_CAP = 48000  # hard ceiling: beyond this a truncated reply is treated as a repair failure

    def __init__(self, model: str, api_key: str | None = None, workspace_id: str | None = None) -> None:
        """Create the async Anthropic SDK client.

        `anthropic` is imported lazily so replay/fake modes never need the package at import time. `api_key`
        None lets the SDK read `ANTHROPIC_API_KEY`; `workspace_id`, when given, is sent as the
        `anthropic-workspace-id` header on every request.
        """
        import anthropic

        self.model = model
        self.workspace_id = workspace_id
        # keys not scoped to a workspace must name one on every request (anthropic-workspace-id)
        headers = {"anthropic-workspace-id": workspace_id} if workspace_id else None
        self.client = anthropic.AsyncAnthropic(api_key=api_key, default_headers=headers)

    def _cost(self, tokens_in: int, tokens_out: int) -> float:
        """USD for one response at this model's `PRICES` rate (default Opus rate for unknown models)."""
        pin, pout = self.PRICES.get(self.model, (5.0, 25.0))
        return tokens_in * pin / 1e6 + tokens_out * pout / 1e6

    async def structured[T: BaseModel](
        self, system: str, prompt: str, schema: type[T], *, max_repairs: int = 2
    ) -> tuple[T, Usage]:
        """Stream `messages` until the reply validates into `schema`, at most `max_repairs + 1` times.

        Loop behaviour per round:
        - SDK/pydantic `ValueError` (invalid JSON or schema violation): if the error looks like truncation
          (`_truncated`) and `max_tokens` is below `MAX_TOKENS_CAP`, double `max_tokens` and retry the same
          messages (does not consume a repair message); otherwise append the errors as a user turn asking
          for a corrected object.
        - `stop_reason == "refusal"`: raise `ValueError` immediately (not repairable).
        - `parsed_output is None` (e.g. stopped on `max_tokens` without a parse error): append a nudge turn.
        - otherwise return the parsed model.
        `Usage` sums the tokens of every round that produced a response; cost is rounded to 5 decimals.
        Raises `ValueError` after the rounds are exhausted with the last error (truncated to 500 chars).
        """
        messages: list[MessageParam] = [{"role": "user", "content": prompt}]
        usage = Usage()
        last_error = ""
        max_tokens = self.MAX_TOKENS
        for _ in range(max_repairs + 1):
            try:
                # streamed: the SDK refuses non-streaming requests whose max_tokens could exceed its
                # 10-minute window (a doubled budget after a truncation trips it); parsed the same way
                async with self.client.messages.stream(
                    model=self.model,
                    max_tokens=max_tokens,
                    system=system,
                    messages=messages,
                    output_format=schema,
                ) as stream:
                    resp = await stream.get_final_message()
            except ValueError as e:  # pydantic ValidationError / bad JSON: repair with the errors as feedback
                last_error = str(e)
                if _truncated(last_error) and max_tokens < self.MAX_TOKENS_CAP:
                    max_tokens = min(max_tokens * 2, self.MAX_TOKENS_CAP)  # not the model's fault: more room
                    continue
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
                # a refusal is final: re-asking would only spend budget on the same answer
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
    """Decorator that reuses a valid cache hit and otherwise calls `inner` and writes the result to cache.

    Record-once semantics: re-recording after a prompt or schema change only pays for the calls whose key
    or validity changed. Works over any `LLMClient`, including `FakeClient` (the offline golden run).
    """

    def __init__(self, inner: LLMClient, cache_dir: Path) -> None:
        """Wrap `inner`; `cache_dir` (`runs/cache/llm`) is created if missing."""
        self.inner, self.cache_dir = inner, cache_dir
        cache_dir.mkdir(parents=True, exist_ok=True)

    async def structured(
        self, system: str, prompt: str, schema: type[T], *, max_repairs: int = 2
    ) -> tuple[T, Usage]:
        """Return the cached response for this key if it still validates, else record a fresh one.

        A cache file that is unreadable, missing `output`, or no longer valid for `schema` is treated as a
        miss and overwritten. Errors from `inner` propagate unchanged (nothing is written on failure).
        Side effect: writes `<key>.json` with `schema`, `output` (JSON-mode dump) and `usage`.
        """
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


def _truncated(error: str) -> bool:
    """A reply cut off at max_tokens surfaces as unterminated JSON, not as a schema error.

    Matches the SDK's JSON decode message ("EOF while parsing") and pydantic's `json_invalid` error type.
    """
    return "EOF while parsing" in error or "json_invalid" in error
