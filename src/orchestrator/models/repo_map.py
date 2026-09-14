from __future__ import annotations

from .common import Frozen


class Endpoint(Frozen):
    method: str
    path: str
    module: str


class RepoMap(Frozen):
    """What the brownfield agents reason over: packages, their import edges, HTTP endpoints, tables."""

    stack: str
    root_packages: list[str]
    packages: list[str]
    imports: dict[str, list[str]]  # package -> in-repo packages it imports
    endpoints: list[Endpoint]
    tables: list[str]
    files: int
