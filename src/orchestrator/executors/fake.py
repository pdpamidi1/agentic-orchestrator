"""
FakeExecutor: the executor half of the offline loop (SDLC_LLM=fake, python target stack).

Per TaskSpec it writes a trivial module plus a unit test into the sandbox and commits, exactly like a
real executor would: one commit per task on the run branch, files only where the task allows them.
When the task's allowed_files name release artifacts (README, openapi.yaml, Dockerfile, CI workflow) it
writes those too; the OpenAPI document is taken from the Design so the contract stays spec-driven.
The output is just enough for the python gates in policy.yaml to pass: compileall, ruff/mypy, the
architecture test, `pytest tests/unit --cov`, `pytest tests/integration`, and the release checklist.

Planted failure (TASKS T5): on the node's first attempt the first task also writes `.env`, a forbidden path,
so every fake run demonstrates POLICY_DECISION=VIOLATION -> revert -> a clean second attempt. The policy
engine, not the executor, is what catches it.

Where it sits: selected by `service.py` when `SDLC_LLM=fake`, with the plant chosen per scenario from
`llm/fake.py#PLANTED` ("scope" greenfield, "pii" brownfield, "test" ambiguous). Under `--record` it is
wrapped by `RecordingExecutor`, which is how the offline golden run's patches are produced.

Invariants: no network, no LLM, deterministic content for a given (TaskSpec, Design, existing tree);
never writes outside `task.allowed_files` except for the planted `.env`; scaffolding files (package
`__init__`, conftest, app, README...) are written once per sandbox and never overwritten.

Trace events: one `EXECUTOR_CALL` per task attempt (actor `fake-executor`, files written, whether a
failure was planted). Files written: the generated sources/tests inside the sandbox plus one git commit.
"""

from __future__ import annotations

import asyncio
import fnmatch
import re
from pathlib import Path
from typing import Any

from ..engine.context import RunContext
from ..models import Design, TaskSpec
from ..models.trace import Kind
from ..sandbox.git import GitSandbox
from .base import Done, ExecResult

# Release artifacts a task may own by name (the greenfield plan's T4). They are written with `once` when
# allowed and skipped by the concrete-path loop so the Design-derived openapi.yaml is never stubbed over.
RELEASE_FILES = ("README.md", "openapi.yaml", "Dockerfile", ".github/workflows/ci.yml")
PLANTED_FILE = ".env"  # policy.change_control.forbidden_paths: never writable, under any approval


def _allowed(path: str, patterns: list[str]) -> bool:
    """True when `path` matches one of the task's `allowed_files` globs.

    `fnmatch` has no `**` semantics, so each pattern is also tried with its `**/` prefix stripped
    (`src/**` matches `src/pkg/mod.py` through the plain `*`, and `**/x.py` matches `x.py`).
    """
    return any(fnmatch.fnmatch(path, p) or fnmatch.fnmatch(path, p.replace("**/", "")) for p in patterns)


def _ident(s: str) -> str:
    """Turn an arbitrary string (task id, package name, operationId) into a valid python identifier.

    Non-word characters become underscores; a leading non-letter gets a `t_` prefix so `T1` -> `t1` and
    `2fa` -> `t_2fa`.
    """
    ident = re.sub(r"\W", "_", s.lower())
    return ident if ident[:1].isalpha() else f"t_{ident}"


class FakeExecutor:
    """Deterministic stand-in for a coding agent: writes stubs that satisfy the python gates and commits.

    One instance per run; `plant` decides which failure the first attempt demonstrates. `render()` is a
    pure function of its inputs and is unit-tested on its own; `execute()` adds the plant, writes files,
    commits and emits the trace event.
    """

    def __init__(self, plant: str | None = "scope") -> None:
        """Choose the planted failure: "scope" (writes .env), "pii" (raw IP field), "test" (failing unit
        test) or None for a clean run."""
        self.plant = plant  # "scope" writes .env; "pii" persists a raw IP field; None plants nothing

    def render(self, task: TaskSpec, design: Design, existing: set[str]) -> dict[str, str]:
        """Files to write for this task (path -> content). Scaffolding is written once per sandbox.

        `existing` is the set of files already in the sandbox (relative paths); anything routed through
        the local `once()` helper is skipped when present or when the task's `allowed_files` do not cover
        it. What is produced, in order:
        - `src/<pkg>/<task>.py` + `tests/unit/test_<task>.py` when the task may touch that module path;
          with them, one-time scaffolding: the package `__init__`, `tests/conftest.py` (puts `src` on
          sys.path), one `__init__` per Design package (the layering rules name them), a FastAPI `app.py`
          mirroring the Design contract plus a test pinning its paths, and an integration smoke test;
        - a stub for every concrete (glob-free, non-release) path the task owns, e.g. a migration file or
          `pyproject.toml`; an existing file gets the stub appended (`_APPEND` marker) rather than replaced;
        - the release artifacts (README from `design.decisions`, openapi.yaml verbatim from the Design,
          Dockerfile, CI workflow), each only if allowed and absent.
        `<pkg>` is the first segment of the first Design package (or `app`).
        """
        pkg = _ident((design.classes.packages[0].name if design.classes.packages else "app").split(".")[0])
        mod = _ident(task.id)
        out: dict[str, str] = {}

        def once(path: str, content: str) -> None:
            """Queue `path` only if it is absent from the sandbox and inside the task's allowed files."""
            if path not in existing and _allowed(path, task.allowed_files):
                out[path] = content

        module = f"src/{pkg}/{mod}.py"
        if _allowed(module, task.allowed_files):
            out[module] = (
                f'"""{task.title} (fake executor stand-in for task {task.id})."""\n\n'
                "from __future__ import annotations\n\n\n"
                f"def {mod}() -> str:\n"
                f'    """Return the task id; the real implementation of: {task.title}."""\n'
                f'    return "{task.id}"\n'
            )
            out[f"tests/unit/test_{mod}.py"] = (
                "from __future__ import annotations\n\n"
                f"from {pkg}.{mod} import {mod}\n\n\n"
                f"def test_{mod}() -> None:\n"
                f'    assert {mod}() == "{task.id}"\n'
            )
            once(f"src/{pkg}/__init__.py", f'"""{pkg}: generated by the fake executor."""\n')
            once(
                "tests/conftest.py",
                "import sys\nfrom pathlib import Path\n\n"
                'sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))\n',
            )
            for p in design.classes.packages:  # the layering rules refer to these packages
                once(
                    f"src/{p.name.replace('.', '/')}/__init__.py",
                    f'"""{p.name}: {p.classes[0].responsibility if p.classes else ""}"""\n',
                )
            if design.api.operations:
                # the contract gate compares app.openapi() with the committed openapi.yaml / Design.api
                paths = sorted({o.path for o in design.api.operations})
                once(f"src/{pkg}/app.py", _render_app(design, pkg))
                once(
                    "tests/unit/test_app.py",
                    "from __future__ import annotations\n\n"
                    f"from {pkg}.app import app\n\n\n"
                    "def test_contract_paths_exposed() -> None:\n"
                    f"    assert sorted(app.openapi()['paths']) == {paths!r}\n",
                )
            once(
                "tests/integration/test_smoke.py",
                f"import {pkg}\n\n\ndef test_package_imports() -> None:\n    assert {pkg}.__doc__\n",
            )
        for path in task.allowed_files:  # concrete (glob-free) paths the task owns: migration, dependency...
            if any(ch in path for ch in "*?[") or path in RELEASE_FILES:
                continue
            out[path] = (_APPEND if path in existing else "") + _stub(path, task, design)
        once(
            "README.md",
            f"# {pkg}\n\nGenerated by the fake executor.\n\n## Decisions\n"
            + "".join(f"- {d}\n" for d in design.decisions),
        )
        once("openapi.yaml", design.api.openapi_yaml)
        once(
            "Dockerfile",
            "FROM python:3.12-slim\nWORKDIR /app\nCOPY src /app/src\nENV PYTHONPATH=/app/src\n"
            f'CMD ["python", "-c", "import {pkg}"]\n',
        )
        once(
            ".github/workflows/ci.yml",
            "name: ci\non: [push]\njobs:\n  test:\n    runs-on: ubuntu-latest\n    steps:\n"
            "      - uses: actions/checkout@v4\n      - run: pip install pytest && pytest -q\n",
        )
        return out

    async def execute(
        self, ctx: RunContext, task: TaskSpec, design: Design, feedback: dict[str, Any] | None
    ) -> ExecResult:
        """Write the rendered files (plus the planted failure on attempt 1), commit, and report `Done`.

        `feedback["attempt"]` is the node-level count of earlier attempts, so the plant fires for tasks run
        during the node's first attempt; a scope/pii violation then fails that attempt, which is why only
        the first task carries it in practice. Plants: "scope" adds `.env` (forbidden path -> policy
        VIOLATION -> revert); "test" adds a unit test that always fails (validation -> diagnose -> replan,
        it survives because it is never a policy violation); "pii" appends a `raw_ip_address` line to the
        first rendered file so the compliance scan trips.

        Side effects: files written in the sandbox (append semantics for `_APPEND`-marked stubs), one git
        commit, one `EXECUTOR_CALL` event. Never returns `BlockedTask` or `Errored`.
        """
        git = GitSandbox(ctx.sandbox)
        base = await git.head()
        existing = await asyncio.to_thread(_tree, ctx.sandbox)
        files = self.render(task, design, existing)
        attempt = (feedback or {}).get("attempt", 0) + 1
        planted = self.plant is not None and attempt == 1  # the first task of a node's first attempt
        if planted and self.plant == "scope":  # out-of-scope write for the policy engine to catch
            files[PLANTED_FILE] = (
                "# planted by the fake executor: agents must never write env files\nAPP_ENV=local\n"
            )
        elif planted and self.plant == "test":  # a unit test that fails: validation -> diagnose -> re-plan
            files["tests/unit/test_planted_failure.py"] = (
                "def test_planted_acceptance_gap() -> None:\n"
                '    assert False, "planted: acceptance criterion not met by any task"\n'
            )
        elif planted and self.plant == "pii" and files:  # persist a raw IP in a file the task may write
            target = sorted(files)[0]
            files[target] += "\n# planted: analytics row keeps the visitor address\nraw_ip_address = None\n"
        for (
            rel,
            content,
        ) in files.items():  # a stub marked _APPEND extends an existing file (e.g. pyproject.toml)
            await asyncio.to_thread(_write, ctx.sandbox / rel, content, content.startswith(_APPEND))
        changed = await git.changed_files(base)
        sha = await git.commit_task(task.id, task.title, ctx.run_id)
        ctx.emit(
            Kind.EXECUTOR_CALL,
            task_id=task.id,
            attempt=attempt,
            actor="fake-executor",
            payload={"files": changed, "planted_violation": planted},
        )
        return Done(
            files_changed=changed,
            tests_added=[f for f in changed if f.startswith("tests/")],
            commit_sha=sha,
            notes="fake executor: trivial module + test",
        )


# Prefix marking a stub that must be appended to an existing file instead of replacing it; `_write` strips
# nothing, so the marker also stays visible in the file as a comment.
_APPEND = "\n# --- appended by the fake executor ---\n"


def _stub(path: str, task: TaskSpec, design: Design) -> str:
    """Content for a concrete path a task owns, by extension.

    `.py` -> an alembic-shaped module with `upgrade`/`downgrade` naming the task's tables; `.toml` -> a
    comment line standing in for a dependency declaration; `.sql` -> one `CREATE TABLE IF NOT EXISTS` per
    table in `data_model_slice`; anything else -> a one-line comment with the task id and title.
    """
    if path.endswith(".py"):
        tables = ", ".join(task.data_model_slice) or "n/a"
        return (
            f'"""{task.title} (fake executor, task {task.id}). Tables: {tables}."""\n\n'
            "from __future__ import annotations\n\n\n"
            "def upgrade() -> None:\n"
            f'    """Would create: {tables}."""\n\n\n'
            "def downgrade() -> None:\n"
            f'    """Would drop: {tables}."""\n'
        )
    if path.endswith(".toml"):
        return f"# {task.id}: {task.title} -- aiokafka>=0.10 would be declared here\n"
    if path.endswith(".sql"):
        return "".join(
            f"CREATE TABLE IF NOT EXISTS {t} (id bigserial PRIMARY KEY);\n" for t in task.data_model_slice
        )
    return f"# {task.id}: {task.title}\n"


def _render_app(design: Design, pkg: str) -> str:
    """A FastAPI app whose OpenAPI document is exactly the Design contract (operation ids, paths, codes).

    One route per `design.api.operations` entry: the lowest response code is the route's `status_code`, the
    others go into `responses=` so they appear in `app.openapi()`; every handler just returns that status.
    This is what the `contract` gate dumps via `sandbox/openapi_dump.py` and diffs against the Design.
    """
    lines = [
        '"""FastAPI app exposing the Design API contract (fake executor)."""',
        "",
        "from __future__ import annotations",
        "",
        "from fastapi import FastAPI, Response",
        "",
        f"app = FastAPI(title={pkg!r})",
    ]
    for op in design.api.operations:
        by_code = op.codes
        codes = sorted(by_code) or [200]
        success = codes[0]
        others = ", ".join(f"{c}: {{'description': {by_code[c]!r}}}" for c in codes if c != success)
        lines += [
            "",
            "",
            f"@app.{op.method.lower()}({op.path!r}, operation_id={op.operation_id!r}, status_code={success}, "
            f"responses={{{others}}})",
            f"def {_ident(op.operation_id)}() -> Response:",
            f"    return Response(status_code={success})",
        ]
    return "\n".join(lines) + "\n"


def _tree(root: Path) -> set[str]:
    """All regular files under `root` as relative POSIX-ish strings, ignoring the `.git` directory.

    Runs in a worker thread; used to decide which scaffolding already exists.
    """
    return {str(p.relative_to(root)) for p in root.rglob("*") if p.is_file() and ".git" not in p.parts}


def _write(path: Path, content: str, append: bool = False) -> None:
    """Write `content` to `path`, creating parents; with `append` and an existing file, concatenate instead.

    Runs in a worker thread so the event loop is not blocked by sandbox I/O.
    """
    path.parent.mkdir(parents=True, exist_ok=True)
    if append and path.exists():
        content = path.read_text(encoding="utf-8") + content
    path.write_text(content, encoding="utf-8")
