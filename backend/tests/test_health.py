from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app


def test_health(tmp_path):
    settings = Settings.from_env({"PS3DEC_STATIC_DIR": str(tmp_path / "none")})
    client = TestClient(create_app(settings))
    r = client.get("/api/health")
    assert r.status_code == 200
    assert r.json() == {"status": "ok"}
