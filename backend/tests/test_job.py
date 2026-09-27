import logging
import os
import shutil
import time

import pytest

from tests import synth
from tests.conftest import wait_job
from tests.fake_tool import FAKE_KEY_LINE

HEX = synth.d1_hex()
SLOW = {"FAKE_TOOL_CHUNK": "2048", "FAKE_TOOL_DELAY": "0.05"}


@pytest.fixture
def env(monkeypatch):
    def set_(**kw):
        for k, v in kw.items():
            monkeypatch.setenv(k, str(v))

    return set_


@pytest.fixture
def std(dirs):
    """A standard image with a matching key."""
    data = synth.build_decrypted((4, 6, 4), seed=3)
    (dirs["iso"] / "Game.iso").write_bytes(data)
    (dirs["keys"] / "Game.dkey").write_text(HEX)
    return data


def post(client, **kw):
    body = {"iso": "Game.iso", "mode": "decrypt", "key": "Game.dkey", **kw}
    return client.post("/api/job", json=body)


def code(r):
    return r.json()["detail"]["code"]


def output_files(dirs):
    return sorted(p.name for p in dirs["output"].iterdir())


# --- 5.1 one job at a time -------------------------------------------------------------------


def test_second_start_while_running_is_rejected_and_first_unaffected(client, dirs, std, env):
    env(**SLOW)
    r = post(client)
    assert r.status_code == 202 and r.json()["state"] == "running"
    first_id = r.json()["id"]

    r2 = post(client, overwrite=True)
    assert r2.status_code == 409 and code(r2) == "job_running"

    job = wait_job(client)
    assert job["id"] == first_id and job["state"] == "succeeded"


def test_no_job_initially(client):
    assert client.get("/api/job").json() is None


# --- 5.2 start-time validation ---------------------------------------------------------------


@pytest.mark.parametrize(
    "kw,status,expected",
    [
        ({"mode": "sideways"}, 422, "bad_mode"),
        ({"iso": "../Game.iso"}, 422, "bad_name"),
        ({"iso": "missing.iso"}, 422, "bad_name"),
        ({"key": None}, 422, "key_required"),
        ({"key": "nope.dkey"}, 422, "bad_name"),
        ({"key": "../Game.dkey"}, 422, "bad_name"),
    ],
)
def test_rejections_write_nothing(client, dirs, std, kw, status, expected):
    r = post(client, **kw)
    assert (r.status_code, code(r)) == (status, expected)
    assert output_files(dirs) == []
    assert client.get("/api/job").json() is None


def test_missing_fields_use_the_standard_error_shape(client):
    r = client.post("/api/job", json={"mode": "decrypt"})
    assert r.status_code == 422 and code(r) == "bad_request"
    assert client.post("/api/job", content="nope").status_code == 422


def test_invalid_key_and_invalid_image(client, dirs, std):
    (dirs["keys"] / "bad.dkey").write_text("xyz")
    r = post(client, key="bad.dkey")
    assert (r.status_code, code(r)) == (422, "invalid_key")
    (dirs["iso"] / "junk.iso").write_bytes(b"\0" * 5000)
    r = post(client, iso="junk.iso")
    assert (r.status_code, code(r)) == (422, "invalid_image")
    assert "region table" in r.json()["detail"]["message"] or "smaller" in r.json()["detail"]["message"]
    assert output_files(dirs) == []


@pytest.mark.parametrize(
    "marker,mode,fragment",
    [
        (synth.DECRYPTED_MARKER, "decrypt", "already decrypted"),
        (synth.ENCRYPTED_MARKER, "encrypt", "already encrypted"),
    ],
)
def test_3k3y_mode_conflicts(client, dirs, marker, mode, fragment):
    (dirs["iso"] / "K.iso").write_bytes(synth.build_decrypted(marker=marker))
    r = client.post("/api/job", json={"iso": "K.iso", "mode": mode})
    assert (r.status_code, code(r)) == (422, "mode_conflict")
    assert fragment in r.json()["detail"]["message"]
    assert output_files(dirs) == []


def test_3k3y_needs_no_key_and_ignores_a_supplied_one(client, dirs, env):
    (dirs["iso"] / "K.iso").write_bytes(synth.build_decrypted(marker=synth.ENCRYPTED_MARKER))
    r = client.post("/api/job", json={"iso": "K.iso", "mode": "decrypt", "key": "does-not-exist.dkey"})
    assert r.status_code == 202
    assert wait_job(client)["state"] == "succeeded"


def test_existing_output_needs_overwrite(client, dirs, std):
    (dirs["output"] / "Game.iso").write_bytes(b"old")
    r = post(client)
    assert (r.status_code, code(r)) == (409, "output_exists")
    assert (dirs["output"] / "Game.iso").read_bytes() == b"old"
    r = post(client, overwrite=True)
    assert r.status_code == 202
    assert wait_job(client)["state"] == "succeeded"
    assert (dirs["output"] / "Game.iso").read_bytes() == std


def test_output_same_as_input_is_refused(dirs, tmp_path, tool_path, std):
    from fastapi.testclient import TestClient

    from app.config import Settings
    from app.main import create_app

    s = Settings.from_env(
        {
            "PS3DEC_ISO_DIR": str(dirs["iso"]),
            "PS3DEC_KEYS_DIR": str(dirs["keys"]),
            "PS3DEC_OUTPUT_DIR": str(dirs["iso"]),
            "PS3DEC_BIN": str(tool_path),
        }
    )
    with TestClient(create_app(s)) as c:
        r = post(c, overwrite=True)
    assert (r.status_code, code(r)) == (422, "output_same_as_input")
    assert (dirs["iso"] / "Game.iso").read_bytes() == std


def test_insufficient_space(client, dirs, std, monkeypatch):
    import app.job as jobmod

    monkeypatch.setattr(jobmod.shutil, "disk_usage", lambda p: shutil._ntuple_diskusage(100, 99, 1))
    r = post(client)
    assert (r.status_code, code(r)) == (507, "insufficient_space")
    assert "need" in r.json()["detail"]["message"]
    assert output_files(dirs) == []


@pytest.mark.skipif(os.geteuid() == 0, reason="root ignores directory permissions")
def test_output_not_writable(client, dirs, std):
    dirs["output"].chmod(0o500)
    try:
        r = post(client)
    finally:
        dirs["output"].chmod(0o700)
    assert (r.status_code, code(r)) == (500, "output_not_writable")
    assert "output" in r.json()["detail"]["message"]


def test_output_folder_missing(client, dirs, std):
    dirs["output"].rmdir()
    r = post(client)
    assert (r.status_code, code(r)) == (500, "output_not_writable")


def test_missing_tool(client, dirs, std, settings, tmp_path):
    from dataclasses import replace

    client.app.state.jobs.settings = replace(settings, tool=tmp_path / "no-such-tool")
    r = post(client)
    assert (r.status_code, code(r)) == (500, "tool_unavailable")
    assert client.get("/api/job").json() is None
    assert output_files(dirs) == []


# --- 5.3 subprocess launch -------------------------------------------------------------------


def test_launch_arguments_and_temp_name(client, dirs, std, env, tmp_path):
    argv_file = tmp_path / "argv.txt"
    env(FAKE_TOOL_ARGV_FILE=argv_file, **SLOW)
    assert post(client, mode="encrypt").status_code == 202
    wait_job(client, until=lambda j: argv_file.exists())
    args = argv_file.read_text().split("\n")
    assert args[:3] == ["e", "d1", HEX]
    assert args[3] == str(dirs["iso"] / "Game.iso")
    assert args[4] == str(dirs["output"] / "Game.iso.ps3dec.part")
    assert not args[4].endswith(".iso")
    wait_job(client)


def test_3k3y_launch_has_no_key_argument(client, dirs, env, tmp_path):
    argv_file = tmp_path / "argv.txt"
    (dirs["iso"] / "K.iso").write_bytes(synth.build_decrypted(marker=synth.ENCRYPTED_MARKER))
    env(FAKE_TOOL_ARGV_FILE=argv_file)
    client.post("/api/job", json={"iso": "K.iso", "mode": "decrypt"})
    wait_job(client)
    args = argv_file.read_text().splitlines()
    assert args[:2] == ["d", "3k3y"]
    assert len(args) == 4 and args[2].endswith("K.iso") and args[3].endswith("K.iso.ps3dec.part")


def test_key_line_never_reaches_log_or_app_logs(client, dirs, std, caplog):
    caplog.set_level(logging.DEBUG)
    post(client)
    job = wait_job(client)
    assert job["state"] == "succeeded"
    assert job["log"] == ["PS3Dec fake", "done"]
    assert "Decryption key" not in " ".join(job["log"])
    assert FAKE_KEY_LINE not in caplog.text and HEX not in caplog.text
    assert HEX not in str(job)


def test_stderr_lines_containing_key_are_filtered(client, dirs, std, monkeypatch, tmp_path):
    wrapper = tmp_path / "leaky.py"
    wrapper.write_text(
        "#!/usr/bin/env python3\nimport sys, shutil\n"
        f"print('using key {HEX.lower()}', file=sys.stderr)\n"
        "print('ordinary line', file=sys.stderr)\n"
        "shutil.copyfile(sys.argv[-2], sys.argv[-1])\n"
    )
    wrapper.chmod(0o755)
    from dataclasses import replace

    client.app.state.jobs.settings = replace(client.app.state.jobs.settings, tool=wrapper)
    post(client)
    assert wait_job(client)["log"] == ["ordinary line"]


# --- 5.4 progress ----------------------------------------------------------------------------


def test_progress_fraction_increases_and_eta_appears(client, dirs, std, env):
    env(**SLOW)
    post(client)
    seen = []
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        j = client.get("/api/job").json()
        seen.append(j)
        if j["state"] != "running":
            break
        time.sleep(0.05)
    running = [j for j in seen if j["state"] == "running"]
    fractions = [j["fraction"] for j in running]
    assert fractions == sorted(fractions) and fractions[-1] > fractions[0]
    assert any(j["eta_s"] is not None and j["throughput_bps"] for j in running)
    assert all(j["bytes_total"] == len(std) for j in seen)
    assert all(j["elapsed_s"] >= 0 for j in seen)
    assert seen[-1]["state"] == "succeeded" and seen[-1]["fraction"] == 1.0


def test_status_readable_by_late_client(client, dirs, std, env):
    env(**SLOW)
    post(client)
    from fastapi.testclient import TestClient

    late = TestClient(client.app)  # a "second tab": no lifespan of its own, same manager
    j = late.get("/api/job").json()
    assert j["state"] == "running" and j["iso"] == "Game.iso"
    wait_job(client)


# --- 5.5 completion --------------------------------------------------------------------------


def test_success_publishes_output_atomically(client, dirs, std):
    post(client)
    job = wait_job(client)
    assert job["state"] == "succeeded" and job["error"] is None and job["output"] == "Game.iso"
    assert output_files(dirs) == ["Game.iso"]
    assert (dirs["output"] / "Game.iso").stat().st_size == len(std)


def test_no_file_with_final_name_while_running(client, dirs, std, env):
    env(**SLOW)
    post(client)
    wait_job(client, until=lambda j: j["bytes_done"] > 0)
    names = output_files(dirs)
    assert names == ["Game.iso.ps3dec.part"]
    wait_job(client)


def test_tool_error_fails_and_leaves_nothing(client, dirs, std, env):
    env(FAKE_TOOL_MODE="fail", FAKE_TOOL_CHUNK=2048)
    post(client)
    job = wait_job(client)
    assert job["state"] == "failed"
    assert job["error"] == "ERROR: Failed to read from file"
    assert output_files(dirs) == []
    # and a new job is accepted afterwards
    env(FAKE_TOOL_MODE="ok")
    assert post(client).status_code == 202
    assert wait_job(client)["state"] == "succeeded"


def test_exit_zero_with_wrong_size_fails(client, dirs, std, env):
    env(FAKE_TOOL_MODE="short")
    post(client)
    job = wait_job(client)
    assert job["state"] == "failed" and "bytes" in job["error"]
    assert output_files(dirs) == []


@pytest.mark.parametrize("mode", ["fail", "short", "hang"])
def test_existing_file_survives_failed_or_cancelled_overwrite(client, dirs, std, env, mode):
    (dirs["output"] / "Game.iso").write_bytes(b"precious")
    env(FAKE_TOOL_MODE=mode, FAKE_TOOL_CHUNK=2048)
    assert post(client, overwrite=True).status_code == 202
    if mode == "hang":
        wait_job(client, until=lambda j: j["bytes_done"] > 0)
        client.delete("/api/job")
    else:
        assert wait_job(client)["state"] == "failed"
    assert (dirs["output"] / "Game.iso").read_bytes() == b"precious"
    assert output_files(dirs) == ["Game.iso"]


# --- 5.6 cancellation ------------------------------------------------------------------------


def test_cancel_mid_job(client, dirs, std, env):
    env(FAKE_TOOL_MODE="hang", FAKE_TOOL_CHUNK=2048)
    post(client)
    wait_job(client, until=lambda j: j["bytes_done"] > 0)
    assert client.delete("/api/job").status_code == 204
    job = client.get("/api/job").json()
    assert job["state"] == "cancelled" and job["error"] is None
    assert output_files(dirs) == []
    assert post(client).status_code == 202  # slot is free again (fake is in hang mode; cancel below)
    client.delete("/api/job")


def test_cancel_escalates_to_kill(client, dirs, std, env):
    env(FAKE_TOOL_MODE="stubborn", FAKE_TOOL_CHUNK=2048)
    post(client)
    wait_job(client, until=lambda j: j["bytes_done"] > 0)
    t0 = time.monotonic()
    client.delete("/api/job")
    assert time.monotonic() - t0 < 5
    assert client.get("/api/job").json()["state"] == "cancelled"
    assert output_files(dirs) == []


def test_cancel_when_idle_is_a_noop(client, dirs, std):
    assert client.delete("/api/job").status_code == 204
    assert client.get("/api/job").json() is None
    post(client)
    job = wait_job(client)
    assert client.delete("/api/job").status_code == 204
    assert client.get("/api/job").json()["state"] == job["state"] == "succeeded"


def test_shutdown_during_job_leaves_no_temp_and_no_process(dirs, std, settings, env):
    from fastapi.testclient import TestClient

    from app.main import create_app

    env(FAKE_TOOL_MODE="stubborn", FAKE_TOOL_CHUNK=2048)
    app = create_app(settings, poll_interval=0.05, kill_grace=0.5)
    with TestClient(app) as c:
        assert post(c).status_code == 202
        wait_job(c, until=lambda j: j["bytes_done"] > 0)
        proc = app.state.jobs._proc
        assert proc is not None
    assert output_files(dirs) == []
    assert proc.returncode is not None


# --- 5.7 startup sweep and retention ---------------------------------------------------------


def test_startup_sweep_removes_only_our_temp_files(dirs, settings):
    from fastapi.testclient import TestClient

    from app.main import create_app

    (dirs["output"] / "Old.iso.ps3dec.part").write_bytes(b"x")
    (dirs["output"] / "something.part").write_bytes(b"mine")
    (dirs["output"] / "Keep.iso").write_bytes(b"done")
    with TestClient(create_app(settings)):
        pass
    assert output_files(dirs) == ["Keep.iso", "something.part"]


def test_last_job_retained_until_next_start(client, dirs, std):
    post(client)
    first = wait_job(client)
    assert client.get("/api/job").json() == first  # stable after completion
    assert first["state"] == "succeeded" and first["elapsed_s"] >= 0
    assert post(client, overwrite=True).status_code == 202
    second = wait_job(client)
    assert second["id"] != first["id"]


def test_failed_job_also_retained(client, dirs, std, env):
    env(FAKE_TOOL_MODE="fail", FAKE_TOOL_CHUNK=2048)
    post(client)
    failed = wait_job(client)
    time.sleep(0.2)
    assert client.get("/api/job").json() == failed
