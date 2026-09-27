from __future__ import annotations

import os
import subprocess
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[2]


@pytest.fixture(scope="session")
def real_tool() -> Path:
    """The real PS3Dec binary; the test is skipped when it has not been built."""
    path = Path(os.environ.get("PS3DEC_BIN", REPO_ROOT / "build" / "Release" / "PS3Dec"))
    if not path.is_file():
        pytest.skip(f"PS3Dec binary not found at {path}; build it or set PS3DEC_BIN")
    return path


def run_tool(tool: Path, *args: str | Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run([str(tool), *map(str, args)], capture_output=True, text=True, timeout=300)


@pytest.fixture
def dirs(tmp_path):
    d = {name: tmp_path / name for name in ("iso", "keys", "output")}
    for p in d.values():
        p.mkdir()
    return d


FAKE_TOOL = Path(__file__).parent / "fake_tool.py"


@pytest.fixture
def tool_path() -> Path:
    """The tool the backend runs; job tests default to the fake one."""
    return FAKE_TOOL


@pytest.fixture
def settings(dirs, tmp_path, tool_path):
    from app.config import Settings

    return Settings.from_env(
        {
            "PS3DEC_ISO_DIR": str(dirs["iso"]),
            "PS3DEC_KEYS_DIR": str(dirs["keys"]),
            "PS3DEC_OUTPUT_DIR": str(dirs["output"]),
            "PS3DEC_BIN": str(tool_path),
            "PS3DEC_STATIC_DIR": str(tmp_path / "static"),
        }
    )


@pytest.fixture
def client(settings):
    from fastapi.testclient import TestClient

    from app.main import create_app

    with TestClient(create_app(settings, poll_interval=0.05, kill_grace=0.5)) as c:
        yield c


def wait_job(client, *, until=lambda j: j["state"] != "running", timeout=20.0):
    """Poll GET /api/job until ``until(job)`` holds; returns the job."""
    import time

    deadline = time.monotonic() + timeout
    job = None
    while time.monotonic() < deadline:
        job = client.get("/api/job").json()
        if job is not None and until(job):
            return job
        time.sleep(0.05)
    raise AssertionError(f"timed out waiting for job; last state: {job}")
