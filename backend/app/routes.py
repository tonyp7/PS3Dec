from __future__ import annotations

from dataclasses import asdict
from typing import Annotated, Any

from fastapi import APIRouter, Depends, Request, Response
from pydantic import BaseModel

from . import catalog, keys
from .config import Settings
from .job import JobManager, StartRequest

router = APIRouter(prefix="/api")


def get_settings(request: Request) -> Settings:
    return request.app.state.settings


SettingsDep = Annotated[Settings, Depends(get_settings)]


def get_jobs(request: Request) -> JobManager:
    return request.app.state.jobs


JobsDep = Annotated[JobManager, Depends(get_jobs)]


class StartBody(BaseModel):
    iso: str
    mode: str
    key: str | None = None
    overwrite: bool = False


@router.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@router.get("/isos")
def list_isos(settings: SettingsDep) -> dict[str, Any]:
    available, isos = catalog.list_isos(settings.iso_dir)
    _, key_items = keys.list_keys(settings.keys_dir)
    items = []
    for iso in isos:
        item = asdict(iso)
        item["valid"] = iso.valid
        item["suggested_key"] = keys.suggest_key(iso.name, key_items) if iso.valid and iso.kind == "standard" else None
        items.append(item)
    return {"available": available, "folder": str(settings.iso_dir), "items": items}


@router.get("/keys")
def list_keys(settings: SettingsDep) -> dict[str, Any]:
    available, items = keys.list_keys(settings.keys_dir)
    return {"available": available, "folder": str(settings.keys_dir), "items": [asdict(k) for k in items]}


@router.get("/job")
def get_job(jobs: JobsDep) -> dict[str, Any] | None:
    return jobs.current()


@router.post("/job", status_code=202)
async def start_job(body: StartBody, jobs: JobsDep) -> dict[str, Any]:
    return await jobs.start(StartRequest(iso=body.iso, mode=body.mode, key=body.key, overwrite=body.overwrite))


@router.delete("/job", status_code=204)
async def cancel_job(jobs: JobsDep) -> Response:
    await jobs.cancel()
    return Response(status_code=204)
