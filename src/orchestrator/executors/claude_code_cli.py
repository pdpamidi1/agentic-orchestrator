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
from ..models import Design, TaskSpec
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

## Feedback from the previous attempt — fix these first
{feedback}

Finish by printing ONE line: {{"status":"DONE|BLOCKED","filesChanged":[...],"testsAdded":[...],"notes":"..."}}
"""


class ClaudeCodeCliExecutor:
    def __init__(self, binary: str = "claude") -> None:
        self.binary = binary

    def build_prompt(
        self, task: TaskSpec, design: Design, feedback: dict[str, Any] | None, stack: str
    ) -> str:
        ops = [o for o in design.api.operations if o.operation_id in task.contract_slice]
        return PROMPT.format(
            id=task.id,
            title=task.title,
            stack=stack,
            allowed="\n".join(f"- {f}" for f in task.allowed_files),
            contract="\n".join(f"- {o.method} {o.path} ({o.operation_id}) -> {o.responses}" for o in ops)
            or "(none)",
            data="\n".join(f"- {t}" for t in task.data_model_slice) or "(none)",
            classes="\n".join(f"- {c}" for c in task.class_structure) or "(none)",
            criteria="\n".join(f"- {c}" for c in task.acceptance_criteria_ids) or "(none)",
            dod="\n".join(f"- {d}" for d in task.definition_of_done),
            feedback=json.dumps(feedback, indent=2) if feedback else "(none)",
        )

    async def execute(
        self, ctx: RunContext, task: TaskSpec, design: Design, feedback: dict[str, Any] | None
    ) -> ExecResult:
        cc = ctx.policy.claude_code
        prompt = self.build_prompt(task, design, feedback, ctx.target_stack)
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
        base = await git.base_ref()
        try:
            proc = await asyncio.create_subprocess_exec(
                *cmd,
                cwd=str(ctx.sandbox),
                env=env,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )
            out, err = await asyncio.wait_for(
                proc.communicate(), timeout=ctx.policy.budgets.node_timeout_seconds
            )
        except TimeoutError:
            return Errored("claude code timeout", transient=True)
        except FileNotFoundError:
            return Errored("claude binary not found; use --replay or install Claude Code", transient=False)
        if proc.returncode != 0:
            return Errored(f"claude exit {proc.returncode}: {err.decode(errors='replace')[-500:]}")

        result = json.loads(out.decode(errors="replace") or "{}")
        usage = result.get("usage", {})
        cost = float(result.get("total_cost_usd", 0.0))
        ctx.emit(
            Kind.EXECUTOR_CALL,
            task_id=task.id,
            actor="claude-code",
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
