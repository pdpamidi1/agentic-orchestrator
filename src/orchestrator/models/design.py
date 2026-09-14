from __future__ import annotations

from pydantic import Field

from .common import Frozen


class ResponseSpec(Frozen):
    status: int = Field(description="HTTP status code, e.g. 201")
    description: str = Field(
        description="what the code means for this operation, e.g. 'created' or 'alias taken'"
    )


class Operation(Frozen):
    method: str
    path: str
    operation_id: str
    responses: list[ResponseSpec] = Field(
        min_length=1, description="every status code the operation can return, success first"
    )
    breaking: bool = False

    @property
    def codes(self) -> dict[int, str]:
        return {r.status: r.description for r in self.responses}


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
