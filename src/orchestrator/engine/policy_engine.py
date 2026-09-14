"""
Change-control and security policy, enforced at the write boundary and in the `scope`/`security` gates.
Every decision is returned as a structured verdict so the caller can emit a POLICY_DECISION trace event.

Where it sits: an ``engine`` leaf over the parsed ``policy.yaml`` (``models/policy.py``). Two callers:
- ``engine/handlers.py`` (executor node): right after each task commits, ``check_scope`` on the task's files
  plus ``scan`` on their contents; a violation reverts the commit (``ROLLED_BACK``) and feeds the findings
  back to the next attempt.
- ``engine/gates.py`` (``scope`` and ``security`` gates): the same checks over the whole run diff against
  the ``sdlc/base`` tag, with per-task size limits switched off.
``sandbox/process.py`` asks ``command_allowed`` before spawning anything in the sandbox.

Invariants
- This module never mutates state and never emits events itself: it returns ``Finding``s / verdicts and the
  caller records the ``POLICY_DECISION``. That keeps the rule and the audit record in one place each.
- Policy is law: nothing here reads approvals directly; the caller passes the set of approved high-impact
  action names, and a protected path is acceptable only when its required action is in that set.
"""

from __future__ import annotations

import functools
import re
from dataclasses import dataclass, field

from ..models.policy import Policy
from ..models.validation import Finding


def matches(path: str, patterns: list[str]) -> bool:
    """Public alias of ``_match``: does ``path`` match any of the glob ``patterns``? (used by handlers)."""
    return _match(path, patterns)


def _match(path: str, patterns: list[str]) -> bool:
    """Glob match with policy semantics.

    ``*`` and ``?`` behave as in ``fnmatch`` (``*`` crosses ``/``, so ``src/*`` is a whole subtree, as
    every allowlist in ``policy.yaml`` and every planner glob assumes). ``**/`` means "zero or more
    directories": ``src/main/java/**/domain/**/*.java`` matches a file directly inside ``domain/`` as well
    as one nested deeper, and ``**/db/migration/**`` matches ``db/migration/V1.sql`` at the root.
    (``fnmatch`` alone treats ``**/`` as ``*/`` and demands at least one directory, which made every file
    at a glob's leaf level a false ``task.allowed_files`` violation.) Backslashes are normalised so
    Windows-style paths from a tool still match.
    """
    p = path.replace("\\", "/")
    return any(_glob_re(pat).match(p) is not None for pat in patterns)


@functools.lru_cache(maxsize=4096)
def _glob_re(pattern: str) -> re.Pattern[str]:
    """Compile one policy glob to a full-match regex (cached; patterns repeat across every check)."""
    out: list[str] = []
    i, n = 0, len(pattern)
    while i < n:
        c = pattern[i]
        if pattern.startswith("**/", i):
            out.append("(?:.*/)?")  # zero or more directories
            i += 3
        elif c == "*":
            out.append(".*")  # fnmatch semantics: crosses "/"
            i += 1
        elif c == "?":
            out.append(".")
            i += 1
        elif c == "[":
            j = pattern.find("]", i + 1)
            if j < 0:
                out.append(re.escape(c))
                i += 1
            else:
                body = pattern[i + 1 : j]
                if body.startswith("!"):
                    body = "^" + body[1:]
                out.append("[" + body.replace("\\", "\\\\") + "]")
                i = j + 1
        else:
            out.append(re.escape(c))
            i += 1
    return re.compile("(?s:" + "".join(out) + r")\Z")


@dataclass(frozen=True)
class PathVerdict:
    """Classification of one path under ``policy.change_control``.

    Precedence is forbidden > protected > allowed > outside (see ``PolicyEngine.classify``), so a path that
    matches both a protected and an allowed glob is protected.
    """

    path: str
    classification: str  # allowed | protected | forbidden | outside
    required_action: str | None  # high-impact action needed for protected paths


@dataclass
class ScopeVerdict:
    """Result of ``PolicyEngine.check_scope`` over a set of changed files.

    ``ok`` is False as soon as any finding is recorded. ``approvals_needed`` lists the high-impact action
    names that would have made the protected-path findings pass; the caller can surface them to a human.
    """

    ok: bool
    verdicts: list[PathVerdict] = field(default_factory=list)  # one per changed file, in input order
    findings: list[Finding] = field(default_factory=list)  # what the caller records / feeds back
    approvals_needed: set[str] = field(default_factory=set)  # action names missing from approved_actions


# Maps a substring of a protected path to the high-impact action (policy.autonomy.high_impact_actions) that
# writing it requires. First hit in insertion order wins; anything protected but unmatched defaults to
# "infrastructure.change". Mirrors the bracketed annotations in policy.yaml#change_control.protected_paths.
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
    """Stateless evaluator of ``policy.yaml`` for one target stack.

    Cheap to construct (two attribute assignments), so callers build one per check instead of sharing.
    ``stack`` selects the banned-code patterns (``security.banned_code_patterns[stack]``); the change-control
    paths and secret patterns are stack-independent.
    """

    def __init__(self, policy: Policy, stack: str) -> None:
        """Bind the parsed policy and the target stack (``"java"`` | ``"python"``)."""
        self.p = policy
        self.stack = stack

    # ---------------------------------------------------------------- change control
    def classify(self, path: str) -> PathVerdict:
        """Classify one repository path as forbidden, protected, allowed or outside.

        Forbidden wins over everything (a ``.env`` under ``src/`` is still forbidden); protected paths get
        the required action from ``PROTECTED_ACTION_HINTS`` (default ``infrastructure.change``); a path that
        matches no list at all is ``outside`` and is rejected by ``check_scope``.
        """
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
        limits: bool = True,
    ) -> ScopeVerdict:
        """Judge a set of changed files against change control, task scope and size limits.

        Args:
            changed_files: repository-relative paths the agent changed (committed or not).
            task_allowed: the ``TaskSpec.allowed_files`` globs in force (all tasks' globs at gate time);
                an empty list disables the per-task rule.
            approved_actions: high-impact action names a human approved (or that an approved HIGH task
                mapped onto); a protected path passes only when its required action is here.
            lines_changed: total changed lines, compared with ``max_lines_changed_per_task`` when ``limits``.
            limits: enforce the per-task file/line ceilings. The write boundary passes True (one task's
                files); the run-level ``scope`` gate passes False because the whole run is naturally larger.

        Returns:
            A ``ScopeVerdict``; rules emitted: ``change_control.forbidden_path``,
            ``change_control.outside_allowed_paths``, ``change_control.protected_path``,
            ``task.allowed_files``, ``change_control.max_files``, ``change_control.max_lines``,
            ``change_control.require_tests`` (a production change under ``require_tests_for`` with no test
            file in the same set).
        """
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
            # task scope is checked independently of the policy classification: an allowed path is still a
            # violation when this task was not planned to touch it (the planner owns allowed_files)
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
        if limits and len(changed_files) > cc.max_files_changed_per_task:
            v.ok = False
            v.findings.append(
                Finding(
                    rule="change_control.max_files",
                    message=f"{len(changed_files)} files > {cc.max_files_changed_per_task}",
                )
            )
        if limits and lines_changed > cc.max_lines_changed_per_task:
            v.ok = False
            v.findings.append(
                Finding(
                    rule="change_control.max_lines",
                    message=f"{lines_changed} lines > {cc.max_lines_changed_per_task}",
                )
            )
        # "a test file" is recognised by path convention ("/test" covers src/test/java, "tests/" the python
        # layout); the rule fires only when production code changed and no test file did
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
        """files: path -> content of changed files.

        Three passes, all regex based and line oriented where a line number is meaningful:
        - ``security.secret`` for every ``policy.security.secret_patterns`` hit (line-numbered);
        - ``security.banned_pattern`` for the stack's ``banned_code_patterns`` (line-numbered);
        - ``compliance.pii`` when a ``compliance.pii.forbidden_in_persistence`` term appears anywhere in a
          file, case-insensitively and with ``_`` matching ``_``, a space or nothing (``raw_ip_address``
          also catches ``rawIpAddress``-style spellings only partially; ``raw ip address`` fully).

        Returns every finding (no early exit) so the next attempt sees the complete list.
        """
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
        """May ``cmd`` run inside the sandbox (``policy.sandbox``)?

        Any ``forbidden_commands`` substring anywhere in the command line vetoes it (so ``x && rm -rf`` is
        caught). Otherwise the command must start with an ``allowed_commands`` entry, or its first word must
        equal the entry's first word (``"docker compose"`` allows ``docker ...``). An empty command is not
        allowed.
        """
        sb = self.p.sandbox
        if any(bad in cmd for bad in sb.forbidden_commands):
            return False
        head = cmd.strip().split()[0] if cmd.strip() else ""
        return any(cmd.strip().startswith(ok) or head == ok.split()[0] for ok in sb.allowed_commands)
