"""Runs the real PS3Dec binary against synthetic images.

The reference output comes from an independent AES-CBC implementation, so this also
checks the tool's PSA-based crypto port and not only that encrypt/decrypt invert each other.
Block size in the tool is 4096 sectors; the region sets below hit its serial (<3 blocks),
pipelined, exact-multiple and partial-last-block code paths.
"""

import pytest

from tests import synth
from tests.conftest import run_tool

REGION_SETS = {
    "tiny": (4, 6, 4),
    "many-small": (3, 5, 7, 2, 9),
    "two-blocks-exact": (16, 8192, 9),
    "three-blocks-exact": (16, 3 * 4096, 9),
    "pipelined-partial": (16, 3 * 4096 + 12, 16, 2 * 4096, 9),
    "plain-multi-block": (2 * 4096 + 5, 40, 4096 + 3),
}


@pytest.mark.parametrize("regions", REGION_SETS.values(), ids=REGION_SETS.keys())
def test_standard_image_roundtrip_matches_reference(tmp_path, real_tool, regions):
    plain = synth.build_decrypted(regions, seed=1)
    enc_ref = synth.encrypt(plain)
    src, enc, dec = tmp_path / "plain.iso", tmp_path / "enc.iso", tmp_path / "dec.iso"
    src.write_bytes(plain)

    r = run_tool(real_tool, "e", "d1", synth.d1_hex(), src, enc)
    assert r.returncode == 0, r.stderr
    assert enc.read_bytes() == enc_ref

    r = run_tool(real_tool, "d", "d1", synth.d1_hex(), enc, dec)
    assert r.returncode == 0, r.stderr
    assert dec.read_bytes() == plain


@pytest.mark.parametrize("regions", [(4, 6, 4), (16, 3 * 4096 + 12, 9)], ids=["tiny", "pipelined"])
def test_3k3y_roundtrip_flips_marker(tmp_path, real_tool, regions):
    plain = synth.build_decrypted(regions, seed=2, marker=synth.DECRYPTED_MARKER)
    enc_ref = synth.encrypt(plain, three_k3y=True)
    src, enc, dec = tmp_path / "plain.iso", tmp_path / "enc.iso", tmp_path / "dec.iso"
    src.write_bytes(plain)

    r = run_tool(real_tool, "e", "3k3y", src, enc)
    assert r.returncode == 0, r.stderr
    data = enc.read_bytes()
    assert data[synth.MARKER_OFFSET : synth.MARKER_OFFSET + 16] == synth.ENCRYPTED_MARKER
    assert data == enc_ref

    r = run_tool(real_tool, "d", "3k3y", enc, dec)
    assert r.returncode == 0, r.stderr
    assert dec.read_bytes() == plain


def test_3k3y_refuses_wrong_direction(tmp_path, real_tool):
    src = tmp_path / "plain.iso"
    src.write_bytes(synth.build_decrypted(marker=synth.DECRYPTED_MARKER))
    r = run_tool(real_tool, "d", "3k3y", src, tmp_path / "out.iso")
    assert r.returncode != 0
    assert "already" in r.stderr
