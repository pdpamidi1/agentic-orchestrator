"""Persistence for the urls table."""

from __future__ import annotations


class UrlRow:
    __tablename__ = "urls"

    def __init__(self, short_code: str, long_url: str) -> None:
        self.short_code, self.long_url = short_code, long_url


STORE: dict[str, UrlRow] = {}


def save(row: UrlRow) -> None:
    STORE[row.short_code] = row


def find(short_code: str) -> UrlRow | None:
    return STORE.get(short_code)
