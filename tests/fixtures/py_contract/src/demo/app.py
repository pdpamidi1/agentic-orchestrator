from __future__ import annotations

from fastapi import FastAPI, Response

app = FastAPI(title="demo")


@app.post(
    "/api/v1/urls", operation_id="createShortUrl", status_code=201, responses={400: {"description": "P"}}
)
def create() -> Response:
    return Response(status_code=201)


@app.get("/{short_code}", operation_id="redirect", status_code=302, responses={404: {"description": "P"}})
def redirect() -> Response:
    return Response(status_code=302)
