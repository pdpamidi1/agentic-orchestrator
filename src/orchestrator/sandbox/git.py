"""Git operations on the sandbox: one commit per task on a run branch, revert on rollback.

Where it sits: every executor and the implementation handler talk to the sandbox repository through
`GitSandbox`; `service.py#_seed` calls `ensure_repo`/`start_run_branch`/`set_run_base` when a run starts,
and the run-level `scope`/`security` gates diff the whole run against the `sdlc/base` tag via `run_base`.

Invariants:
- One branch per run, one commit per task (`commit_task`), rollback is `git revert` of that commit
  (`revert`), so the history stays inspectable with ordinary git tooling.
- `sdlc/base` marks where the run branch forked (after the orchestrator's own baseline commits); it is
  the reference point for "everything this run changed".
- Tool output the gates produce (`__pycache__`, `.coverage`, caches, `target/`) is excluded through
  `.git/info/exclude` so it never reads as an agent change and the agent's own files are never edited.
- Every git call is an `asyncio` subprocess with stderr folded into stdout; return codes are returned,
  not raised (except in `revert`, which raises on an unresolvable conflict).

No trace events and no files outside the sandbox's `.git` are written here, except `export_patch`, which
writes a diff wherever the caller asks (the changesets cache).
"""

from __future__ import annotations

import asyncio
from pathlib import Path


class GitSandbox:
    """Thin async wrapper around the `git` CLI for one sandbox directory.

    Stateless apart from `root`; every method issues git commands with `cwd=root`. Safe to construct many
    times for the same directory (executors do so per task).
    """

    def __init__(self, root: Path) -> None:
        """`root` is the sandbox working tree (`runs/<id>/sandbox`)."""
        self.root = root

    async def _git(self, *args: str) -> tuple[int, str]:
        """Run `git <args>` in the sandbox and return `(returncode, combined stdout+stderr)`.

        Never raises on a non-zero exit; callers inspect the code. Output is decoded with replacement so
        odd bytes in file names or diffs cannot break a run.
        """
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
        """Make `root` a git repository with a baseline commit and the gate-output excludes in place.

        If `.git` is missing: create the directory, `git init`, stage everything already present (a seeded
        brownfield workspace is the baseline, not an agent change) and commit (`--allow-empty` so an empty
        greenfield sandbox still gets a root commit). Always (re)writes `.git/info/exclude` from `EXCLUDES`.
        Idempotent.
        """
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
        """Create and check out the run branch, tagging its fork point as `sdlc/base`.

        If the branch already exists (a resumed run), it is checked out without moving the tag, so the
        base still points at the original fork.
        """
        rc, _ = await self._git("checkout", "-q", "-b", branch)
        if rc == 0:
            await self._git("tag", "-f", self.BASE_TAG, "HEAD")
        else:
            await self._git("checkout", "-q", branch)

    async def head(self) -> str:
        """Full sha of the current HEAD commit."""
        _, out = await self._git("rev-parse", "HEAD")
        return out.strip()

    async def set_run_base(self) -> None:
        """Move the base to HEAD: called after the orchestrator commits its own baseline, before any task."""
        await self._git("tag", "-f", self.BASE_TAG, "HEAD")

    async def run_base(self) -> str:
        """Sha the `sdlc/base` tag points at, or HEAD when the tag does not exist (no run started yet)."""
        rc, out = await self._git("rev-parse", "-q", "--verify", f"{self.BASE_TAG}^{{commit}}")
        return out.strip() if rc == 0 else await self.head()

    async def changed_files(self, base: str = "HEAD") -> list[str]:
        """Sorted paths that differ from `base`: tracked changes (`git diff --name-only`) plus untracked
        files not covered by ignore/exclude rules.

        Used both to measure a task (base = pre-task HEAD) and the whole run (base = `sdlc/base`).
        """
        _, out = await self._git("diff", "--name-only", base)
        _, untracked = await self._git("ls-files", "--others", "--exclude-standard")
        return sorted({*out.split(), *untracked.split()})

    async def lines_changed(self, base: str = "HEAD") -> int:
        """Added + deleted line count versus `base` from `git diff --numstat` (binary files, shown as
        `-`, are skipped). Untracked files are not counted. Feeds the policy size limits."""
        _, out = await self._git("diff", "--numstat", base)
        total = 0
        for line in out.splitlines():
            parts = line.split("\t")
            if len(parts) >= 2 and parts[0].isdigit() and parts[1].isdigit():
                total += int(parts[0]) + int(parts[1])
        return total

    async def changed_file_contents(self, base: str = "HEAD") -> dict[str, str]:
        """Current contents of every file in `changed_files(base)` that still exists on disk.

        Deleted files are absent from the result; unreadable files are skipped. This is what the policy
        engine scans for secrets, banned patterns and PII.
        """
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
        """Stage everything (`git add -A`) and commit as `<task_id>: <title>` with a `run: <run_id>` trailer.

        Returns the new HEAD sha. If nothing changed the commit fails silently and the returned sha is the
        unchanged HEAD.
        """
        await self._git("add", "-A")
        await self._git("commit", "-q", "-m", f"{task_id}: {title}\n\nrun: {run_id}")
        _, sha = await self._git("rev-parse", "HEAD")
        return sha.strip()

    async def revert(self, sha: str) -> None:
        """Undo one task commit with `git revert --no-edit`, never leaving the tree mid-revert.

        On a conflict (a later commit touched the same paths) the revert is aborted; if `sha` is HEAD the
        commit is dropped with `reset --hard sha~1` instead, otherwise `RuntimeError` is raised with the
        tail of git's output. Used by the handler on a policy violation (`ROLLED_BACK`).
        """
        rc, out = await self._git("revert", "--no-edit", sha)
        if rc != 0:  # a later commit touched the same paths: never leave the tree mid-revert
            await self._git("revert", "--abort")
            if await self.head() == sha:
                await self._git("reset", "--hard", f"{sha}~1")
                return
            raise RuntimeError(f"cannot revert {sha[:8]}: conflicts and it is not HEAD\n{out[-500:]}")

    async def reset_working_tree(self) -> None:
        """Discard uncommitted changes: restore tracked files and delete untracked files/directories.

        Excluded paths (`.git/info/exclude`) survive because `clean` is run without `-x`. Used when an
        executor reports BLOCKED or a recorded patch fails to apply.
        """
        await self._git("checkout", "--", ".")
        await self._git("clean", "-fdq")

    async def export_patch(self, base: str, dest: Path) -> None:
        """Write `git diff <base>` (committed and uncommitted changes since `base`) to `dest`.

        Parent directories are created. This is how `RecordingExecutor` produces
        `runs/cache/changesets/<scenario>/<task>.attempt<n>.patch`.
        """
        _, out = await self._git("diff", base)
        await asyncio.to_thread(dest.parent.mkdir, parents=True, exist_ok=True)
        await asyncio.to_thread(dest.write_text, out, encoding="utf-8")
