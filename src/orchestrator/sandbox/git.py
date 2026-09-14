"""Git operations on the sandbox: one commit per task on a run branch, revert on rollback."""

from __future__ import annotations

import asyncio
from pathlib import Path


class GitSandbox:
    def __init__(self, root: Path) -> None:
        self.root = root

    async def _git(self, *args: str) -> tuple[int, str]:
        proc = await asyncio.create_subprocess_exec(
            "git", *args, cwd=str(self.root), stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT
        )
        out, _ = await proc.communicate()
        return proc.returncode or 0, out.decode(errors="replace")

    # tool output the gates themselves produce; kept out of the tree diff without touching the agent's files
    EXCLUDES = (
        "__pycache__/",
        "*.pyc",
        ".pytest_cache/",
        ".mypy_cache/",
        ".ruff_cache/",
        ".coverage",
        ".import_linter_cache/",
        "target/",
    )

    async def ensure_repo(self) -> None:
        if not (self.root / ".git").exists():
            self.root.mkdir(parents=True, exist_ok=True)
            await self._git("init", "-q")
            await self._git("add", "-A")  # a seeded workspace is the baseline, not an agent change
            await self._git("commit", "--allow-empty", "-q", "-m", "chore: initial (agentic-sdlc)")
        exclude = self.root / ".git" / "info" / "exclude"
        await asyncio.to_thread(exclude.parent.mkdir, parents=True, exist_ok=True)
        await asyncio.to_thread(exclude.write_text, "\n".join(self.EXCLUDES) + "\n", encoding="utf-8")

    BASE_TAG = "sdlc/base"  # where the run branch forked: gates diff the whole run against it

    async def start_run_branch(self, branch: str) -> None:
        rc, _ = await self._git("checkout", "-q", "-b", branch)
        if rc == 0:
            await self._git("tag", "-f", self.BASE_TAG, "HEAD")
        else:
            await self._git("checkout", "-q", branch)

    async def head(self) -> str:
        _, out = await self._git("rev-parse", "HEAD")
        return out.strip()

    async def run_base(self) -> str:
        rc, out = await self._git("rev-parse", "-q", "--verify", f"{self.BASE_TAG}^{{commit}}")
        return out.strip() if rc == 0 else await self.head()

    async def changed_files(self, base: str = "HEAD") -> list[str]:
        _, out = await self._git("diff", "--name-only", base)
        _, untracked = await self._git("ls-files", "--others", "--exclude-standard")
        return sorted({*out.split(), *untracked.split()})

    async def lines_changed(self, base: str = "HEAD") -> int:
        _, out = await self._git("diff", "--numstat", base)
        total = 0
        for line in out.splitlines():
            parts = line.split("\t")
            if len(parts) >= 2 and parts[0].isdigit() and parts[1].isdigit():
                total += int(parts[0]) + int(parts[1])
        return total

    async def changed_file_contents(self, base: str = "HEAD") -> dict[str, str]:
        out: dict[str, str] = {}
        for f in await self.changed_files(base):
            p = self.root / f
            if p.is_file():
                try:
                    out[f] = p.read_text(encoding="utf-8", errors="replace")
                except OSError:
                    pass
        return out

    async def commit_task(self, task_id: str, title: str, run_id: str) -> str:
        await self._git("add", "-A")
        await self._git("commit", "-q", "-m", f"{task_id}: {title}\n\nrun: {run_id}")
        _, sha = await self._git("rev-parse", "HEAD")
        return sha.strip()

    async def revert(self, sha: str) -> None:
        await self._git("revert", "--no-edit", sha)

    async def reset_working_tree(self) -> None:
        await self._git("checkout", "--", ".")
        await self._git("clean", "-fdq")

    async def export_patch(self, base: str, dest: Path) -> None:
        _, out = await self._git("diff", base)
        await asyncio.to_thread(dest.parent.mkdir, parents=True, exist_ok=True)
        await asyncio.to_thread(dest.write_text, out, encoding="utf-8")
