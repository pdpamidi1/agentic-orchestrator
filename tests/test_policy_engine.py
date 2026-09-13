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
