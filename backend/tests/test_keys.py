import pytest

from app import keys
from app.errors import ApiError

HEX = "00112233445566778899AABBCCDDEEFF"


def wr(folder, name, content):
    (folder / name).write_bytes(content if isinstance(content, bytes) else content.encode())


@pytest.mark.parametrize(
    "content",
    [HEX + "\n", HEX, "  " + HEX + "  \r\n", "0x" + HEX.lower(), "0X" + HEX, HEX.lower()],
)
def test_parse_valid(content):
    assert keys.parse_d1(content.encode()) == HEX


@pytest.mark.parametrize(
    "content,fragment",
    [
        (HEX[:-1], "found 31"),
        (HEX + "0", "found 33"),
        ("G" + HEX[1:], "not hexadecimal"),
        ("", "empty"),
        ("  \n", "empty"),
        ("0x", "empty"),
        (b"\xff\xfe" + HEX.encode(), "ASCII"),
        (b"A" * 1000, "larger"),
    ],
)
def test_parse_invalid(content, fragment):
    raw = content if isinstance(content, bytes) else content.encode()
    with pytest.raises(ValueError, match=fragment):
        keys.parse_d1(raw)


def test_listing_only_dkey_files_with_validity(tmp_path):
    wr(tmp_path, "Game.dkey", HEX)
    wr(tmp_path, "Other.DKEY", "nope")
    wr(tmp_path, "readme.txt", HEX)
    (tmp_path / "dir.dkey").mkdir()
    available, items = keys.list_keys(tmp_path)
    assert available
    assert [(k.name, k.valid) for k in items] == [("Game.dkey", True), ("Other.DKEY", False)]
    assert items[1].reason


def test_missing_and_empty_folder(tmp_path):
    assert keys.list_keys(tmp_path) == (True, [])
    assert keys.list_keys(tmp_path / "nope") == (False, [])


def test_suggest_matching_stem_case_insensitive(tmp_path):
    wr(tmp_path, "game.dkey", HEX)
    _, items = keys.list_keys(tmp_path)
    assert keys.suggest_key("Game.iso", items) == "game.dkey"
    assert keys.suggest_key("Game.ISO", items) == "game.dkey"


def test_suggest_prefers_exact_case_and_skips_invalid_and_missing(tmp_path):
    wr(tmp_path, "game.dkey", HEX)
    wr(tmp_path, "Game.dkey", HEX)
    wr(tmp_path, "bad.dkey", "zz")
    _, items = keys.list_keys(tmp_path)
    assert keys.suggest_key("Game.iso", items) == "Game.dkey"
    assert keys.suggest_key("bad.iso", items) is None  # only key with that stem is invalid
    assert keys.suggest_key("other.iso", items) is None


def test_key_for_job_3k3y_ignores_key(tmp_path):
    assert keys.key_for_job("3k3y-encrypted", tmp_path, None) is None
    assert keys.key_for_job("3k3y-decrypted", tmp_path, "whatever.dkey") is None


def test_key_for_job_standard_requires_valid_key(tmp_path):
    wr(tmp_path, "g.dkey", "0x" + HEX.lower())
    wr(tmp_path, "bad.dkey", "zz")
    assert keys.key_for_job("standard", tmp_path, "g.dkey") == HEX
    with pytest.raises(ApiError) as e:
        keys.key_for_job("standard", tmp_path, None)
    assert (e.value.status, e.value.code) == (422, "key_required")
    with pytest.raises(ApiError) as e:
        keys.key_for_job("standard", tmp_path, "bad.dkey")
    assert e.value.code == "invalid_key"
    with pytest.raises(ApiError) as e:
        keys.key_for_job("standard", tmp_path, "missing.dkey")
    assert e.value.code == "bad_name"
    with pytest.raises(ApiError) as e:
        keys.key_for_job("standard", tmp_path, "../g.dkey")
    assert e.value.code == "bad_name"
