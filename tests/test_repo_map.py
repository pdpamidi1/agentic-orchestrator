"""T8: repo_map producer on the brownfield fixture (python) and a small java tree."""

from __future__ import annotations

from pathlib import Path

from orchestrator.sandbox.repo_map import build_repo_map

FIXTURE = Path(__file__).resolve().parent / "fixtures" / "brownfield_ws"


def test_python_repo_map_has_packages_edges_endpoints_and_tables() -> None:
    rm = build_repo_map(FIXTURE, "python")
    assert rm.root_packages == ["shortener"]
    assert {"shortener.api", "shortener.service", "shortener.repo"} <= set(rm.packages)
    assert rm.imports["shortener.api"] == ["shortener.service"]
    assert rm.imports["shortener.service"] == ["shortener.repo"]
    assert "shortener.repo" not in rm.imports  # leaf
    assert [(e.method, e.path) for e in rm.endpoints] == [("POST", "/api/v1/urls"), ("GET", "/{short_code}")]
    assert rm.tables == ["urls"] and rm.files > 10
    assert rm.model_dump_json()  # goes into the impact prompt as an artifact


def test_java_repo_map(tmp_path: Path) -> None:
    src = tmp_path / "src" / "main" / "java" / "app"
    (src / "api").mkdir(parents=True)
    (src / "repo").mkdir()
    (src / "api" / "UrlController.java").write_text(
        "package app.api;\nimport app.repo.UrlRepository;\n@RestController\n"
        '@RequestMapping("/api/v1") public class UrlController {\n'
        '  @PostMapping("/urls") void create() {}\n  @GetMapping("/{code}") void go() {}\n}\n'
    )
    (src / "repo" / "Url.java").write_text('package app.repo;\n@Entity @Table(name = "urls") class Url {}\n')
    (src / "repo" / "UrlRepository.java").write_text("package app.repo;\ninterface UrlRepository {}\n")
    mig = tmp_path / "src" / "main" / "resources" / "db" / "migration"
    mig.mkdir(parents=True)
    (mig / "V1__init.sql").write_text(
        "CREATE TABLE urls (id bigserial);\nCREATE TABLE IF NOT EXISTS clicks (id int);\n"
    )
    rm = build_repo_map(tmp_path, "java")
    assert rm.packages == ["app.api", "app.repo"] and rm.imports == {"app.api": ["app.repo"]}
    assert [(e.method, e.path) for e in rm.endpoints] == [("POST", "/api/v1/urls"), ("GET", "/api/v1/{code}")]
    assert rm.tables == ["clicks", "urls"]


def test_empty_sandbox_gives_an_empty_map(tmp_path: Path) -> None:
    rm = build_repo_map(tmp_path, "python")
    assert rm.packages == [] and rm.endpoints == [] and rm.tables == [] and rm.files == 0


def test_java_paths_from_constants_and_test_sources_skipped(tmp_path: Path) -> None:
    """Spring controllers often declare `path = PATH` constants; test fixtures under src/test are not
    the application's endpoints."""
    main = tmp_path / "src" / "main" / "java" / "app" / "web"
    main.mkdir(parents=True)
    (main / "WriteController.java").write_text(
        "package app.web;\n@RestController\n@RequestMapping(path = WriteController.PATH)\n"
        'public class WriteController {\n  public static final String PATH = "/api/v1/urls";\n'
        '  @PostMapping(consumes = "application/json") void create() {}\n}\n'
    )
    (main / "RedirectController.java").write_text(
        "package app.web;\n@RestController\npublic class RedirectController {\n"
        '  public static final String PATH = "/{short_code}";\n  @GetMapping(path = PATH) void go() {}\n}\n'
    )
    test = tmp_path / "src" / "test" / "java" / "app" / "web"
    test.mkdir(parents=True)
    (test / "ThrowingController.java").write_text(
        "package app.web;\n@RestController\nclass ThrowingController {\n"
        '  @GetMapping("/throw/boom") void b() {}\n}\n'
    )
    rm = build_repo_map(tmp_path, "java")
    assert [(e.method, e.path) for e in rm.endpoints] == [("POST", "/api/v1/urls"), ("GET", "/{short_code}")]
