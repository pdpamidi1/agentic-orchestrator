"""Standalone script run INSIDE the sandbox by the `contract` gate (python stack).

Imports every module under <src_dir>, finds the first FastAPI application instance and prints its OpenAPI
document as one JSON line. No orchestrator imports: this file is executed by the sandbox's interpreter.

Where it sits: `engine/gates.py` runs `python <this file> src` through `sandbox/process.run_command`
and parses stdout; the gate then diffs the document against the committed `openapi.yaml` (else
`Design.api`) to find breaking or undocumented operations.

Contract of the output: exactly one JSON object on stdout. Success -> the OpenAPI document, exit 0.
Failure -> `{"error": ..., "import_errors": [...]}`, exit 3, so the gate can show why no app was found.
Importing the target's modules executes their top-level code: that is deliberate (it is the sandbox's own
interpreter and environment) and the reason this file must stay free of orchestrator imports.
"""

from __future__ import annotations

import importlib
import json
import sys
from pathlib import Path


def main(src: str) -> int:
    """Import modules under `src` until a `FastAPI` instance is found; print its `openapi()` as JSON.

    `src` is put at the front of `sys.path` so `pkg.mod` imports resolve. Modules are visited in sorted
    path order; `__init__` collapses to its package; any path segment starting with `test`, `.` or `_` is
    skipped (tests, hidden and private modules). A module that raises on import is recorded (first 10
    reported) and the walk continues, since another module may hold the app. Returns 0 on success, 3 when
    fastapi is not importable or no application instance exists.
    """
    root = Path(src).resolve()
    sys.path.insert(0, str(root))
    try:
        from fastapi import FastAPI
    except ImportError:
        print(json.dumps({"error": "fastapi is not importable in the sandbox environment"}))
        return 3
    errors: list[str] = []
    for py in sorted(root.rglob("*.py")):
        parts = list(py.relative_to(root).with_suffix("").parts)
        if parts[-1] == "__init__":
            parts = parts[:-1]
        if not parts or any(p.startswith(("test", ".", "_")) for p in parts):
            continue
        name = ".".join(parts)
        try:
            module = importlib.import_module(name)
        except Exception as e:  # a broken module is reported, not fatal: another module may hold the app
            errors.append(f"{name}: {type(e).__name__}: {e}")
            continue
        for value in list(vars(module).values()):
            if isinstance(value, FastAPI):
                print(json.dumps(value.openapi()))
                return 0
    print(json.dumps({"error": f"no FastAPI application found under {src}", "import_errors": errors[:10]}))
    return 3


if __name__ == "__main__":
    # default to `src` so the gate command can be just `python openapi_dump.py` from the sandbox root
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "src"))
