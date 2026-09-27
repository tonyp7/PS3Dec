import json

from tests import synth

HEX = synth.d1_hex()


def test_isos_and_keys_shapes_and_suggestions(client, dirs):
    (dirs["iso"] / "Game.iso").write_bytes(synth.build_decrypted())
    (dirs["iso"] / "Enc3k.iso").write_bytes(synth.build_decrypted(marker=synth.ENCRYPTED_MARKER))
    (dirs["iso"] / "broken.iso").write_bytes(b"\0" * 10)
    (dirs["keys"] / "game.dkey").write_text(HEX)
    (dirs["keys"] / "enc3k.dkey").write_text(HEX)
    (dirs["keys"] / "bad.dkey").write_text("zz")

    r = client.get("/api/isos")
    assert r.status_code == 200
    body = r.json()
    assert body["available"] is True and body["folder"] == str(dirs["iso"])
    by_name = {i["name"]: i for i in body["items"]}
    assert list(by_name) == ["broken.iso", "Enc3k.iso", "Game.iso"]
    assert by_name["Game.iso"]["kind"] == "standard"
    assert by_name["Game.iso"]["suggested_key"] == "game.dkey"
    assert by_name["Game.iso"]["valid"] is True
    assert by_name["Enc3k.iso"]["kind"] == "3k3y-encrypted"
    assert by_name["Enc3k.iso"]["suggested_key"] is None  # 3k3y needs no key
    assert by_name["broken.iso"]["valid"] is False and by_name["broken.iso"]["reason"]

    k = client.get("/api/keys").json()
    assert k["available"] is True
    assert [(i["name"], i["valid"]) for i in k["items"]] == [
        ("bad.dkey", False),
        ("enc3k.dkey", True),
        ("game.dkey", True),
    ]


def test_no_key_material_in_responses(client, dirs):
    (dirs["iso"] / "Game.iso").write_bytes(synth.build_decrypted())
    (dirs["keys"] / "game.dkey").write_text(HEX)
    for path in ("/api/isos", "/api/keys"):
        text = json.dumps(client.get(path).json()).upper()
        assert HEX not in text


def test_empty_and_unavailable_folders(client, dirs):
    assert client.get("/api/isos").json()["items"] == []
    assert client.get("/api/keys").json()["items"] == []
    dirs["iso"].rmdir()
    dirs["keys"].rmdir()
    assert client.get("/api/isos").json()["available"] is False
    assert client.get("/api/keys").json()["available"] is False


def test_listing_picks_up_new_files(client, dirs):
    assert client.get("/api/isos").json()["items"] == []
    (dirs["iso"] / "New.iso").write_bytes(synth.build_decrypted())
    assert [i["name"] for i in client.get("/api/isos").json()["items"]] == ["New.iso"]
