"""repo_map producer: walk the sandbox and describe it for the brownfield agents (packages, import graph,
endpoints, tables). Static analysis only: python via ast, java via regex; nothing is imported or executed.

Where it sits: `service.py#_seed` calls `build_repo_map` after a brownfield workspace (`SDLC_WORKSPACE`)
has been copied into the sandbox and committed as the baseline; the resulting `RepoMap` artifact is put
into context as `repo_map`, which the impact agent reads instead of the raw tree.

Invariants:
- Read-only and side-effect free: no files written, no trace events, no code executed.
- Best effort: a python file that does not parse is skipped; a java file without a `package` line is
  attributed to its file stem. The map is an aid for the agents, not a gate.
- Only `<root>/src` is analysed for packages/imports/endpoints; tables are searched under the whole root,
  skipping test files; `SKIP_DIRS` are ignored everywhere.
"""

from __future__ import annotations

import ast
import re
from pathlib import Path

from ..models.repo_map import Endpoint, RepoMap

# Decorator attribute names that count as a FastAPI/Starlette route (`@app.get(...)`, `@router.post(...)`).
HTTP = {"get", "post", "put", "delete", "patch", "head", "options"}
# Directories never descended into: VCS metadata, build output, caches and virtual environments.
SKIP_DIRS = {".git", "__pycache__", ".venv", "node_modules", "target", ".mypy_cache", ".ruff_cache"}
# Table name extractors, one per convention: raw SQL DDL, SQLAlchemy declarative `__tablename__`,
# alembic `op.create_table(...)`, JPA `@Table(name = "...")`.
TABLE_RES = [
    re.compile(r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?[\"`]?(\w+)", re.I),
    re.compile(r"__tablename__\s*=\s*[\"'](\w+)[\"']"),
    re.compile(r"create_table\(\s*[\"'](\w+)[\"']"),
    re.compile(r"@Table\(\s*name\s*=\s*\"(\w+)\""),
]
JAVA_PKG = re.compile(r"^\s*package\s+([\w.]+)\s*;", re.M)
JAVA_IMPORT = re.compile(r"^\s*import\s+(?:static\s+)?([\w.]+)\s*;", re.M)
# Spring MVC method-level mappings; group 1 is the verb (or "Request"), group 2 the path literal.
JAVA_MAPPING = re.compile(r"@(Get|Post|Put|Delete|Patch|Request)Mapping\(\s*(?:value\s*=\s*)?\"([^\"]*)\"")
# Class-level `@RequestMapping("...")` immediately followed by the class declaration: the path prefix.
JAVA_CLASS_MAPPING = re.compile(
    r"@RequestMapping\(\s*(?:value\s*=\s*)?\"([^\"]*)\"\s*\)\s*(?:public\s+)?class"
)


def _files(root: Path, suffix: str) -> list[Path]:
    """All files under `root` ending in `suffix`, sorted, excluding any path with a `SKIP_DIRS` component."""
    return sorted(p for p in root.rglob(f"*{suffix}") if not (set(p.parts) & SKIP_DIRS))


def _tables(root: Path) -> list[str]:
    """Sorted, de-duplicated table names found in `.sql`, `.py` and `.java` files under `root`.

    Files inside a `test` directory or whose name starts with `test` are skipped so fixtures and test
    schemas do not show up as production tables.
    """
    found: set[str] = set()
    for p in _files(root, ".sql") + _files(root, ".py") + _files(root, ".java"):
        if "test" in p.parts or p.name.startswith("test"):
            continue
        text = p.read_text(encoding="utf-8", errors="replace")
        for rx in TABLE_RES:
            found.update(rx.findall(text))
    return sorted(found)


def _python(src: Path) -> tuple[list[str], dict[str, set[str]], list[Endpoint]]:
    """Analyse a python source tree with `ast`: `(sorted packages, package import edges, endpoints)`.

    Module names are derived from the path relative to `src` (`pkg/mod.py` -> `pkg.mod`, `__init__.py`
    collapses to its package). A file's package is the longest known package that prefixes its module.
    Import edges are recorded only between packages of this tree (first segment in `roots`) and never
    self-edges. Endpoints are decorators of the form `@<obj>.<http verb>("<literal path>", ...)` on sync or
    async functions. Files that fail to parse are skipped.
    """
    packages: set[str] = set()
    imports: dict[str, set[str]] = {}
    endpoints: list[Endpoint] = []
    modules: dict[Path, str] = {}
    for py in _files(src, ".py"):
        parts = list(py.relative_to(src).with_suffix("").parts)
        if parts[-1] == "__init__":
            parts = parts[:-1]
        if not parts:
            continue
        modules[py] = ".".join(parts)
        # a module contributes its parent package; a package __init__ (or a top-level file) contributes itself
        packages.add(".".join(parts[:-1]) if py.name != "__init__.py" and len(parts) > 1 else ".".join(parts))
    roots = {p.split(".")[0] for p in packages}

    def package_of(module: str) -> str:
        """Longest package that equals or prefixes `module`; falls back to the module's first segment."""
        cands = [p for p in packages if module == p or module.startswith(p + ".")]
        return max(cands, key=len) if cands else module.split(".")[0]

    for py, module in modules.items():
        try:
            tree = ast.parse(py.read_text(encoding="utf-8", errors="replace"))
        except SyntaxError:
            continue
        here = package_of(module)
        for node in ast.walk(tree):
            names: list[str] = []
            if isinstance(node, ast.Import):
                names = [a.name for a in node.names]
            elif isinstance(node, ast.ImportFrom) and node.module:
                # `from a.b import c` may name a subpackage `a.b.c`, so both forms are resolved
                names = [node.module] + [f"{node.module}.{a.name}" for a in node.names]
            for name in names:
                if name.split(".")[0] in roots:
                    target = package_of(name)
                    if target != here:
                        imports.setdefault(here, set()).add(target)
            if isinstance(node, ast.FunctionDef | ast.AsyncFunctionDef):
                for dec in node.decorator_list:
                    if isinstance(dec, ast.Call) and isinstance(dec.func, ast.Attribute):
                        method = dec.func.attr.lower()
                        if method in HTTP and dec.args and isinstance(dec.args[0], ast.Constant):
                            endpoints.append(
                                Endpoint(method=method.upper(), path=str(dec.args[0].value), module=module)
                            )
    return sorted(packages), imports, endpoints


def _java(src: Path) -> tuple[list[str], dict[str, set[str]], list[Endpoint]]:
    """Analyse a java source tree with regexes: `(sorted packages, package import edges, endpoints)`.

    Packages come from `package x.y;` declarations. An import edge is recorded when an import's package
    (everything before the last dot) is another package of this tree. Endpoints are Spring
    `@<Verb>Mapping("path")` annotations, prefixed with the class-level `@RequestMapping` path when
    present; a bare `@RequestMapping` equal to the class prefix is the prefix itself and is not an
    endpoint, any other `@RequestMapping` is reported with method `ANY`. `module` is the package name.
    """
    packages: set[str] = set()
    texts: dict[Path, str] = {}
    for j in _files(src, ".java"):
        text = j.read_text(encoding="utf-8", errors="replace")
        texts[j] = text
        m = JAVA_PKG.search(text)
        if m:
            packages.add(m.group(1))
    imports: dict[str, set[str]] = {}
    endpoints: list[Endpoint] = []
    for j, text in texts.items():
        m = JAVA_PKG.search(text)
        here = m.group(1) if m else j.stem
        for imp in JAVA_IMPORT.findall(text):
            pkg = imp.rsplit(".", 1)[0]
            if pkg in packages and pkg != here:
                imports.setdefault(here, set()).add(pkg)
        prefix = JAVA_CLASS_MAPPING.search(text)
        base = prefix.group(1) if prefix else ""
        for kind, path in JAVA_MAPPING.findall(text):
            if kind == "Request" and path == base:
                continue  # the class-level prefix itself, already folded into `base`
            endpoints.append(
                Endpoint(method=kind.upper() if kind != "Request" else "ANY", path=base + path, module=here)
            )
    return sorted(packages), imports, endpoints


def build_repo_map(root: Path, stack: str) -> RepoMap:
    """Build the `RepoMap` artifact for the sandbox at `root`.

    `stack` selects the analyser (`"java"` -> `_java`, anything else -> `_python`) over `<root>/src`; a
    missing `src` yields empty packages/imports/endpoints. `files` counts every regular file under `root`
    outside `SKIP_DIRS`. Import edges are sorted for determinism; endpoints are ordered by (path, method).
    """
    src = root / "src"
    if stack == "java":
        packages, imports, endpoints = _java(src) if src.exists() else ([], {}, [])
    else:
        packages, imports, endpoints = _python(src) if src.exists() else ([], {}, [])
    files = len([p for p in root.rglob("*") if p.is_file() and not (set(p.parts) & SKIP_DIRS)])
    return RepoMap(
        stack=stack,
        root_packages=sorted({p.split(".")[0] for p in packages}),
        packages=packages,
        imports={k: sorted(v) for k, v in sorted(imports.items())},
        endpoints=sorted(endpoints, key=lambda e: (e.path, e.method)),
        tables=_tables(root),
        files=files,
    )
