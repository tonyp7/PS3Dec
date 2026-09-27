from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path
from typing import Literal

from .paths import BadName, resolve_in  # noqa: F401  (BadName re-exported)

SECTOR = 2048
HEADER_SIZE = 2 * SECTOR
MARKER_OFFSET = 0xF70
TABLE_OFFSET = 12

Kind = Literal["3k3y-encrypted", "3k3y-decrypted", "standard", "invalid"]


@dataclass(frozen=True)
class IsoInfo:
    name: str
    size: int
    kind: Kind
    reason: str | None = None

    @property
    def valid(self) -> bool:
        return self.kind != "invalid"


def _invalid(name: str, size: int, reason: str) -> IsoInfo:
    return IsoInfo(name=name, size=size, kind="invalid", reason=reason)


def region_table_problem(header: bytes, size: int) -> str | None:
    """Why the region table is not consistent with the file, or None if it is."""
    count = int.from_bytes(header[0:4], "big")
    if count < 1:
        return "region table is empty (not a PS3 disc image?)"
    entries = 2 * count - 1
    if TABLE_OFFSET + 4 * entries > len(header):
        return f"region table claims {count} region pairs, which does not fit the header (not a PS3 disc image?)"
    table = [int.from_bytes(header[TABLE_OFFSET + 4 * i : TABLE_OFFSET + 4 * i + 4], "big") for i in range(entries)]
    if any(b <= a for a, b in zip(table, table[1:])):
        return "region table end sectors are not increasing"
    expected = (table[-1] + 1) * SECTOR
    if expected != size:
        return f"region table describes {expected} bytes but the file is {size} bytes"
    return None


def classify(path: Path) -> IsoInfo:
    name = path.name
    try:
        size = path.stat().st_size
        if size < HEADER_SIZE:
            return _invalid(name, size, f"file is smaller than {HEADER_SIZE} bytes")
        with path.open("rb") as f:
            header = f.read(HEADER_SIZE)
    except OSError as e:
        return _invalid(name, 0, f"unreadable: {e.strerror or e}")
    if len(header) < HEADER_SIZE:
        return _invalid(name, size, "file changed while reading")

    marker = header[MARKER_OFFSET]
    kind: Kind
    if marker == 0:
        kind = "standard"
    elif marker in b"Ee":
        kind = "3k3y-encrypted"
    elif marker in b"Dd":
        kind = "3k3y-decrypted"
    else:
        return _invalid(name, size, f"unrecognised 3k3y marker byte 0x{marker:02x}")

    problem = region_table_problem(header, size)
    if problem:
        return _invalid(name, size, problem)
    return IsoInfo(name=name, size=size, kind=kind)


def list_isos(iso_dir: Path) -> tuple[bool, list[IsoInfo]]:
    """(folder available, ISOs found); non-recursive, regular `.iso` files only."""
    try:
        with os.scandir(iso_dir) as it:
            entries = [e for e in it if e.name.lower().endswith(".iso") and e.is_file()]
    except OSError:
        return False, []
    entries.sort(key=lambda e: (e.name.casefold(), e.name))
    return True, [classify(Path(e.path)) for e in entries]


def resolve_iso(iso_dir: Path, name: str) -> Path:
    """Map a client-supplied name to a file in the ISO folder, or raise BadName."""
    return resolve_in(iso_dir, name, ".iso", "ISO")
