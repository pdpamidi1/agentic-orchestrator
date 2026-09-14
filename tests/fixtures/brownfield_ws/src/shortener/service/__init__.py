"""Short code allocation and lookup."""

from __future__ import annotations

from shortener.repo import UrlRow, find, save


def shorten(long_url: str) -> str:
    code = f"c{len(long_url) % 97:02d}"
    save(UrlRow(code, long_url))
    return code


def resolve(short_code: str) -> str | None:
    row = find(short_code)
    return row.long_url if row else None
