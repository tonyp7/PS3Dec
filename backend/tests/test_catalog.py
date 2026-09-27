import os

import pytest

from app import catalog
from tests import synth


def write(path, data):
    path.write_bytes(data)
    return path


def test_mixed_folder_lists_only_top_level_iso_files(tmp_path):
    write(tmp_path / "A.iso", synth.build_decrypted())
    write(tmp_path / "b.ISO", synth.build_decrypted())
    write(tmp_path / "notes.txt", b"hi")
    (tmp_path / "sub").mkdir()
    write(tmp_path / "sub" / "c.iso", synth.build_decrypted())
    (tmp_path / "dir.iso").mkdir()  # a directory named *.iso is not a file
    available, items = catalog.list_isos(tmp_path)
    assert available
    assert [i.name for i in items] == ["A.iso", "b.ISO"]
    assert all(i.size == len(synth.build_decrypted()) for i in items)


def test_empty_and_missing_folder(tmp_path):
    assert catalog.list_isos(tmp_path) == (True, [])
    assert catalog.list_isos(tmp_path / "nope") == (False, [])
    write(tmp_path / "file", b"x")
    assert catalog.list_isos(tmp_path / "file") == (False, [])


def test_listing_reflects_changes_without_restart(tmp_path):
    assert catalog.list_isos(tmp_path)[1] == []
    write(tmp_path / "x.iso", synth.build_decrypted())
    assert [i.name for i in catalog.list_isos(tmp_path)[1]] == ["x.iso"]
    os.remove(tmp_path / "x.iso")
    assert catalog.list_isos(tmp_path)[1] == []


@pytest.mark.parametrize(
    "marker,kind",
    [
        (synth.ENCRYPTED_MARKER, "3k3y-encrypted"),
        (synth.DECRYPTED_MARKER, "3k3y-decrypted"),
        (b"encrypted 3K ISO", "3k3y-encrypted"),
        (b"decrypted 3K ISO", "3k3y-decrypted"),
        (None, "standard"),
    ],
)
def test_classification(tmp_path, marker, kind):
    info = catalog.classify(write(tmp_path / "g.iso", synth.build_decrypted(marker=marker)))
    assert info.kind == kind and info.valid and info.reason is None


def test_unrecognised_marker_is_invalid(tmp_path):
    info = catalog.classify(write(tmp_path / "g.iso", synth.build_decrypted(marker=b"Xecrypted 3K ISO")))
    assert info.kind == "invalid" and "marker" in info.reason


def test_truncated_file_is_invalid(tmp_path):
    info = catalog.classify(write(tmp_path / "g.iso", b"\x00" * 100))
    assert info.kind == "invalid" and "smaller" in info.reason
    assert info.size == 100


def test_consistent_region_table_passes(tmp_path):
    assert catalog.classify(write(tmp_path / "g.iso", synth.build_decrypted((3, 5, 7, 2, 9)))).valid


def test_size_mismatch_is_invalid(tmp_path):
    data = synth.build_decrypted((4, 6, 4))
    for name, blob in [("short.iso", data[: -synth.SECTOR]), ("long.iso", data + bytes(synth.SECTOR))]:
        info = catalog.classify(write(tmp_path / name, blob))
        assert info.kind == "invalid" and "file is" in info.reason


def test_non_ps3_iso_is_invalid(tmp_path):
    # An ISO9660-looking blob: zeros then "CD001" at sector 16, no region table.
    blob = bytearray(20 * synth.SECTOR)
    blob[16 * synth.SECTOR + 1 : 16 * synth.SECTOR + 6] = b"CD001"
    info = catalog.classify(write(tmp_path / "x.iso", bytes(blob)))
    assert info.kind == "invalid" and "region table" in info.reason
    junk = bytearray(os.urandom(6 * synth.SECTOR))
    junk[synth.MARKER_OFFSET] = 0
    assert catalog.classify(write(tmp_path / "y.iso", bytes(junk))).kind == "invalid"


def test_non_increasing_table_is_invalid(tmp_path):
    data = bytearray(synth.build_decrypted((4, 6, 4)))
    data[16:20] = (3).to_bytes(4, "big")  # second entry smaller than the first
    assert "increasing" in catalog.classify(write(tmp_path / "g.iso", bytes(data))).reason


def test_table_larger_than_header_is_invalid(tmp_path):
    data = bytearray(synth.build_decrypted((4, 6, 4)))
    data[0:4] = (5000).to_bytes(4, "big")
    assert "fit" in catalog.classify(write(tmp_path / "g.iso", bytes(data))).reason


@pytest.mark.parametrize(
    "name",
    ["../../etc/passwd", "sub/game.iso", "..", ".", "", "a\\b.iso", "x.iso\x00", "notiso.txt", "missing.iso", ".."],
)
def test_resolve_rejects_bad_names(tmp_path, name):
    (tmp_path / "sub").mkdir()
    write(tmp_path / "sub" / "game.iso", b"x")
    write(tmp_path / "notiso.txt", b"x")
    with pytest.raises(catalog.BadName):
        catalog.resolve_iso(tmp_path, name)


def test_resolve_accepts_listed_name(tmp_path):
    write(tmp_path / "Game.ISO", b"x")
    assert catalog.resolve_iso(tmp_path, "Game.ISO") == tmp_path / "Game.ISO"
