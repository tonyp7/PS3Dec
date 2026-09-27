from pathlib import Path

from app.config import Settings


def test_defaults():
    s = Settings.from_env({})
    assert s.iso_dir == Path("/data/iso")
    assert s.keys_dir == Path("/data/keys")
    assert s.output_dir == Path("/data/output")
    assert s.tool == Path("/usr/local/bin/ps3dec")
    assert s.static_dir == Path("/app/static")
    assert s.port == 8000


def test_overrides():
    s = Settings.from_env(
        {
            "PS3DEC_ISO_DIR": "/a",
            "PS3DEC_KEYS_DIR": "/b",
            "PS3DEC_OUTPUT_DIR": "/c",
            "PS3DEC_BIN": "/d/tool",
            "PS3DEC_STATIC_DIR": "/e",
            "PORT": "9001",
        }
    )
    assert (s.iso_dir, s.keys_dir, s.output_dir) == (Path("/a"), Path("/b"), Path("/c"))
    assert s.tool == Path("/d/tool")
    assert s.static_dir == Path("/e")
    assert s.port == 9001
