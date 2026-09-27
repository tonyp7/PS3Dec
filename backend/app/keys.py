from __future__ import annotations

import os
import re
from dataclasses import dataclass
from pathlib import Path, PurePath

from .errors import ApiError
from .paths import resolve_in

MAX_KEY_FILE_BYTES = 256
_HEX32 = re.compile(r"[0-9A-Fa-f]{32}")


@dataclass(frozen=True)
class KeyInfo:
    name: str
    valid: bool
    reason: str | None = None


def parse_d1(raw: bytes) -> str:
    """Canonical upper-case 32-hex D1 from file content, or ValueError with the reason."""
    if len(raw) > MAX_KEY_FILE_BYTES:
        raise ValueError(f"file is larger than {MAX_KEY_FILE_BYTES} bytes")
    try:
        text = raw.decode("ascii")
    except UnicodeDecodeError:
        raise ValueError("file is not ASCII text") from None
    text = text.strip()
    if text[:2] in ("0x", "0X"):
        text = text[2:]
    if not text:
        raise ValueError("file is empty")
    if _HEX32.fullmatch(text) is None:
        if len(text) != 32:
            raise ValueError(f"expected 32 hex characters, found {len(text)}")
        raise ValueError("contains characters that are not hexadecimal")
    return text.upper()


def _read(path: Path) -> bytes:
    with path.open("rb") as f:
        return f.read(MAX_KEY_FILE_BYTES + 1)


def inspect(path: Path) -> KeyInfo:
    try:
        parse_d1(_read(path))
    except ValueError as e:
        return KeyInfo(path.name, False, str(e))
    except OSError as e:
        return KeyInfo(path.name, False, f"unreadable: {e.strerror or e}")
    return KeyInfo(path.name, True)


def list_keys(keys_dir: Path) -> tuple[bool, list[KeyInfo]]:
    """(folder available, keys found); non-recursive, regular `.dkey` files only."""
    try:
        with os.scandir(keys_dir) as it:
            entries = [e for e in it if e.name.lower().endswith(".dkey") and e.is_file()]
    except OSError:
        return False, []
    entries.sort(key=lambda e: (e.name.casefold(), e.name))
    return True, [inspect(Path(e.path)) for e in entries]


def suggest_key(iso_name: str, keys: list[KeyInfo]) -> str | None:
    """A valid key whose stem equals the ISO's stem (case-insensitive); exact case wins."""
    stem = PurePath(iso_name).stem
    matches = [k for k in keys if k.valid and PurePath(k.name).stem.casefold() == stem.casefold()]
    if not matches:
        return None
    exact = [k for k in matches if PurePath(k.name).stem == stem]
    return (exact or matches)[0].name


def load_d1(keys_dir: Path, name: str) -> str:
    """Canonical D1 hex from the named key file; ApiError if it is missing or invalid."""
    path = resolve_in(keys_dir, name, ".dkey", "key")
    try:
        return parse_d1(_read(path))
    except ValueError as e:
        raise ApiError(422, "invalid_key", f"key {name!r} is invalid: {e}") from None
    except OSError as e:
        raise ApiError(422, "invalid_key", f"key {name!r} is unreadable: {e.strerror or e}") from None


def key_for_job(kind: str, keys_dir: Path, key_name: str | None) -> str | None:
    """D1 hex to pass to the tool, or None for 3k3y images (which carry their own key).

    For 3k3y images any supplied key is ignored. Standard images require a valid key.
    """
    if kind.startswith("3k3y"):
        return None
    if not key_name:
        raise ApiError(422, "key_required", "a key is required for this image")
    return load_d1(keys_dir, key_name)
