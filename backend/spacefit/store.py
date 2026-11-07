import json
import sqlite3
import threading
from datetime import datetime, timezone
from pathlib import Path
from uuid import uuid4


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


class JobStore:
    """Transactional state on disk. One service process owns the reconstruction worker."""
    def __init__(self, root: Path):
        self.root = root
        root.mkdir(parents=True, exist_ok=True)
        self.path = root / "jobs.sqlite3"
        self.lock = threading.RLock()
        with self.connect() as db:
            db.execute("CREATE TABLE IF NOT EXISTS jobs (id TEXT PRIMARY KEY, body TEXT NOT NULL)")

    def connect(self):
        return sqlite3.connect(self.path, timeout=30)

    def create(self, metadata: dict, files: list[str], status="queued") -> dict:
        job = dict(id=str(uuid4()), status=status, stage=status, progress=0,
                   created_at=now(), updated_at=now(), metadata=metadata, files=files,
                   error=None, metrics={}, calibration=None, result=None)
        with self.lock, self.connect() as db:
            db.execute("INSERT INTO jobs VALUES (?, ?)", (job["id"], json.dumps(job)))
        return job

    def get(self, job_id: str) -> dict | None:
        with self.connect() as db:
            row = db.execute("SELECT body FROM jobs WHERE id=?", (job_id,)).fetchone()
        return json.loads(row[0]) if row else None

    def all(self) -> list[dict]:
        with self.connect() as db:
            return [json.loads(row[0]) for row in db.execute("SELECT body FROM jobs ORDER BY rowid DESC")]

    def update(self, job_id: str, **values) -> dict:
        with self.lock, self.connect() as db:
            row = db.execute("SELECT body FROM jobs WHERE id=?", (job_id,)).fetchone()
            if not row:
                raise KeyError(job_id)
            job = json.loads(row[0])
            job.update(values, updated_at=now())
            db.execute("UPDATE jobs SET body=? WHERE id=?", (json.dumps(job), job_id))
            return job

    def transition(self, job_id: str, allowed: set[str], **values) -> dict | None:
        with self.lock:
            job = self.get(job_id)
            if job is None or job["status"] not in allowed:
                return None
            return self.update(job_id, **values)

    def recover(self):
        for job in self.all():
            if job["status"] in {"processing", "calibrating", "uploading"}:
                self.update(job["id"], status="failed", stage="interrupted", error=
                            "Server stopped during processing. Retry the scan or its calibration.")
