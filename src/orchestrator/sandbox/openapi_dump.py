"""Standalone script run INSIDE the sandbox by the `contract` gate (python stack).

Imports every module under <src_dir>, finds the first FastAPI application instance and prints its OpenAPI
document as one JSON line. No orchestrator imports: this file is executed by the sandbox's interpreter.
"""

from __future__ import annotations

import importlib
import json
import sys
from pathlib import Path


def main(src: str) -> int:
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
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "src"))
