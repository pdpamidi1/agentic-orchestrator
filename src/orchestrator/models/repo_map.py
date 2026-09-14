"""``RepoMap``: the structural summary of a brownfield workspace.

Produced at intake by ``sandbox/repo_map.py#build_repo_map`` (ast for python, regex for java) when
``Settings.workspace`` is non-empty, and put into context under producer ``intake`` (``service.py#start``).
Consumed by the impact agent instead of the raw file tree. Not an LLM output, so a plain dict field is
acceptable here (no structured-output schema is derived from it).
"""

from __future__ import annotations

from .common import Frozen


class Endpoint(Frozen):
    """An HTTP route discovered in the workspace and the module/class that declares it."""

    method: str
    path: str
    module: str


class RepoMap(Frozen):
    """What the brownfield agents reason over: packages, their import edges, HTTP endpoints, tables.

    ``root_packages`` are the top-level packages; ``packages`` every package found; ``imports`` only keeps
    in-repo edges (third-party imports are dropped); ``tables`` come from DDL/ORM patterns; ``files`` is the
    number of source files scanned, useful as a sanity check that seeding worked.
    """

    stack: str
    root_packages: list[str]
    packages: list[str]
    imports: dict[str, list[str]]  # package -> in-repo packages it imports
    endpoints: list[Endpoint]
    tables: list[str]
    files: int
