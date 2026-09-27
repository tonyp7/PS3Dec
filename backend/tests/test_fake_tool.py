import os
import subprocess
import sys
from pathlib import Path

import pytest

FAKE = Path(__file__).parent / "fake_tool.py"


def run(tmp_path, mode, **env):
    src, dst = tmp_path / "in.iso", tmp_path / "out.iso"
    src.write_bytes(os.urandom(4096))
    p = subprocess.run(
        [str(FAKE), "d", "d1", "00" * 16, str(src), str(dst)],
        capture_output=True,
        text=True,
        timeout=20,
        env={**os.environ, "FAKE_TOOL_MODE": mode, "FAKE_TOOL_CHUNK": "1024", **env},
    )
    return src, dst, p


def test_ok_copies_and_prints_key_on_stdout(tmp_path):
    src, dst, p = run(tmp_path, "ok")
    assert p.returncode == 0
    assert dst.read_bytes() == src.read_bytes()
    assert "Decryption key:" in p.stdout and "Decryption key" not in p.stderr


def test_short_exits_zero_with_truncated_output(tmp_path):
    src, dst, p = run(tmp_path, "short")
    assert p.returncode == 0
    assert dst.stat().st_size == src.stat().st_size - 1


def test_fail_exits_1_with_error_line(tmp_path):
    _, dst, p = run(tmp_path, "fail")
    assert p.returncode == 1
    assert p.stderr.strip().splitlines()[-1] == "ERROR: Failed to read from file"
    assert 0 < dst.stat().st_size < 4096


def test_hang_never_exits(tmp_path):
    src = tmp_path / "in.iso"
    src.write_bytes(os.urandom(4096))
    p = subprocess.Popen(
        [sys.executable, str(FAKE), "d", "d1", "00" * 16, str(src), str(tmp_path / "o")],
        env={**os.environ, "FAKE_TOOL_MODE": "hang", "FAKE_TOOL_CHUNK": "1024"},
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    try:
        with pytest.raises(subprocess.TimeoutExpired):
            p.wait(timeout=1.5)
    finally:
        p.kill()
        p.wait()
