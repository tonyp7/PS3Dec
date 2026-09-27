"""End to end through the HTTP API with the real PS3Dec binary and synthetic images."""

import pytest

from tests import synth
from tests.conftest import wait_job

HEX = synth.d1_hex()


@pytest.fixture
def tool_path(real_tool):
    return real_tool


def run(client, **body):
    r = client.post("/api/job", json=body)
    assert r.status_code == 202, r.text
    return wait_job(client, timeout=120)


@pytest.mark.parametrize("regions", [(4, 6, 4), (16, 3 * 4096 + 12, 16, 4096, 9)], ids=["small", "pipelined"])
def test_standard_encrypt_then_decrypt_matches_reference(client, dirs, regions):
    plain = synth.build_decrypted(regions, seed=5)
    (dirs["iso"] / "Plain.iso").write_bytes(plain)
    (dirs["keys"] / "Plain.dkey").write_text(HEX + "\n")

    job = run(client, iso="Plain.iso", mode="encrypt", key="Plain.dkey")
    assert job["state"] == "succeeded", job
    encrypted = (dirs["output"] / "Plain.iso").read_bytes()
    assert encrypted == synth.encrypt(plain)

    # feed the encrypted result back in as a new input and decrypt it
    (dirs["iso"] / "Enc.iso").write_bytes(encrypted)
    (dirs["keys"] / "Enc.dkey").write_text("0x" + HEX.lower())
    job = run(client, iso="Enc.iso", mode="decrypt", key="Enc.dkey")
    assert job["state"] == "succeeded", job
    assert (dirs["output"] / "Enc.iso").read_bytes() == plain
    assert [p.name for p in dirs["output"].iterdir() if p.name.endswith(".part")] == []


def test_3k3y_decrypt_then_encrypt(client, dirs):
    plain = synth.build_decrypted((4, 6, 4), seed=6, marker=synth.DECRYPTED_MARKER)
    enc = synth.encrypt(plain, three_k3y=True)
    (dirs["iso"] / "K.iso").write_bytes(enc)

    job = run(client, iso="K.iso", mode="decrypt")
    assert job["state"] == "succeeded", job
    assert (dirs["output"] / "K.iso").read_bytes() == plain

    (dirs["iso"] / "K2.iso").write_bytes(plain)
    job = run(client, iso="K2.iso", mode="encrypt")
    assert job["state"] == "succeeded", job
    assert (dirs["output"] / "K2.iso").read_bytes() == enc


def test_wrong_key_still_completes_but_differs(client, dirs):
    """The tool cannot detect a wrong key; the output just isn't the plaintext."""
    plain = synth.build_decrypted((4, 6, 4), seed=7)
    (dirs["iso"] / "E.iso").write_bytes(synth.encrypt(plain))
    (dirs["keys"] / "wrong.dkey").write_text("FF" * 16)
    job = run(client, iso="E.iso", mode="decrypt", key="wrong.dkey")
    assert job["state"] == "succeeded"
    assert (dirs["output"] / "E.iso").read_bytes() != plain
