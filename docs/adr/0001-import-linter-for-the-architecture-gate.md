# ADR-0001: import-linter enforces Design layering rules in the python architecture gate

Status: accepted (TASKS T4)

## Context
`Design.classes.layering_rules` states which packages may depend on which (`shortener.api -> shortener.service
-> shortener.repo`). The `architecture` gate for the python stack is `pytest -q tests/test_architecture.py`
(policy.yaml). Something has to turn the design's rules into that test, and the check has to catch indirect
import chains, not just direct imports, or agents can route around it through a helper module.

## Decision
Add `import-linter` as a runtime dependency. The orchestrator generates `tests/test_architecture.py` in the
sandbox from the Design (`engine/arch_contract.py`) and commits it before the executor runs; the test builds an
import-linter *layers* contract per `a -> b -> c` rule (layers marked optional so a not-yet-created package
does not error) and asserts `lint_imports()` returns 0. Rules without `->` are recorded in the test docstring
for humans and are not machine-checked.

## Alternatives rejected
- In-house AST import walker: direct imports only, no chain detection, another thing to maintain.
- ArchUnit-style per-language tooling: java keeps ArchUnit (`ArchitectureTest`), python gets import-linter; each
  is the idiomatic tool for its stack.
- Letting the implementation agent write the architecture test: the agent under test must not author the gate.

## Consequences
- One new dependency (import-linter, which brings grimp). It runs inside the sandbox's interpreter, which is the
  orchestrator's environment today.
- The sandbox needs a `src/` layout for the generated test's `sys.path` handling.
