from __future__ import annotations

from .common import Frozen


class Operation(Frozen):
    method: str
    path: str
    operation_id: str
    responses: dict[int, str]
    breaking: bool = False


class ApiContract(Frozen):
    openapi_yaml: str
    operations: list[Operation]


class Column(Frozen):
    name: str
    type: str
    nullable: bool = False
    notes: str = ""


class Table(Frozen):
    name: str
    columns: list[Column]
    constraints: list[str] = []


class DataModel(Frozen):
    tables: list[Table]
    migrations: list[str]  # migration file bodies; any migration is a high-impact action
    evolution_notes: str = ""  # backward-compat strategy (expand/contract, versioned events)


class Clazz(Frozen):
    fqcn: str
    responsibility: str
    depends_on: list[str] = []


class Package(Frozen):
    name: str
    classes: list[Clazz]
    may_depend_on: list[str] = []


class ClassStructure(Frozen):
    packages: list[Package]
    layering_rules: list[str]  # compiled into ArchUnit / import-linter rules by the architecture gate


class Design(Frozen):
    """Low-level design artifacts. The executor implements from THESE, never from the requirement."""

    run_id: str
    spec_version: int
    api: ApiContract
    data: DataModel
    classes: ClassStructure
    decisions: list[str] = []  # ADR-style one-liners with rejected alternatives
