"""Run a command inside the sandbox with policy checks, scrubbed env, and a timeout.

Where it sits: `engine/gates.py` uses `run_command` for every gate command in `policy.yaml#gates`
(compile, style, unit, integration, ...). It is the only place orchestrator code spawns a shell in the
sandbox, so the `policy.sandbox` command allowlist is enforced exactly once, here.

Invariants:
- A command the `PolicyEngine` does not allow never starts: `CommandNotAllowed` is raised first. The
  caller (the gate runner) is responsible for recording the `POLICY_DECISION`.
- The child sees only PATH, HOME, JAVA_HOME and LANG; no API keys or orchestrator settings leak into
  build tools or tests.
- The timeout is enforced here and the child is killed on expiry, so no gate can outlive its budget.

No files are written and no trace events are emitted by this module.
"""

from __future__ import annotations

import asyncio
import os
import shlex
from pathlib import Path

from ..engine.policy_engine import PolicyEngine
from ..models.policy import Policy


class CommandNotAllowed(RuntimeError):
    """Raised before execution when `policy.sandbox` does not allow the command for this stack."""

    pass


# `timeout` is the policy budget for the command; the child process must be killed here on expiry,
# so the caller cannot own the deadline with asyncio.timeout (hence the ASYNC109 exemption).
async def run_command(
    cmd: str,
    cwd: Path,
    policy: Policy,
    timeout: int,  # noqa: ASYNC109
    stack: str = "java",
) -> tuple[int, str]:
    """Execute `cmd` through the shell in `cwd` and return `(exit_code, combined stdout+stderr)`.

    Parameters: `cmd` is the exact shell string from the gate definition; `policy`/`stack` select the
    allowlist checked by `PolicyEngine.command_allowed`; `timeout` is in seconds.
    Failure modes: `CommandNotAllowed` when policy rejects the command (nothing is spawned); on timeout the
    process is killed and `(124, "timeout after <n>s: <cmd>")` is returned, mirroring the `timeout(1)`
    convention so gates treat it like any other failing command. Output is decoded with replacement.
    """
    if not PolicyEngine(policy, stack).command_allowed(cmd):
        raise CommandNotAllowed(f"policy.sandbox forbids: {cmd}")
    # scrubbed environment: enough for python/java build tools, nothing from the orchestrator's own config
    env = {k: v for k, v in os.environ.items() if k in ("PATH", "HOME", "JAVA_HOME", "LANG")}
    proc = await asyncio.create_subprocess_shell(
        cmd, cwd=str(cwd), env=env, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT
    )
    try:
        out, _ = await asyncio.wait_for(proc.communicate(), timeout=timeout)
    except TimeoutError:
        proc.kill()
        return 124, f"timeout after {timeout}s: {shlex.quote(cmd)}"
    return proc.returncode or 0, out.decode(errors="replace")
