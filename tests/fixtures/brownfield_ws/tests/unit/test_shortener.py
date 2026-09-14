from shortener.api import app
from shortener.service import resolve, shorten


def test_shorten_and_resolve() -> None:
    code = shorten("https://example.com/a")
    assert resolve(code) == "https://example.com/a" and resolve("nope") is None


def test_contract_paths() -> None:
    assert set(app.openapi()["paths"]) == {"/api/v1/urls", "/{short_code}"}
