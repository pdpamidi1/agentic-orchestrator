"""
Change-control and security policy, enforced at the write boundary and in the `scope`/`security` gates.
Every decision is returned as a structured verdict so the caller can emit a POLICY_DECISION trace event.
"""

from __future__ import annotations

import fnmatch
import re
from dataclasses import dataclass, field

from ..models.policy import Policy
from ..models.validation import Finding


def _match(path: str, patterns: list[str]) -> bool:
    # fnmatch treats ** like *; good enough for path allowlists, and we normalise separators
    p = path.replace("\\", "/")
    return any(fnmatch.fnmatch(p, pat) or fnmatch.fnmatch(p, pat.replace("**/", "")) for pat in patterns)


@dataclass(frozen=True)
class PathVerdict:
    path: str
    classification: str  # allowed | protected | forbidden | outside
    required_action: str | None  # high-impact action needed for protected paths


@dataclass
class ScopeVerdict:
    ok: bool
    verdicts: list[PathVerdict] = field(default_factory=list)
    findings: list[Finding] = field(default_factory=list)
    approvals_needed: set[str] = field(default_factory=set)


PROTECTED_ACTION_HINTS = {
    "migration": "schema.migration",
    "alembic": "schema.migration",
    "pom.xml": "dependency.major_version",
    "pyproject": "dependency.major_version",
    "Dockerfile": "infrastructure.change",
    "k8s": "infrastructure.change",
    "helm": "infrastructure.change",
    "workflows": "release.config",
    "application": "secrets.or_config",
}


class PolicyEngine:
    def __init__(self, policy: Policy, stack: str) -> None:
        self.p = policy
        self.stack = stack

    # ---------------------------------------------------------------- change control
    def classify(self, path: str) -> PathVerdict:
        cc = self.p.change_control
        if _match(path, cc.forbidden_paths):
            return PathVerdict(path, "forbidden", None)
        if _match(path, cc.protected_paths):
            action = next(
                (a for k, a in PROTECTED_ACTION_HINTS.items() if k in path), "infrastructure.change"
            )
            return PathVerdict(path, "protected", action)
        if _match(path, cc.allowed_paths):
            return PathVerdict(path, "allowed", None)
        return PathVerdict(path, "outside", None)

    def check_scope(
        self,
        changed_files: list[str],
        task_allowed: list[str],
        approved_actions: set[str],
        lines_changed: int = 0,
    ) -> ScopeVerdict:
        v = ScopeVerdict(ok=True)
        cc = self.p.change_control
        for f in changed_files:
            pv = self.classify(f)
            v.verdicts.append(pv)
            if pv.classification == "forbidden":
                v.ok = False
                v.findings.append(
                    Finding(
                        file=f,
                        rule="change_control.forbidden_path",
                        message="agent wrote a forbidden path",
                        suggested_fix="revert this file",
                    )
                )
            elif pv.classification == "outside":
                v.ok = False
                v.findings.append(
                    Finding(
                        file=f,
                        rule="change_control.outside_allowed_paths",
                        message="path is outside policy.allowed_paths",
                    )
                )
            elif pv.classification == "protected" and pv.required_action not in approved_actions:
                v.ok = False
                v.approvals_needed.add(pv.required_action or "")
                v.findings.append(
                    Finding(
                        file=f,
                        rule="change_control.protected_path",
                        message=f"requires human approval: {pv.required_action}",
                    )
                )
            if task_allowed and not _match(f, task_allowed):
                v.ok = False
                v.findings.append(
                    Finding(
                        file=f,
                        rule="task.allowed_files",
                        message="file not in TaskSpec.allowed_files",
                        suggested_fix="split into a new task",
                    )
                )
        if len(changed_files) > cc.max_files_changed_per_task:
            v.ok = False
            v.findings.append(
                Finding(
                    rule="change_control.max_files",
                    message=f"{len(changed_files)} files > {cc.max_files_changed_per_task}",
                )
            )
        if lines_changed > cc.max_lines_changed_per_task:
            v.ok = False
            v.findings.append(
                Finding(
                    rule="change_control.max_lines",
                    message=f"{lines_changed} lines > {cc.max_lines_changed_per_task}",
                )
            )
        prod = [
            f
            for f in changed_files
            if _match(f, cc.require_tests_for) and "/test" not in f and "tests/" not in f
        ]
        tests = [f for f in changed_files if "/test" in f or "tests/" in f]
        if prod and not tests:
            v.ok = False
            v.findings.append(
                Finding(
                    rule="change_control.require_tests", message="production change without a test change"
                )
            )
        return v

    # ---------------------------------------------------------------- security scan
    def scan(self, files: dict[str, str]) -> list[Finding]:
        """files: path -> content of changed files."""
        out: list[Finding] = []
        secrets = [re.compile(p) for p in self.p.security.secret_patterns]
        banned = [re.compile(p) for p in self.p.security.banned_code_patterns.get(self.stack, [])]
        for path, content in files.items():
            for i, line in enumerate(content.splitlines(), start=1):
                for rx in secrets:
                    if rx.search(line):
                        out.append(
                            Finding(
                                file=path,
                                line=i,
                                rule="security.secret",
                                message="possible secret committed",
                                suggested_fix="move to environment/secret manager",
                            )
                        )
                for rx in banned:
                    if rx.search(line):
                        out.append(
                            Finding(
                                file=path,
                                line=i,
                                rule="security.banned_pattern",
                                message=f"matches {rx.pattern}",
                            )
                        )
        pii = (self.p.compliance.get("pii") or {}).get("forbidden_in_persistence", [])
        for path, content in files.items():
            for term in pii:
                if re.search(term.replace("_", "[_ ]?"), content, re.IGNORECASE):
                    out.append(
                        Finding(
                            file=path,
                            rule="compliance.pii",
                            message=f"persists forbidden field {term}",
                            suggested_fix="hash/salt or drop the field",
                        )
                    )
        return out

    # ---------------------------------------------------------------- sandbox commands
    def command_allowed(self, cmd: str) -> bool:
        sb = self.p.sandbox
        if any(bad in cmd for bad in sb.forbidden_commands):
            return False
        head = cmd.strip().split()[0] if cmd.strip() else ""
        return any(cmd.strip().startswith(ok) or head == ok.split()[0] for ok in sb.allowed_commands)
