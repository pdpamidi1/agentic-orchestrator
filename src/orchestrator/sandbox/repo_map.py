"""repo_map producer: walk the sandbox and describe it for the brownfield agents (packages, import graph,
endpoints, tables). Static analysis only: python via ast, java via regex; nothing is imported or executed."""

from __future__ import annotations

import ast
import re
from pathlib import Path

from ..models.repo_map import Endpoint, RepoMap

HTTP = {"get", "post", "put", "delete", "patch", "head", "options"}
SKIP_DIRS = {".git", "__pycache__", ".venv", "node_modules", "target", ".mypy_cache", ".ruff_cache"}
TABLE_RES = [
    re.compile(r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?[\"`]?(\w+)", re.I),
    re.compile(r"__tablename__\s*=\s*[\"'](\w+)[\"']"),
    re.compile(r"create_table\(\s*[\"'](\w+)[\"']"),
    re.compile(r"@Table\(\s*name\s*=\s*\"(\w+)\""),
]
JAVA_PKG = re.compile(r"^\s*package\s+([\w.]+)\s*;", re.M)
JAVA_IMPORT = re.compile(r"^\s*import\s+(?:static\s+)?([\w.]+)\s*;", re.M)
JAVA_MAPPING = re.compile(r"@(Get|Post|Put|Delete|Patch|Request)Mapping\(\s*(?:value\s*=\s*)?\"([^\"]*)\"")
JAVA_CLASS_MAPPING = re.compile(
    r"@RequestMapping\(\s*(?:value\s*=\s*)?\"([^\"]*)\"\s*\)\s*(?:public\s+)?class"
)


def _files(root: Path, suffix: str) -> list[Path]:
    return sorted(p for p in root.rglob(f"*{suffix}") if not (set(p.parts) & SKIP_DIRS))


def _tables(root: Path) -> list[str]:
    found: set[str] = set()
    for p in _files(root, ".sql") + _files(root, ".py") + _files(root, ".java"):
        if "test" in p.parts or p.name.startswith("test"):
            continue
        text = p.read_text(encoding="utf-8", errors="replace")
        for rx in TABLE_RES:
            found.update(rx.findall(text))
    return sorted(found)


def _python(src: Path) -> tuple[list[str], dict[str, set[str]], list[Endpoint]]:
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
        packages.add(".".join(parts[:-1]) if py.name != "__init__.py" and len(parts) > 1 else ".".join(parts))
    roots = {p.split(".")[0] for p in packages}

    def package_of(module: str) -> str:
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
                continue
            endpoints.append(
                Endpoint(method=kind.upper() if kind != "Request" else "ANY", path=base + path, module=here)
            )
    return sorted(packages), imports, endpoints


def build_repo_map(root: Path, stack: str) -> RepoMap:
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
