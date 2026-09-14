"""001: urls table."""

from __future__ import annotations


def upgrade() -> None:
    """op.create_table("urls", ...)"""


def downgrade() -> None:
    """op.drop_table("urls")"""
