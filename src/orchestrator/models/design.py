"""``Design``: the low-level design the executor implements from.

Produced by the architecture agent (node ``architecture``) from ``spec``, ``plan`` and, for brownfield,
``impact``. Consumed by the executor (per-task slices of API operations, tables and classes), the
``contract`` gate (``ApiContract`` is the fallback when no OpenAPI document is committed), the
``architecture`` gate (``ClassStructure.layering_rules`` become import-linter/ArchUnit contracts, generated
and committed before the executor runs), and the security, risk and documenter agents.

Typing note: ``Operation.responses`` is a list of ``ResponseSpec`` rather than a ``dict[int, str]``
because structured outputs turn free-form dicts into empty objects (the first live Design came back with
no response codes). The ``codes`` property restores the dict view for consumers.
"""

from __future__ import annotations

from pydantic import Field

from .common import Frozen


class ResponseSpec(Frozen):
    """One HTTP status code an operation can return, with its meaning for that operation."""

    status: int = Field(description="HTTP status code, e.g. 201")
    description: str = Field(
        description="what the code means for this operation, e.g. 'created' or 'alias taken'"
    )


class Operation(Frozen):
    """One API operation of the contract, keyed by ``operation_id`` (what ``TaskSpec.contract_slice`` lists).

    ``responses`` must be non-empty (success first) so the contract gate has codes to diff against the
    running app. ``breaking`` marks an operation the design knowingly changes incompatibly, which the gate
    only allows under the ``api.contract.breaking_change`` high-impact approval.
    """

    method: str
    path: str
    operation_id: str
    responses: list[ResponseSpec] = Field(
        min_length=1, description="every status code the operation can return, success first"
    )
    breaking: bool = False

    @property
    def codes(self) -> dict[int, str]:
        """``{status: description}`` view of ``responses`` for gate diffs and prompt rendering."""
        return {r.status: r.description for r in self.responses}


class ApiContract(Frozen):
    """The API surface: a full OpenAPI YAML document plus the same operations as typed ``Operation`` rows.

    The YAML is what gets committed into the sandbox for humans and tooling; the typed list is what the
    executor prompt and the contract gate consume.
    """

    openapi_yaml: str
    operations: list[Operation]


class Column(Frozen):
    """A table column; ``type`` is the target stack's SQL/ORM type as free text."""

    name: str
    type: str
    nullable: bool = False
    notes: str = ""


class Table(Frozen):
    """A persistent table (``TaskSpec.data_model_slice`` refers to ``name``); ``constraints`` are DDL text."""

    name: str
    columns: list[Column]
    constraints: list[str] = []


class DataModel(Frozen):
    """Persistence design: tables plus the migration bodies needed to reach them.

    Any migration is a protected, high-impact action under ``policy.yaml`` (``schema.migration``), so a
    task that touches one is planned as ``impact_level=HIGH`` and pauses for approval.
    """

    tables: list[Table]
    migrations: list[str]  # migration file bodies; any migration is a high-impact action
    evolution_notes: str = ""  # backward-compat strategy (expand/contract, versioned events)


class Clazz(Frozen):
    """A class/module the executor must create: fully qualified name, single responsibility, dependencies."""

    fqcn: str
    responsibility: str
    depends_on: list[str] = []


class Package(Frozen):
    """A package/layer grouping classes; ``may_depend_on`` is the allowed outgoing package edges."""

    name: str
    classes: list[Clazz]
    may_depend_on: list[str] = []


class ClassStructure(Frozen):
    """Static structure of the solution: packages, classes and the layering rules the architecture gate
    enforces."""

    packages: list[Package]
    layering_rules: list[str]  # compiled into ArchUnit / import-linter rules by the architecture gate


class Design(Frozen):
    """Low-level design artifacts. The executor implements from THESE, never from the requirement.

    ``spec_version`` records which ``Spec`` the design answers, so a spec bump visibly invalidates it.
    ``decisions`` are ADR-style one-liners carried into the documenter's output.
    """

    run_id: str
    spec_version: int
    api: ApiContract
    data: DataModel
    classes: ClassStructure
    decisions: list[str] = []  # ADR-style one-liners with rejected alternatives
