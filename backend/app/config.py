from __future__ import annotations

import os
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Settings:
    iso_dir: Path
    keys_dir: Path
    output_dir: Path
    tool: Path
    static_dir: Path
    port: int

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None) -> Settings:
        env = os.environ if env is None else env
        return cls(
            iso_dir=Path(env.get("PS3DEC_ISO_DIR", "/data/iso")),
            keys_dir=Path(env.get("PS3DEC_KEYS_DIR", "/data/keys")),
            output_dir=Path(env.get("PS3DEC_OUTPUT_DIR", "/data/output")),
            tool=Path(env.get("PS3DEC_BIN", "/usr/local/bin/ps3dec")),
            static_dir=Path(env.get("PS3DEC_STATIC_DIR", "/app/static")),
            port=int(env.get("PORT", "8000")),
        )
