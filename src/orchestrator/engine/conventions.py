"""
Repository conventions rendered from policy.yaml for the agents and the executor.

The planner decides allowed_files, the architect decides where the OpenAPI document lives and the executor
writes the build; none of them may guess at governance. This module turns the policy (change control paths,
gate commands per stack, the committed contract locations) into the text those prompts receive, so the plan,
the design and the code are written against the gates that will actually run.
"""

from __future__ import annotations

from ..models.policy import Policy

COMMITTED_OPENAPI = ("openapi.yaml", "src/main/resources/openapi.yaml")  # engine.gates reads the same pair
SPRINGDOC_DUMPS = ("target/openapi.json", "target/openapi.yaml")
RELEASE_ARTIFACTS = (
    "Dockerfile",
    ".github/workflows/<workflow>.yml",
    "README.md",
    "the committed OpenAPI document",
)
# files the orchestrator itself provisions into the sandbox before the first task (never agent-written)
PROVISIONED = {
    "java": [
        "mvnw + .mvn/wrapper/maven-wrapper.properties (Maven 3.9)",
        "src/test/java/sdlc/ArchitectureTest.java",
    ],
    "python": ["tests/test_architecture.py (import-linter contract from the design's layering rules)"],
}

_INTERNAL_GATE_TEXT = {
    "diff_scope": "every changed file must be inside allowed paths and the task's allowed_files",
    "secret_and_pattern_scan": "no secrets, banned code patterns or forbidden PII fields in what you write",
    "openapi_diff": (
        "the committed OpenAPI document ({committed}) must match what the running application exposes, "
        "operation by operation and status code by status code"
    ),
    "release_checklist": (
        "Dockerfile, a .github/workflows/ workflow, README.md and the committed OpenAPI document exist"
    ),
}
_STACK_NOTES = {
    "java": (
        "- java: the orchestrator provisions the Maven wrapper (mvnw) and "
        "src/test/java/sdlc/ArchitectureTest.java before the first task; keep both working. Define the `it` "
        "Maven profile so `-Pit verify` runs the integration tests AND writes the springdoc OpenAPI dump to "
        "{dumps} (springdoc-openapi-maven-plugin). Commit the OpenAPI document at "
        "src/main/resources/openapi.yaml."
    ),
    "python": (
        "- python: src/ layout, tests/unit and tests/integration, a FastAPI app importable from src/; the "
        "orchestrator provisions tests/test_architecture.py, keep it passing. Commit the OpenAPI document at "
        "openapi.yaml."
    ),
}


def gate_contract(policy: Policy, stack: str) -> list[str]:
    """One line per gate policy.yaml will run for this stack: command or meaning, required or advisory."""
    lines: list[str] = []
    for gid in policy.gates:
        g = policy.gate(gid, stack)
        level = "required" if g.required else "advisory"
        if g.cmd.startswith("internal:"):
            text = _INTERNAL_GATE_TEXT.get(g.cmd.split(":", 1)[1])
            if text:
                lines.append(f"- {gid} ({level}): " + text.format(committed=" or ".join(COMMITTED_OPENAPI)))
        else:
            lines.append(f"- {gid} ({level}): `{g.cmd}`")
    if stack in _STACK_NOTES:
        lines.append(_STACK_NOTES[stack].format(dumps=" or ".join(SPRINGDOC_DUMPS)))
    return lines


def render_conventions(policy: Policy, stack: str) -> str:
    """The governance and build facts a planner or architect must respect (Markdown)."""
    cc = policy.change_control
    lines = [
        f"Target stack: {stack}.",
        "",
        "Paths (policy.change_control): tasks may only touch these; anything else is rejected at commit.",
        "- allowed (free): " + ", ".join(cc.allowed_paths),
        "- protected (needs a high-impact approval; mark the task impact_level HIGH): "
        + ", ".join(cc.protected_paths),
        "- forbidden (never): " + ", ".join(cc.forbidden_paths),
        f"- per task at most {cc.max_files_changed_per_task} files / {cc.max_lines_changed_per_task} changed "
        f"lines; production changes under {', '.join(cc.require_tests_for)} need a test change in the "
        "same task",
        "",
        "Provisioned by the orchestrator before the first task (do not plan or write them): "
        + "; ".join(PROVISIONED.get(stack, ["nothing"])),
        "",
        "Gates that run on the whole repository after implementation (plan tasks so they pass):",
        *gate_contract(policy, stack),
        "",
        "Release checklist: " + ", ".join(RELEASE_ARTIFACTS) + ".",
    ]
    return "\n".join(lines)
