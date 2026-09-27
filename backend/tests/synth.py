"""Synthetic PS3-format disc images for tests.

Layout, as read from src/PS3Dec.c:

* sector 0 starts with a big-endian region-pair count N; regions = 2N-1.
* from offset 12, ``2N-1`` big-endian end-sector entries.
* regions alternate plain, encrypted, plain, ... and end on a plain region.
  Plain region i covers ``[prev, table[i]]`` inclusive; an encrypted region
  covers ``[table[i-1]+1, table[i]-1]``. Total sectors is ``table[-1] + 1``.
* 3k3y images carry a 16-byte state marker at 0xF70 and D1 at 0xF80.
* each sector is AES-128-CBC with IV = 12 zero bytes + big-endian LBA.
"""

from __future__ import annotations

import random
from collections.abc import Sequence

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

SECTOR = 2048
MARKER_OFFSET = 0xF70
D1_OFFSET = 0xF80
ENCRYPTED_MARKER = b"Encrypted 3K ISO"
DECRYPTED_MARKER = b"Decrypted 3K ISO"

# Constants that PS3Dec uses to turn D1 into the disc key.
_KEY_D1 = bytes([0x38, 11, 0xCF, 11, 0x53, 0x45, 0x5B, 60, 120, 0x17, 0xAB, 0x4F, 0xA3, 0xBA, 0x90, 0xED])
_IV_D1 = bytes([0x69, 0x47, 0x47, 0x72, 0xAF, 0x6F, 0xDA, 0xB3, 0x42, 0x74, 0x3A, 0xEF, 170, 0x18, 0x62, 0x87])

DEFAULT_D1 = bytes.fromhex("00112233445566778899AABBCCDDEEFF")


def d1_hex(d1: bytes = DEFAULT_D1) -> str:
    return d1.hex().upper()


def _cbc(key: bytes, iv: bytes, data: bytes, *, encrypt: bool) -> bytes:
    cipher = Cipher(algorithms.AES(key), modes.CBC(iv))
    ctx = cipher.encryptor() if encrypt else cipher.decryptor()
    return ctx.update(data) + ctx.finalize()


def disc_key(d1: bytes = DEFAULT_D1) -> bytes:
    return _cbc(_KEY_D1, _IV_D1, d1, encrypt=True)


def _table_entries(regions: Sequence[int]) -> list[int]:
    assert len(regions) % 2 == 1 and regions[0] >= 2, "need plain/enc/.../plain, first plain >= 2"
    table, start = [], 0
    for i, count in enumerate(regions):
        table.append(start + count - 1 if i % 2 == 0 else start + count)
        start += count
    return table


def build_header_only(regions: Sequence[int]) -> bytearray:
    """A 2-sector header holding a valid region table (rest of image not included)."""
    table = _table_entries(regions)
    head = bytearray(2 * SECTOR)
    head[0:4] = ((len(regions) + 1) // 2).to_bytes(4, "big")
    for i, entry in enumerate(table):
        head[12 + 4 * i : 16 + 4 * i] = entry.to_bytes(4, "big")
    return head


def build_decrypted(
    regions: Sequence[int] = (4, 6, 4),
    *,
    seed: int = 0,
    marker: bytes | None = None,
    d1: bytes = DEFAULT_D1,
) -> bytes:
    """A decrypted image; ``marker`` makes it a 3k3y image in that state."""
    rng = random.Random(seed)
    total = sum(regions)
    data = bytearray(rng.randbytes(total * SECTOR))
    head = build_header_only(regions)
    data[0 : len(head)] = head
    if marker is not None:
        assert len(marker) == 16
        data[MARKER_OFFSET : MARKER_OFFSET + 16] = marker
        data[D1_OFFSET : D1_OFFSET + 16] = d1
    else:
        data[MARKER_OFFSET] = 0
    return bytes(data)


def encrypted_ranges(data: bytes) -> list[tuple[int, int]]:
    """Half-open ``(first_sector, end_sector)`` ranges of the encrypted regions."""
    n = int.from_bytes(data[0:4], "big")
    table = [int.from_bytes(data[12 + 4 * i : 16 + 4 * i], "big") for i in range(2 * n - 1)]
    return [(table[i - 1] + 1, table[i]) for i in range(1, len(table), 2)]


def _crypt(data: bytes, d1: bytes, *, encrypt: bool, marker: bytes | None) -> bytes:
    key = disc_key(d1)
    out = bytearray(data)
    for first, end in encrypted_ranges(data):
        for lba in range(first, end):
            iv = bytes(12) + lba.to_bytes(4, "big")
            lo = lba * SECTOR
            out[lo : lo + SECTOR] = _cbc(key, iv, bytes(out[lo : lo + SECTOR]), encrypt=encrypt)
    if marker is not None:
        out[MARKER_OFFSET : MARKER_OFFSET + 16] = marker
    return bytes(out)


def encrypt(data: bytes, d1: bytes = DEFAULT_D1, *, three_k3y: bool = False) -> bytes:
    """Independent reference for what ``PS3Dec e`` should produce."""
    return _crypt(data, d1, encrypt=True, marker=ENCRYPTED_MARKER if three_k3y else None)


def decrypt(data: bytes, d1: bytes = DEFAULT_D1, *, three_k3y: bool = False) -> bytes:
    return _crypt(data, d1, encrypt=False, marker=DECRYPTED_MARKER if three_k3y else None)
