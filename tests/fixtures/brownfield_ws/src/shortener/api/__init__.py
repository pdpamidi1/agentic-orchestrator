"""HTTP layer."""

from __future__ import annotations

from fastapi import FastAPI, Response

from shortener.service import resolve, shorten

app = FastAPI(title="shortener")


@app.post("/api/v1/urls", operation_id="createShortUrl", status_code=201, responses={400: {}, 409: {}})
def create(long_url: str = "https://example.com") -> dict[str, str]:
    return {"short_url": f"/{shorten(long_url)}"}


@app.get("/{short_code}", operation_id="redirect", status_code=302, responses={404: {}, 410: {}})
def redirect(short_code: str = "") -> Response:
    target = resolve(short_code)
    return Response(status_code=302 if target else 404, headers={"Location": target or "/"})
