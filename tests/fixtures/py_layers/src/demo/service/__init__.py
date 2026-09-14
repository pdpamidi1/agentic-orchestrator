from demo.repo import load


def resolve(code: str) -> str:
    return load(code).upper()
