import logging

import uvicorn

from .config import Settings
from .main import create_app


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(levelname)s:     %(name)s - %(message)s")
    settings = Settings.from_env()
    # Job state lives in memory, so there must be exactly one worker.
    uvicorn.run(create_app(settings), host="0.0.0.0", port=settings.port, workers=1)


if __name__ == "__main__":
    main()
