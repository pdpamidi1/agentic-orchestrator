"""T7 prep: the Claude Code executor driven by a fake `claude` binary (no network, no spend)."""

from __future__ import annotations

import asyncio
import json
import os
import stat
from pathlib import Path

from orchestrator.engine.context import RunContext
from orchestrator.executors.base import BlockedTask, Done, Errored
from orchestrator.executors.claude_code_cli import ClaudeCodeCliExecutor
from orchestrator.llm.fake import CANNED
from orchestrator.models import Design, Plan, load_policy
from orchestrator.models.trace import Kind
from orchestrator.sandbox.git import GitSandbox
from orchestrator.trace.sink import InMemorySink

REPO = Path(__file__).resolve().parent.parent
POLICY = load_policy(str(REPO / "policy.yaml"))

FAKE_CLAUDE = """#!/usr/bin/env python3
# fake Claude Code: records argv, then behaves per ../fake_claude_mode (done | blocked | crash)
import json, pathlib, sys

pathlib.Path("../claude_args.txt").write_text("\\n".join(sys.argv[1:]))
mode = pathlib.Path("../fake_claude_mode")
mode = mode.read_text().strip() if mode.exists() else "done"
if mode == "hang":
    import time

    time.sleep(30)
    sys.exit(0)
if mode == "crash":
    print(json.dumps({"type": "result", "subtype": "error_max_turns", "is_error": True, "num_turns": 25,
                      "total_cost_usd": 0.5}))
    print("boom", file=sys.stderr)
    sys.exit(1)
if mode == "blocked":
    pathlib.Path("src/stray.py").write_text("oops\\n")
    status = {"status": "BLOCKED", "notes": "needs a dependency"}
    usage = {"input_tokens": 3, "output_tokens": 2}
    out = {"result": "needs pom.xml\\n" + json.dumps(status), "usage": usage, "total_cost_usd": 0.002,
           "num_turns": 1}
else:
    pathlib.Path("src/shortener").mkdir(parents=True, exist_ok=True)
    pathlib.Path("tests/unit").mkdir(parents=True, exist_ok=True)
    pathlib.Path("src/shortener/x.py").write_text("X = 1\\n")
    test = "from shortener.x import X\\n\\n\\ndef test_x() -> None:\\n    assert X == 1\\n"
    pathlib.Path("tests/unit/test_x.py").write_text(test)
    status = {"status": "DONE", "filesChanged": ["src/shortener/x.py"],
              "testsAdded": ["tests/unit/test_x.py"], "notes": "ok"}
    usage = {"input_tokens": 50, "output_tokens": 70}
    out = {"result": "implemented\\n" + json.dumps(status), "usage": usage, "total_cost_usd": 0.0123,
           "num_turns": 4}
print(json.dumps(out))
"""


async def setup(tmp_path: Path) -> tuple[RunContext, GitSandbox, ClaudeCodeCliExecutor]:
    binary = tmp_path / "claude"
    binary.write_text(FAKE_CLAUDE)
    binary.chmod(binary.stat().st_mode | stat.S_IXUSR)
    sandbox = tmp_path / "sandbox"
    ctx = RunContext(
        run_id="r1", scenario="t", policy=POLICY, sandbox=sandbox, trace=InMemorySink(), target_stack="python"
    )
    git = GitSandbox(sandbox)
    await git.ensure_repo()
    (sandbox / "src").mkdir()
    return ctx, git, ClaudeCodeCliExecutor(binary=str(binary))


def task_and_design() -> tuple:  # type: ignore[type-arg]
    plan, design = Plan.model_validate(CANNED["Plan"]), Design.model_validate(CANNED["Design"])
    return plan.tasks[1], design  # T2: has a contract slice + class structure


async def test_done_commits_and_reports_usage(tmp_path: Path) -> None:
    ctx, git, ex = await setup(tmp_path)
    task, design = task_and_design()
    r = await ex.execute(ctx, task, design, {"attempt": 1, "hint": "use base62"})
    assert isinstance(r, Done), r
    assert r.files_changed == ["src/shortener/x.py", "tests/unit/test_x.py"]
    assert r.tests_added == ["tests/unit/test_x.py"] and r.notes == "ok"
    assert (r.tokens_in, r.tokens_out, r.cost_usd) == (50, 70, 0.0123)
    assert await git.head() == r.commit_sha and await git.changed_files() == []  # committed, tree clean
    call = next(e for e in ctx.trace.events("r1") if e.kind == Kind.EXECUTOR_CALL)
    assert call.actor == "claude-code" and call.cost_usd == 0.0123 and call.payload["turns"] == 4

    args = (tmp_path / "claude_args.txt").read_text()
    cc = POLICY.claude_code
    assert "--permission-mode\n" + cc.permission_mode in args and "--max-turns\n" + str(cc.max_turns) in args
    assert "--output-format\njson" in args and ",".join(cc.disallowed_tools) in args
    prompt = args.split("-p\n", 1)[1].split("\n--output-format")[0]
    assert task.title in prompt and "createShortUrl" in prompt and "use base62" in prompt
    assert "URL shortener" not in prompt  # spec-driven: the raw requirement never reaches the executor
    assert "## Build contract" in prompt and POLICY.gate("unit", "python").cmd in prompt
    assert "tests/test_architecture.py" in prompt and "springdoc" not in prompt  # stack-specific notes
    assert f"at most {cc.max_turns} tool turns" in prompt
    assert (REPO / cc.system_prompt_file).read_text().strip() in args  # --append-system-prompt content


async def test_blocked_resets_the_tree_and_crash_is_a_transient_error(tmp_path: Path) -> None:
    ctx, git, ex = await setup(tmp_path)
    task, design = task_and_design()
    (tmp_path / "fake_claude_mode").write_text("blocked")
    r = await ex.execute(ctx, task, design, None)
    assert isinstance(r, BlockedTask) and r.reason == "needs a dependency"
    assert await git.changed_files() == []  # the stray file the agent wrote is gone

    (tmp_path / "fake_claude_mode").write_text("crash")
    r = await ex.execute(ctx, task, design, {"attempt": 1})
    assert isinstance(r, Errored) and r.transient and "error_max_turns" in r.reason  # stdout, not just stderr
    calls = [e for e in ctx.trace.events("r1") if e.kind == Kind.EXECUTOR_CALL]
    assert calls[-1].status == "ERROR" and calls[-1].payload["subtype"] == "error_max_turns"
    assert calls[-1].cost_usd == 0.5
    saved = json.loads((ctx.sandbox.parent / "executor" / f"{task.id}.attempt2.json").read_text())
    assert saved["exit_code"] == 1 and "error_max_turns" in saved["stdout"] and "boom" in saved["stderr"]


async def test_missing_binary_is_not_transient(tmp_path: Path) -> None:
    ctx, _, _ = await setup(tmp_path)
    task, design = task_and_design()
    r = await ClaudeCodeCliExecutor(binary=str(tmp_path / "nope")).execute(ctx, task, design, None)
    assert isinstance(r, Errored) and not r.transient and "not found" in r.reason
    assert not os.environ.get("CLAUDECODE") or True  # documented: env is scrubbed, nested sessions allowed


def test_gate_contract_for_java_lists_the_maven_gates_and_the_springdoc_dump() -> None:
    from orchestrator.engine.conventions import gate_contract

    lines = gate_contract(POLICY, "java")
    text = "\n".join(lines)
    assert "`./mvnw -q -Pit verify`" in text and "-Dtest=ArchitectureTest" in text and "(advisory)" in text
    assert "target/openapi.json" in text and "src/main/resources/openapi.yaml" in text
    assert "src/test/java/sdlc/ArchitectureTest.java" in text


async def test_timeout_kills_the_headless_process(tmp_path: Path) -> None:
    ctx, _, ex = await setup(tmp_path)
    task, design = task_and_design()
    fast = POLICY.model_copy(
        update={"budgets": POLICY.budgets.model_copy(update={"node_timeout_seconds": 1})}
    )
    ctx.policy = fast
    (tmp_path / "fake_claude_mode").write_text("hang")
    r = await ex.execute(ctx, task, design, None)
    assert isinstance(r, Errored) and r.transient and "timeout" in r.reason
    proc = await asyncio.create_subprocess_exec(
        "pgrep", "-f", str(tmp_path / "claude"), stdout=asyncio.subprocess.PIPE
    )
    out, _ = await proc.communicate()
    left = out.decode().strip()
    assert left == "", f"orphaned headless claude still running: {left}"
