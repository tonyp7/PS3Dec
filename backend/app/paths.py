from __future__ import annotations

from pathlib import Path

from .errors import ApiError


class BadName(ApiError):
    def __init__(self, message: str):
        super().__init__(422, "bad_name", message)


def resolve_in(folder: Path, name: str, suffix: str, what: str) -> Path:
    """Map a client-supplied bare file name to a regular file directly inside ``folder``.

    Rejects anything containing a path separator or NUL, ``.``/``..``, a wrong extension,
    or a name that is not an existing regular file.
    """
    if (
        not name
        or "\x00" in name
        or "/" in name
        or "\\" in name
        or name in (".", "..")
        or not name.lower().endswith(suffix)
    ):
        raise BadName(f"invalid {what} name: {name!r}")
    path = folder / name
    if not path.is_file():
        raise BadName(f"{what} not found: {name!r}")
    return path
