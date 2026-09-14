from __future__ import annotations

from orchestrator.engine.policy_engine import PolicyEngine


def test_classify_paths(policy) -> None:  # type: ignore[no-untyped-def]
    pe = PolicyEngine(policy, "python")
    assert pe.classify("src/app/x.py").classification == "allowed"
    assert pe.classify(".env.local").classification == "forbidden"
    v = pe.classify("src/main/resources/db/migration/V2__x.sql")
    assert v.classification == "protected" and v.required_action == "schema.migration"
    assert pe.classify("k8s/deploy.yaml").classification == "outside"


def test_scope_requires_tests_and_blocks_protected(policy) -> None:  # type: ignore[no-untyped-def]
    pe = PolicyEngine(policy, "python")
    v = pe.check_scope(["src/app/x.py"], ["src/**"], set())
    assert not v.ok and any(f.rule == "change_control.require_tests" for f in v.findings)
    v = pe.check_scope(["src/app/x.py", "tests/test_x.py"], ["src/**", "tests/**"], set())
    assert v.ok
    v = pe.check_scope(["pom.xml", "tests/t.py"], ["**"], set())
    assert not v.ok and "dependency.major_version" in v.approvals_needed
    v = pe.check_scope(["pom.xml", "tests/t.py"], ["**"], {"dependency.major_version"})
    assert v.ok


def test_security_scan(policy) -> None:  # type: ignore[no-untyped-def]
    pe = PolicyEngine(policy, "python")
    f = pe.scan(
        {
            "src/a.py": "key='AKIAABCDEFGHIJKLMNOP'\nx = eval('1')\n",
            "src/m.py": "class Click: raw_ip_address: str",
        }
    )
    rules = {x.rule for x in f}
    assert rules == {"security.secret", "security.banned_pattern", "compliance.pii"}


def test_command_allowlist(policy) -> None:  # type: ignore[no-untyped-def]
    pe = PolicyEngine(policy, "python")
    assert pe.command_allowed("pytest -q") and pe.command_allowed("git diff")
    assert not pe.command_allowed("curl http://x") and not pe.command_allowed("rm -rf /")


def test_glob_double_star_matches_zero_or_more_directories() -> None:
    """``**/`` must accept a file directly at the glob's leaf level, not only nested ones.

    Regression: fnmatch turned ``**/`` into ``*/`` (at least one directory), so every planner glob of the
    shape ``src/main/java/**/domain/**/*.java`` rejected ``.../domain/Entity.java`` as a scope violation.
    """
    from orchestrator.engine.policy_engine import matches

    leaf = "src/main/java/com/example/shortener/analytics/domain/ClickOutboxEntry.java"
    assert matches(leaf, ["src/main/java/**/analytics/domain/**/*.java"])
    assert matches(
        "src/main/java/com/x/analytics/domain/sub/Deep.java", ["src/main/java/**/domain/**/*.java"]
    )
    assert matches("db/migration/V1.sql", ["**/db/migration/**"])  # zero leading directories
    assert matches("src/main/java/x/RedirectController.java", ["src/main/java/**/*RedirectController.java"])
    assert matches("src/app/x.py", ["src/*"])  # fnmatch semantics kept: * crosses "/"
    assert matches("a\\b\\c.txt", ["a/b/*.txt"])  # separators normalised
    assert not matches("tests/x.py", ["src/**"])
    assert not matches(".env.local", ["src/**", "tests/**"])
    assert matches("x.py", ["[!.]*.py"]) and not matches(".x.py", ["[!.]*.py"])
