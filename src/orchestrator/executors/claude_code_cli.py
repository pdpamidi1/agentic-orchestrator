"""
Primary executor: Claude Code in headless mode inside the sandbox. Tool permissions come from
policy.claude_code; the prompt is spec-driven (TaskSpec + contract slice + class structure + last
attempt's feedback), never the raw requirement.
"""

from __future__ import annotations

import asyncio
import json
import os
from pathlib import Path
from typing import Any

from ..engine.context import RunContext
from ..engine.conventions import gate_contract
from ..models import Design, TaskSpec
from ..models.policy import Policy
from ..models.trace import Kind
from ..sandbox.git import GitSandbox
from .base import BlockedTask, Done, Errored, ExecResult

PROMPT = """# Task {id} — {title}
Implement exactly this task inside the current repository. Stack: {stack}.

## Allowed files (touch nothing else; if you need another file, stop and report BLOCKED)
{allowed}

## API contract slice (operationIds from openapi.yaml)
{contract}

## Data model slice (tables you may read/write; NO migrations unless highImpactApproved)
{data}

## Class structure (implement these names/packages exactly)
{classes}

## Acceptance criteria to satisfy (each needs a test)
{criteria}

## Definition of done
{dod}

## Build contract — these gates run on the whole repository after all tasks; make them work from task 1
{gates}

## Turn budget
You have at most {turns} tool turns in this attempt. Plan the edits first, write whole files, run the
build once at the end. If the attempt runs out of turns, the next attempt continues on the current tree
(nothing is reset) with this feedback, so leave the tree in a coherent state as you go.

## Feedback from the previous attempt — fix these first
{feedback}

Finish by printing ONE line: {{"status":"DONE|BLOCKED","filesChanged":[...],"testsAdded":[...],"notes":"..."}}
"""


class ClaudeCodeCliExecutor:
    def __init__(self, binary: str = "claude") -> None:
        self.binary = binary

    def build_prompt(
        self,
        task: TaskSpec,
        design: Design,
        feedback: dict[str, Any] | None,
        stack: str,
        policy: Policy | None = None,
    ) -> str:
        ops = [o for o in design.api.operations if o.operation_id in task.contract_slice]
        return PROMPT.format(
            id=task.id,
            title=task.title,
            stack=stack,
            allowed="\n".join(f"- {f}" for f in task.allowed_files),
            contract="\n".join(
                f"- {o.method} {o.path} ({o.operation_id}) -> "
                + ", ".join(f"{r.status} {r.description}" for r in o.responses)
                for o in ops
            )
            or "(none)",
            data="\n".join(f"- {t}" for t in task.data_model_slice) or "(none)",
            classes="\n".join(f"- {c}" for c in task.class_structure) or "(none)",
            criteria="\n".join(f"- {c}" for c in task.acceptance_criteria_ids) or "(none)",
            dod="\n".join(f"- {d}" for d in task.definition_of_done),
            gates="\n".join(gate_contract(policy, stack)) if policy else "(none)",
            turns=policy.claude_code.max_turns if policy else "n/a",
            feedback=json.dumps(feedback, indent=2) if feedback else "(none)",
        )

    async def execute(
        self, ctx: RunContext, task: TaskSpec, design: Design, feedback: dict[str, Any] | None
    ) -> ExecResult:
        cc = ctx.policy.claude_code
        prompt = self.build_prompt(task, design, feedback, ctx.target_stack, ctx.policy)
        system_prompt = await asyncio.to_thread(Path(cc.system_prompt_file).read_text, encoding="utf-8")
        cmd = [
            self.binary,
            "-p",
            prompt,
            "--output-format",
            "json",
            "--max-turns",
            str(cc.max_turns),
            "--permission-mode",
            cc.permission_mode,
            "--allowedTools",
            ",".join(cc.allowed_tools),
            "--disallowedTools",
            ",".join(cc.disallowed_tools),
            "--append-system-prompt",
            system_prompt,
        ]
        env = {k: v for k, v in os.environ.items() if k in ("PATH", "HOME", "ANTHROPIC_API_KEY", "JAVA_HOME")}
        git = GitSandbox(ctx.sandbox)
        base = await git.head()
        try:
            proc = await asyncio.create_subprocess_exec(
                *cmd,
                cwd=str(ctx.sandbox),
                env=env,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )
            try:
                out, err = await asyncio.wait_for(
                    proc.communicate(), timeout=ctx.policy.budgets.node_timeout_seconds
                )
            except (TimeoutError, asyncio.CancelledError):
                proc.kill()  # no orphaned agent keeps editing the sandbox after we stop waiting
                await proc.wait()
                raise
        except TimeoutError:
            return Errored("claude code timeout", transient=True)
        except FileNotFoundError:
            return Errored("claude binary not found; use --replay or install Claude Code", transient=False)
        attempt = (feedback or {}).get("attempt", 0) + 1
        raw_out, raw_err = out.decode(errors="replace"), err.decode(errors="replace")
        # keep every attempt's raw result for post-mortems: runs/<id>/executor/<task>.attempt<n>.json
        await asyncio.to_thread(
            _write_json,
            ctx.sandbox.parent / "executor" / f"{task.id}.attempt{attempt}.json",
            {
                "task": task.id,
                "attempt": attempt,
                "exit_code": proc.returncode,
                "stdout": raw_out,
                "stderr": raw_err,
            },
        )
        if proc.returncode != 0:
            # Claude Code reports its failure (e.g. subtype error_max_turns) in the JSON on stdout, not stderr
            try:
                failed = json.loads(raw_out or "{}")
            except json.JSONDecodeError:
                failed = {}
            detail = failed.get("subtype") or failed.get("result") or raw_out[-300:] or raw_err[-300:]
            ctx.emit(
                Kind.EXECUTOR_CALL,
                task_id=task.id,
                attempt=attempt,
                actor="claude-code",
                status="ERROR",
                cost_usd=float(failed.get("total_cost_usd", 0.0) or 0.0),
                payload={
                    "exit_code": proc.returncode,
                    "subtype": failed.get("subtype"),
                    "turns": failed.get("num_turns"),
                },
            )
            return Errored(f"claude exit {proc.returncode}: {str(detail)[:300]}")

        result = json.loads(raw_out or "{}")
        usage = result.get("usage", {})
        cost = float(result.get("total_cost_usd", 0.0))
        ctx.emit(
            Kind.EXECUTOR_CALL,
            task_id=task.id,
            attempt=attempt,
            actor="claude-code",
            status="OK",
            tokens_in=usage.get("input_tokens", 0),
            tokens_out=usage.get("output_tokens", 0),
            cost_usd=cost,
            payload={"turns": result.get("num_turns")},
        )

        # the agent's own status line, if present
        text = result.get("result", "")
        status_line = next(
            (line for line in reversed(text.splitlines()) if line.strip().startswith("{")), "{}"
        )
        try:
            reported = json.loads(status_line)
        except json.JSONDecodeError:
            reported = {}
        if reported.get("status") == "BLOCKED":
            await git.reset_working_tree()
            return BlockedTask(reported.get("notes", "blocked by agent"))

        changed = await git.changed_files(base)
        sha = await git.commit_task(task.id, task.title, ctx.run_id)
        return Done(
            files_changed=changed,
            tests_added=reported.get("testsAdded", []),
            commit_sha=sha,
            tokens_in=usage.get("input_tokens", 0),
            tokens_out=usage.get("output_tokens", 0),
            cost_usd=cost,
            notes=reported.get("notes", ""),
        )


def _write_json(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2), encoding="utf-8")
