from contextlib import asynccontextmanager
import hmac
import json
from pathlib import Path
import shutil
from uuid import UUID, uuid4
from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse
from pydantic import ValidationError
from starlette.datastructures import UploadFile
from .config import Settings
from .models import Calibration, ScanMetadata
from .native_runtime import NativeReadiness
from .pipeline import Pipeline
from .store import JobStore


class UploadTooLarge(Exception):
    pass


class BodyLimit:
    def __init__(self, app, limit):
        self.app, self.limit = app, limit

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        total, started = 0, False
        async def bounded_receive():
            nonlocal total
            message = await receive()
            total += len(message.get("body", b""))
            if total > self.limit:
                raise UploadTooLarge()
            return message
        async def tracked_send(message):
            nonlocal started
            if message["type"] == "http.response.start":
                started = True
            await send(message)
        try:
            await self.app(scope, bounded_receive, tracked_send)
        except UploadTooLarge:
            if not started:
                await JSONResponse({"detail": "Upload exceeds server size limit"}, 413)(scope, receive, send)


def create_app(settings: Settings | None = None, start_worker: bool = True):
    settings = settings or Settings()
    store = JobStore(settings.data_dir)
    native = NativeReadiness(settings)
    worker = Pipeline(store, settings, native=native)

    @asynccontextmanager
    async def lifespan(app):
        if start_worker:
            worker.start()
        try:
            yield
        finally:
            try:
                if start_worker:
                    worker.close()
            finally:
                native.close()

    app = FastAPI(title="AR-SpaceFit Reconstruction", version="1.0.0", lifespan=lifespan)
    app.add_middleware(BodyLimit, limit=settings.max_upload_bytes + 2 * 1024 * 1024)
    app.state.store, app.state.worker = store, worker
    app.state.native = native

    async def auth(request: Request):
        if not settings.token or len(settings.token) < 16:
            raise HTTPException(503, "Configure SPACEFIT_TOKEN with at least 16 characters on the laptop")
        value = request.headers.get("Authorization", "")
        if not hmac.compare_digest(value.encode(), ("Bearer " + settings.token).encode()):
            raise HTTPException(401, "Invalid pairing token")

    def job_or_404(job_id: str):
        try:
            if str(UUID(job_id)) != job_id:
                raise ValueError()
        except ValueError:
            raise HTTPException(404, "Scan not found")
        job = store.get(job_id)
        if job is None:
            raise HTTPException(404, "Scan not found")
        return job

    def public(job):
        return {key: value for key, value in job.items() if key not in {"files", "work_dir"}}

    @app.get("/health")
    def health():
        return {"service": "AR-SpaceFit", "api_version": 1, "status": "ok"}

    @app.get("/capabilities", dependencies=[Depends(auth)])
    def capabilities():
        return dict(**native.snapshot(), min_images=settings.min_images,
                    max_images=settings.max_images, max_upload_mb=settings.max_upload_bytes // 1024 // 1024)

    @app.get("/jobs", dependencies=[Depends(auth)])
    def jobs():
        return [public(job) for job in store.all()[:200]]

    @app.get("/jobs/{job_id}", dependencies=[Depends(auth)])
    def get_job(job_id: str):
        return public(job_or_404(job_id))

    @app.post("/jobs", status_code=202, dependencies=[Depends(auth)])
    async def upload(request: Request):
        content_length = request.headers.get("content-length")
        if content_length:
            try:
                length = int(content_length)
                if length < 0:
                    raise ValueError()
            except ValueError:
                raise HTTPException(400, "Invalid Content-Length")
            if length > settings.max_upload_bytes:
                raise HTTPException(413, "Upload exceeds size limit")
        client_id = request.headers.get("X-Request-ID", str(uuid4()))
        try:
            client_id = str(UUID(client_id))
        except ValueError:
            raise HTTPException(422, "X-Request-ID must be a UUID")
        existing = next((job for job in store.all() if job.get("client_id") == client_id), None)
        if existing:
            return public(existing)
        staging = settings.data_dir / ("incoming-" + str(uuid4()))
        staging.mkdir()
        try:
            async with request.form(max_files=settings.max_images, max_fields=2, max_part_size=32768) as form:
                try:
                    metadata = ScanMetadata.model_validate_json(str(form.get("metadata", "")))
                except ValidationError:
                    raise HTTPException(422, "Invalid name, category or reference measurement in metadata")
                files = form.getlist("files")
                if not files or not all(isinstance(file, UploadFile) for file in files):
                    raise HTTPException(422, "Send files as multipart field 'files'")
                suffixes = [Path(file.filename or "").suffix.lower() for file in files]
                is_video = len(files) == 1 and suffixes[0] in {".mp4", ".mov", ".mkv", ".webm"}
                if not is_video and (not settings.min_images <= len(files) <= settings.max_images or
                                     any(suffix not in {".jpg", ".jpeg", ".png"} for suffix in suffixes)):
                    raise HTTPException(422, f"Send one video or {settings.min_images}–{settings.max_images} JPEG/PNG images")
                names, total = [], 0
                for index, file in enumerate(files):
                    # Never use a client-supplied filename as a filesystem path.
                    name = f"{index:04d}{suffixes[index]}"
                    size = 0
                    with (staging / name).open("wb") as output:
                        while chunk := await file.read(1024 * 1024):
                            total += len(chunk)
                            size += len(chunk)
                            if total > settings.max_upload_bytes or (not is_video and size > 25 * 1024 * 1024):
                                raise HTTPException(413, "Upload too large (images limited to 25 MB each)")
                            output.write(chunk)
                    if size == 0:
                        raise HTTPException(422, "Empty file")
                    names.append(name)
                with store.lock:
                    existing = next((job for job in store.all() if job.get("client_id") == client_id), None)
                    if existing:
                        return public(existing)
                    job = store.create(metadata.model_dump(), names, status="uploading")
                    target = settings.data_dir / job["id"]
                    target.mkdir()
                    staging.rename(target / "uploads")
                    job = store.update(job["id"], client_id=client_id, status="queued", stage="queued")
                worker.wakeup.set()
                return public(job)
        finally:
            if staging.exists():
                shutil.rmtree(staging)

    @app.post("/jobs/{job_id}/cancel", dependencies=[Depends(auth)])
    def cancel(job_id: str):
        job_or_404(job_id)
        job = store.transition(job_id, {"queued", "processing", "calibration_queued", "calibrating"}, status="cancelled", stage="cancelled")
        if not job:
            raise HTTPException(409, "Scan is not running")
        return public(job)

    @app.post("/jobs/{job_id}/retry", dependencies=[Depends(auth)])
    def retry(job_id: str):
        previous = job_or_404(job_id)
        status = "calibration_queued" if previous.get("calibration") and previous.get("work_dir") else "queued"
        job = store.transition(job_id, {"failed", "cancelled"}, status=status, stage=status, progress=0, error=None)
        if not job:
            raise HTTPException(409, "Only failed/cancelled scans can be retried")
        worker.wakeup.set()
        return public(job)

    @app.post("/jobs/{job_id}/calibrate", dependencies=[Depends(auth)])
    def calibrate(job_id: str, calibration: Calibration):
        job_or_404(job_id)
        if not (settings.data_dir / job_id / "preview.glb").is_file():
            raise HTTPException(409, "Reconstructed preview is not available yet")
        job = store.transition(job_id, {"needs_review", "completed", "failed", "cancelled"}, status="calibration_queued", stage="calibration_queued",
                               progress=0, error=None, calibration=calibration.model_dump())
        if not job:
            raise HTTPException(409, "Scan must finish reconstruction before calibration")
        worker.wakeup.set()
        return public(job)

    @app.get("/jobs/{job_id}/artifacts/{kind}", dependencies=[Depends(auth)])
    def artifact(job_id: str, kind: str):
        job = job_or_404(job_id)
        root = settings.data_dir / job_id
        if kind == "model" and job["status"] == "completed":
            path, mime = root / "model.glb", "model/gltf-binary"
        elif kind == "preview" and job["status"] in {"needs_review", "completed", "failed", "cancelled", "calibration_queued", "calibrating"}:
            path, mime = root / "preview.glb", "model/gltf-binary"
        elif kind == "log" and job.get("work_dir"):
            path, mime = root / job["work_dir"] / "pipeline.log", "text/plain"
        elif kind == "thumbnail" and job.get("work_dir"):
            path, mime = root / job["work_dir"] / "images" / "0000.jpg", "image/jpeg"
        else:
            raise HTTPException(404, "Artifact not available")
        if not path.is_file():
            raise HTTPException(404, "Artifact not available yet")
        return FileResponse(path, media_type=mime, filename=path.name)

    return app


# Uvicorn uses --factory spacefit.api:create_app. Importing this module must not
# create/open the production database (especially when collecting tests).
