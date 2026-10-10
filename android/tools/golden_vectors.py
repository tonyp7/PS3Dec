#!/usr/bin/env python3
"""Regenerate the engine's golden vectors from the reference C tool.

Each vector is a synthetic image (built deterministically from a seed, with the same SplitMix64
generator the Kotlin tests use) plus the SHA-256 of what the real PS3Dec binary produces from it.
Only seeds, layouts and hashes are stored, in
android/engine/src/test/resources/golden-vectors.tsv; the Kotlin tests rebuild the images and compare.

Usage (build the C tool first, see the repository README):

    python3 android/tools/golden_vectors.py [--tool build/Release/PS3Dec] [--check]

With --check nothing is written; the script exits non-zero if the file would change.

The generator in this file and SyntheticImage.kt must stay in step. The input hash recorded for
every vector makes a drift between them show up as a clear test failure.
"""

from __future__ import annotations

import argparse
import hashlib
import subprocess
import sys
import tempfile
from pathlib import Path

SECTOR = 2048
HEADER_SIZE = 2 * SECTOR
MARKER_OFFSET = 0xF70
D1_OFFSET = 0xF80
ENCRYPTED_MARKER = b"Encrypted 3K ISO"
DECRYPTED_MARKER = b"Decrypted 3K ISO"
DEFAULT_D1 = bytes(i * 0x11 for i in range(16))
# A disc key as a redump .dkey file holds it (the tool's `key` type, used as is).
DEFAULT_DISC_KEY = bytes.fromhex("AFA263ED1E09FD1CB8C6206F967218F8")

REPO = Path(__file__).resolve().parents[2]
OUT = REPO / "android" / "engine" / "src" / "test" / "resources" / "golden-vectors.tsv"

_M = (1 << 64) - 1


class SplitMix64:
    def __init__(self, seed: int):
        self.state = seed & _M

    def _next(self) -> int:
        self.state = (self.state + 0x9E3779B97F4A7C15) & _M
        z = self.state
        z = ((z ^ (z >> 30)) * 0xBF58476D1CE4E5B9) & _M
        z = ((z ^ (z >> 27)) * 0x94D049BB133111EB) & _M
        return z ^ (z >> 31)

    def bytes(self, n: int) -> bytes:
        out = bytearray()
        while len(out) < n:
            out += self._next().to_bytes(8, "little")
        return bytes(out[:n])


def table_entries(regions: list[int]) -> list[int]:
    assert len(regions) % 2 == 1 and regions[0] >= 2
    table, start = [], 0
    for i, count in enumerate(regions):
        table.append(start + count - 1 if i % 2 == 0 else start + count)
        start += count
    return table


def build_image(regions: list[int], seed: int, marker: bytes | None) -> bytes:
    data = bytearray(SplitMix64(seed).bytes(sum(regions) * SECTOR))
    head = bytearray(HEADER_SIZE)
    head[0:4] = ((len(regions) + 1) // 2).to_bytes(4, "big")
    for i, entry in enumerate(table_entries(regions)):
        head[12 + 4 * i : 16 + 4 * i] = entry.to_bytes(4, "big")
    data[0:HEADER_SIZE] = head
    if marker is not None:
        data[MARKER_OFFSET : MARKER_OFFSET + 16] = marker
        data[D1_OFFSET : D1_OFFSET + 16] = DEFAULT_D1
    else:
        data[MARKER_OFFSET] = 0
    return bytes(data)


# (name, regions in sectors, marker, mode). Standard images are run with both the d1 and the key type; 3k3y images read
# their key from the header. 4096 sectors is the reference tool's buffer size, so layouts around
# it (and multiples of it) exercise its block-boundary handling.
LAYOUTS: list[tuple[str, list[int], str | None]] = [
    ("one converted region", [2, 6, 2], None),
    ("two converted regions", [4, 5, 3, 7, 2], None),
    ("three converted regions", [3, 4, 2, 5, 2, 6, 3], None),
    ("single sector converted region", [2, 1, 2], None),
    ("converted region of 4095 sectors", [2, 4095, 2], None),
    ("converted region of 4096 sectors", [2, 4096, 2], None),
    ("converted region of 4097 sectors", [2, 4097, 2], None),
    ("converted region of 8192 sectors", [2, 8192, 2], None),
    ("converted region of 8193 sectors", [3, 8193, 3], None),
    ("converted region of 12289 sectors", [2, 12289, 2], None),
    ("plain regions larger than a buffer", [4100, 5, 4100], None),
    ("first plain region of 4096 sectors", [4096, 3, 2], None),
    ("converted regions straddling blocks", [5, 4094, 7, 4100, 9], None),
    ("3k3y straddling a block", [3, 4100, 2], "3k3y"),
    ("3k3y several regions", [2, 7, 3, 4097, 4], "3k3y"),
]


def sha(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


def run_tool(tool: Path, mode: str, key_type: str, src: Path, dst: Path) -> None:
    letter = "e" if mode == "encrypt" else "d"
    args = [str(tool), letter]
    args += ["3k3y"] if key_type == "3k3y" else [key_type, (DEFAULT_D1 if key_type == "d1" else DEFAULT_DISC_KEY).hex().upper()]
    args += [str(src), str(dst)]
    proc = subprocess.run(args, stdin=subprocess.DEVNULL, capture_output=True, check=False)
    if proc.returncode != 0:
        sys.exit(f"tool failed ({proc.returncode}) for {src.name}: {proc.stderr.decode(errors='replace')[-300:]}")


def generate(tool: Path) -> list[str]:
    rows = ["# name\tregions\tseed\tmarker\tmode\tkey\tinput_sha256\toutput_sha256"]
    seed = 100
    with tempfile.TemporaryDirectory() as tmp:
        tmpdir = Path(tmp)
        for name, regions, kind in LAYOUTS:
            key_types = ["3k3y"] if kind == "3k3y" else ["d1", "key"]
            for key_type in key_types:
                for mode in ("encrypt", "decrypt"):
                    seed += 1
                    if kind == "3k3y":
                        marker = DECRYPTED_MARKER if mode == "encrypt" else ENCRYPTED_MARKER
                        marker_name, keycol = ("decrypted" if mode == "encrypt" else "encrypted"), "-"
                    else:
                        value = DEFAULT_D1 if key_type == "d1" else DEFAULT_DISC_KEY
                        marker, marker_name, keycol = None, "-", f"{key_type}:{value.hex().upper()}"
                    image = build_image(regions, seed, marker)
                    src, dst = tmpdir / "in.iso", tmpdir / "out.iso"
                    src.write_bytes(image)
                    dst.unlink(missing_ok=True)
                    run_tool(tool, mode, key_type, src, dst)
                    result = dst.read_bytes()
                    if len(result) != len(image):
                        sys.exit(f"{name} {mode}: output is {len(result)} bytes, input {len(image)}")
                    label = f"{name} ({mode})" if key_type != "key" else f"{name} ({mode}, disc key)"
                    rows.append(
                        "\t".join(
                            [label, ",".join(map(str, regions)), str(seed), marker_name, mode, keycol, sha(image), sha(result)]
                        )
                    )
    return rows


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--tool", type=Path, default=REPO / "build" / "Release" / "PS3Dec")
    ap.add_argument("--check", action="store_true", help="do not write; fail if the file would change")
    args = ap.parse_args()
    if not args.tool.is_file():
        sys.exit(f"reference tool not found: {args.tool} (build it first, see the repository README)")

    text = "\n".join(generate(args.tool)) + "\n"
    if args.check:
        if not OUT.is_file() or OUT.read_text() != text:
            sys.exit(f"{OUT} is out of date")
        print(f"{OUT} is up to date")
        return
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text)
    print(f"wrote {len(text.splitlines()) - 1} vectors to {OUT}")


if __name__ == "__main__":
    main()
