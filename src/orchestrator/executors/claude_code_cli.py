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
from ..engine.gates import COMMITTED_CANDIDATES, SPRINGDOC_DUMPS
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

## Feedback from the previous attempt — fix these first
{feedback}

Finish by printing ONE line: {{"status":"DONE|BLOCKED","filesChanged":[...],"testsAdded":[...],"notes":"..."}}
"""


_INTERNAL_GATE_TEXT = {
    "secret_and_pattern_scan": "no secrets, banned code patterns or forbidden PII fields in what you write",
    "openapi_diff": (
        "the committed OpenAPI document ({committed}) must match what the running application exposes, "
        "operation by operation and status code by status code"
    ),
    "release_checklist": (
        "Dockerfile, a .github/workflows/ workflow, README.md and the committed OpenAPI document must exist"
    ),
}
_STACK_NOTES = {
    "java": (
        "- java specifics: commit the Maven wrapper (mvnw + .mvn/wrapper/maven-wrapper.properties); define "
        "the `it` profile so `-Pit verify` runs the integration tests AND writes the springdoc OpenAPI dump "
        "to {dumps} (springdoc-openapi-maven-plugin); the orchestrator provides "
        "src/test/java/sdlc/ArchitectureTest.java, keep it compiling"
    ),
    "python": (
        "- python specifics: src/ layout, tests/unit and tests/integration, a FastAPI app importable from "
        "src/; the orchestrator provides tests/test_architecture.py, keep it passing"
    ),
}


def gate_contract(policy: Policy, stack: str) -> list[str]:
    """Human-readable build contract for the executor: the gate commands policy.yaml runs for this stack."""
    lines: list[str] = []
    for gid in policy.gates:
        g = policy.gate(gid, stack)
        level = "required" if g.required else "advisory"
        if g.cmd.startswith("internal:"):
            text = _INTERNAL_GATE_TEXT.get(g.cmd.split(":", 1)[1])
            if text:
                lines.append(
                    f"- {gid} ({level}): " + text.format(committed=" or ".join(COMMITTED_CANDIDATES))
                )
        else:
            lines.append(f"- {gid} ({level}): `{g.cmd}`")
    if stack in _STACK_NOTES:
        lines.append(_STACK_NOTES[stack].format(dumps=" or ".join(SPRINGDOC_DUMPS)))
    return lines


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
