"""Run a command inside the sandbox with policy checks, scrubbed env, and a timeout."""

from __future__ import annotations

import asyncio
import os
import shlex
from pathlib import Path

from ..engine.policy_engine import PolicyEngine
from ..models.policy import Policy


class CommandNotAllowed(RuntimeError):
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
    if not PolicyEngine(policy, stack).command_allowed(cmd):
        raise CommandNotAllowed(f"policy.sandbox forbids: {cmd}")
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
