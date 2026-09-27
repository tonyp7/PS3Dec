from __future__ import annotations

import asyncio
import contextlib
import logging
import os
import shutil
import time
import uuid
from collections import deque
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Literal

from . import catalog, keys
from .config import Settings
from .errors import ApiError

log = logging.getLogger("ps3dec.job")

TEMP_SUFFIX = ".ps3dec.part"
LOG_LINES = 50
THROUGHPUT_WINDOW_S = 5.0

State = Literal["running", "succeeded", "failed", "cancelled"]
Mode = Literal["decrypt", "encrypt"]


def human(n: int) -> str:
    size = float(n)
    for unit in ("B", "KiB", "MiB", "GiB"):
        if size < 1024 or unit == "GiB":
            return f"{size:.0f} {unit}" if unit == "B" else f"{size:.1f} {unit}"
        size /= 1024
    raise AssertionError  # pragma: no cover


@dataclass
class StartRequest:
    iso: str
    mode: str
    key: str | None = None
    overwrite: bool = False


@dataclass
class Plan:
    """A fully validated job, ready to launch."""

    iso_path: Path
    iso_name: str
    mode: Mode
    kind: str
    size: int
    d1: str | None
    final: Path
    temp: Path


@dataclass
class Job:
    id: str
    iso: str
    mode: Mode
    output: str
    bytes_total: int
    state: State = "running"
    bytes_done: int = 0
    throughput_bps: float | None = None
    eta_s: float | None = None
    error: str | None = None
    started: float = field(default_factory=time.monotonic)
    finished: float | None = None
    log: deque[str] = field(default_factory=lambda: deque(maxlen=LOG_LINES))
    cancel_requested: bool = False
    samples: deque[tuple[float, int]] = field(default_factory=deque)

    def to_dict(self) -> dict[str, Any]:
        end = self.finished if self.finished is not None else time.monotonic()
        return {
            "id": self.id,
            "state": self.state,
            "iso": self.iso,
            "mode": self.mode,
            "output": self.output,
            "bytes_done": self.bytes_done,
            "bytes_total": self.bytes_total,
            "fraction": min(1.0, self.bytes_done / self.bytes_total) if self.bytes_total else 0.0,
            "elapsed_s": round(end - self.started, 2),
            "throughput_bps": self.throughput_bps,
            "eta_s": self.eta_s,
            "log": list(self.log),
            "error": self.error,
        }


def sweep_stale(output_dir: Path) -> int:
    """Delete temp files left by an earlier run. Only our own suffix is touched."""
    removed = 0
    try:
        with os.scandir(output_dir) as it:
            for e in it:
                if e.name.endswith(TEMP_SUFFIX) and e.is_file(follow_symlinks=False):
                    try:
                        os.unlink(e.path)
                        removed += 1
                    except OSError as err:
                        log.warning("could not remove stale temp file %s: %s", e.name, err)
    except OSError:
        pass
    return removed


def validate_start(settings: Settings, req: StartRequest) -> Plan:
    """All checks that must pass before the tool is launched. Writes nothing that persists."""
    if req.mode not in ("decrypt", "encrypt"):
        raise ApiError(422, "bad_mode", "mode must be 'decrypt' or 'encrypt'")
    mode: Mode = req.mode  # type: ignore[assignment]

    iso_path = catalog.resolve_iso(settings.iso_dir, req.iso)
    info = catalog.classify(iso_path)
    if info.kind == "invalid":
        raise ApiError(422, "invalid_image", f"{info.name} cannot be used: {info.reason}")
    if info.kind == "3k3y-decrypted" and mode == "decrypt":
        raise ApiError(422, "mode_conflict", f"{info.name} is a 3k3y image that is already decrypted")
    if info.kind == "3k3y-encrypted" and mode == "encrypt":
        raise ApiError(422, "mode_conflict", f"{info.name} is a 3k3y image that is already encrypted")

    d1 = keys.key_for_job(info.kind, settings.keys_dir, req.key)

    out_dir = settings.output_dir
    if not out_dir.is_dir():
        raise ApiError(500, "output_not_writable", f"output folder {out_dir} does not exist or is not mounted")
    final = out_dir / info.name
    if final.exists() and final.samefile(iso_path):
        raise ApiError(422, "output_same_as_input", "the output would overwrite the input image; use separate folders")
    if final.exists() and not req.overwrite:
        raise ApiError(409, "output_exists", f"{info.name} already exists in the output folder")
    if final.is_dir():
        raise ApiError(409, "output_exists", f"{info.name} in the output folder is a directory")

    temp = out_dir / (info.name + TEMP_SUFFIX)
    try:
        with temp.open("wb"):
            pass
        temp.unlink()
    except OSError as e:
        raise ApiError(500, "output_not_writable", f"output folder {out_dir} is not writable: {e.strerror or e}") from None

    free = shutil.disk_usage(out_dir).free
    if free < info.size:
        raise ApiError(
            507,
            "insufficient_space",
            f"not enough free space in the output folder: need {human(info.size)}, have {human(free)}",
        )
    return Plan(iso_path, info.name, mode, info.kind, info.size, d1, final, temp)


class JobManager:
    """Holds the single job slot. All state changes happen on the event loop thread."""

    def __init__(self, settings: Settings, *, poll_interval: float = 1.0, kill_grace: float = 3.0):
        self.settings = settings
        self.poll_interval = poll_interval
        self.kill_grace = kill_grace
        self._job: Job | None = None
        self._proc: asyncio.subprocess.Process | None = None
        self._task: asyncio.Task[None] | None = None

    # -- queries ---------------------------------------------------------------------------

    @property
    def running(self) -> bool:
        return self._job is not None and self._job.state == "running"

    def current(self) -> dict[str, Any] | None:
        return self._job.to_dict() if self._job else None

    # -- commands --------------------------------------------------------------------------

    async def start(self, req: StartRequest) -> dict[str, Any]:
        if self.running:
            raise ApiError(409, "job_running", "another job is already running")
        plan = await asyncio.to_thread(validate_start, self.settings, req)
        if self.running:  # another request won the race while we were validating
            raise ApiError(409, "job_running", "another job is already running")

        previous = self._job
        job = Job(id=uuid.uuid4().hex[:12], iso=plan.iso_name, mode=plan.mode, output=plan.iso_name, bytes_total=plan.size)
        self._job = job  # claim the slot before any await

        letter = "d" if plan.mode == "decrypt" else "e"
        type_args = ["3k3y"] if plan.d1 is None else ["d1", plan.d1]
        argv = [
            str(self.settings.tool),
            letter,
            *type_args,
            os.path.abspath(plan.iso_path),
            os.path.abspath(plan.temp),
        ]
        try:
            proc = await asyncio.create_subprocess_exec(
                *argv,
                stdin=asyncio.subprocess.DEVNULL,
                stdout=asyncio.subprocess.DEVNULL,  # the tool prints the derived key here
                stderr=asyncio.subprocess.PIPE,
            )
        except OSError as e:
            self._job = previous
            log.error("could not launch %s: %s", self.settings.tool, e.strerror or e)
            raise ApiError(500, "tool_unavailable", f"could not launch the PS3Dec tool: {e.strerror or e}") from None

        self._proc = proc
        log.info("job %s started: %s %s (%d bytes)", job.id, plan.mode, plan.iso_name, plan.size)
        self._task = asyncio.create_task(self._run(job, proc, plan))
        return job.to_dict()

    async def cancel(self) -> None:
        """Stop the running job (no-op when idle) and wait until its state has settled."""
        job, proc, task = self._job, self._proc, self._task
        if job is None or job.state != "running" or task is None:
            return
        job.cancel_requested = True
        if proc is not None and proc.returncode is None:
            with contextlib.suppress(ProcessLookupError):
                proc.terminate()
            try:
                await asyncio.wait_for(asyncio.shield(task), self.kill_grace)
            except TimeoutError:
                with contextlib.suppress(ProcessLookupError):
                    proc.kill()
        await task

    async def shutdown(self) -> None:
        await self.cancel()

    # -- runner ----------------------------------------------------------------------------

    async def _run(self, job: Job, proc: asyncio.subprocess.Process, plan: Plan) -> None:
        sampler = asyncio.create_task(self._sample_loop(job, plan.temp))
        try:
            reader = asyncio.create_task(self._read_stderr(job, proc, plan.d1))
            rc = await proc.wait()
            await reader
            self._finish(job, rc, plan)
        except Exception as e:  # never leave the slot stuck in "running"
            log.exception("job %s crashed", job.id)
            self._settle(job, "failed", f"internal error: {e}", plan.temp)
        finally:
            sampler.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await sampler
            self._proc = None

    def _finish(self, job: Job, rc: int, plan: Plan) -> None:
        if job.cancel_requested:
            self._settle(job, "cancelled", None, plan.temp)
            return
        if rc != 0:
            if rc < 0:
                reason = f"the tool was terminated by signal {-rc}"
            else:
                errors = [line for line in job.log if line.startswith("ERROR")]
                reason = (errors or list(job.log) or [f"the tool exited with status {rc}"])[-1]
            self._settle(job, "failed", reason, plan.temp)
            return
        try:
            size = plan.temp.stat().st_size
        except OSError as e:
            self._settle(job, "failed", f"output file is missing: {e.strerror or e}", plan.temp)
            return
        if size != plan.size:
            self._settle(job, "failed", f"output is {size} bytes but the image is {plan.size} bytes", plan.temp)
            return
        try:
            os.replace(plan.temp, plan.final)
        except OSError as e:
            self._settle(job, "failed", f"could not move the output into place: {e.strerror or e}", plan.temp)
            return
        job.bytes_done = job.bytes_total
        job.eta_s = 0.0
        self._settle(job, "succeeded", None, None)

    def _settle(self, job: Job, state: State, error: str | None, temp: Path | None) -> None:
        if temp is not None:
            with contextlib.suppress(OSError):
                temp.unlink()
        job.state = state
        job.error = error
        job.finished = time.monotonic()
        log.info("job %s %s%s", job.id, state, f": {error}" if error else "")

    async def _read_stderr(self, job: Job, proc: asyncio.subprocess.Process, d1: str | None) -> None:
        assert proc.stderr is not None
        async for raw in proc.stderr:
            line = raw.decode("utf-8", "replace").strip()
            if not line:
                continue
            lowered = line.lower()
            if "decryption key" in lowered or (d1 is not None and d1.lower() in lowered):
                continue  # never relay key material
            job.log.append(line)

    async def _sample_loop(self, job: Job, temp: Path) -> None:
        while True:
            self._sample(job, temp)
            await asyncio.sleep(self.poll_interval)

    def _sample(self, job: Job, temp: Path) -> None:
        try:
            done = temp.stat().st_size
        except OSError:
            return
        now = time.monotonic()
        job.bytes_done = min(done, job.bytes_total)
        job.samples.append((now, done))
        while len(job.samples) > 2 and now - job.samples[0][0] > THROUGHPUT_WINDOW_S:
            job.samples.popleft()
        (t0, b0), (t1, b1) = job.samples[0], job.samples[-1]
        if t1 > t0 and b1 > b0:
            job.throughput_bps = (b1 - b0) / (t1 - t0)
            job.eta_s = round((job.bytes_total - job.bytes_done) / job.throughput_bps, 1)
