from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app


def make(tmp_path, static):
    return Settings.from_env(
        {
            "PS3DEC_ISO_DIR": str(tmp_path / "iso"),
            "PS3DEC_KEYS_DIR": str(tmp_path / "keys"),
            "PS3DEC_OUTPUT_DIR": str(tmp_path / "out"),
            "PS3DEC_STATIC_DIR": str(static),
        }
    )


def test_serves_index_and_assets_without_shadowing_api(tmp_path):
    static = tmp_path / "static"
    (static / "assets").mkdir(parents=True)
    (static / "index.html").write_text("<!doctype html><title>PS3Dec</title>")
    (static / "assets" / "app.js").write_text("console.log(1)")
    with TestClient(create_app(make(tmp_path, static))) as c:
        r = c.get("/")
        assert r.status_code == 200 and "<title>PS3Dec</title>" in r.text
        assert c.get("/assets/app.js").text == "console.log(1)"
        assert c.get("/api/health").json() == {"status": "ok"}
        assert c.get("/api/job").json() is None
        assert c.get("/api/unknown").status_code == 404
        assert c.get("/nope.txt").status_code == 404


def test_missing_static_dir_is_tolerated(tmp_path):
    with TestClient(create_app(make(tmp_path, tmp_path / "absent"))) as c:
        assert c.get("/api/health").status_code == 200
        assert c.get("/").status_code == 404
