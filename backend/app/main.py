from __future__ import annotations

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from fastapi.staticfiles import StaticFiles

from .config import Settings
from .errors import ApiError
from .job import JobManager, sweep_stale
from .routes import router

log = logging.getLogger("ps3dec")


def create_app(
    settings: Settings | None = None, *, poll_interval: float = 1.0, kill_grace: float = 3.0
) -> FastAPI:
    settings = settings or Settings.from_env()
    jobs = JobManager(settings, poll_interval=poll_interval, kill_grace=kill_grace)

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        removed = sweep_stale(settings.output_dir)
        if removed:
            log.info("removed %d stale temp file(s) from %s", removed, settings.output_dir)
        yield
        await jobs.shutdown()

    app = FastAPI(title="PS3Dec", docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)
    app.state.settings = settings
    app.state.jobs = jobs

    @app.exception_handler(ApiError)
    async def api_error(_: Request, exc: ApiError) -> JSONResponse:
        return JSONResponse(status_code=exc.status, content={"detail": {"code": exc.code, "message": exc.message}})

    @app.exception_handler(RequestValidationError)
    async def bad_request(_: Request, exc: RequestValidationError) -> JSONResponse:
        first = exc.errors()[0] if exc.errors() else {}
        where = ".".join(str(p) for p in first.get("loc", ()) if p != "body")
        message = f"invalid request{': ' + where if where else ''}: {first.get('msg', 'bad input')}"
        return JSONResponse(status_code=422, content={"detail": {"code": "bad_request", "message": message}})

    app.include_router(router)
    # Mounted last so it never shadows /api. The directory is absent in development, where
    # the Vite dev server serves the frontend instead.
    if settings.static_dir.is_dir():
        app.mount("/", StaticFiles(directory=settings.static_dir, html=True), name="static")
    return app
