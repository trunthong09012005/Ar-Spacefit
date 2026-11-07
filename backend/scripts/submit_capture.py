"""Submit a local capture to the already-running localhost backend; never print its token."""
import argparse
import json
from pathlib import Path
import sys
from uuid import UUID, uuid4

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from doctor import load_config
from spacefit.config import Settings
from spacefit.models import ScanMetadata


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("capture", type=Path)
    parser.add_argument("--name", required=True)
    parser.add_argument("--category", default="CHAIR")
    parser.add_argument("--reference-cm", required=True, type=float)
    parser.add_argument("--reference-axis", default="width")
    parser.add_argument("--request-id", type=UUID, default=None, help="Reuse for safe retry after an interrupted upload")
    parser.add_argument("--receipt", type=Path, required=True, help="New JSON receipt file")
    args = parser.parse_args()
    if not args.capture.is_file():
        parser.error("Capture file not found")
    if args.receipt.exists():
        parser.error("Receipt already exists; do not submit the same capture accidentally")
    load_config()
    settings = Settings()
    if len(settings.token) < 16:
        parser.error("Configure the pairing token in backend/.env first")
    metadata = ScanMetadata(name=args.name, category=args.category, reference_axis=args.reference_axis,
                            reference_cm=args.reference_cm)
    request_id = str(args.request_id or uuid4())
    args.receipt.parent.mkdir(parents=True, exist_ok=True)
    receipt = dict(request_id=request_id, source=str(args.capture.resolve()), metadata=metadata.model_dump())
    # Persist the id BEFORE submitting, so a network interruption can be retried idempotently.
    args.receipt.write_text(json.dumps(receipt, ensure_ascii=False, indent=2), encoding="utf-8")
    with httpx.Client(base_url="http://127.0.0.1:8000", trust_env=False, timeout=120,
                      headers={"Authorization": "Bearer " + settings.token}) as client:
        capabilities = client.get("/capabilities")
        capabilities.raise_for_status()
        if not capabilities.json()["ready"]:
            raise RuntimeError("Backend tools are not ready")
        with args.capture.open("rb") as source:
            response = client.post("/jobs", headers={"X-Request-ID": request_id},
                                   data={"metadata": metadata.model_dump_json()},
                                   files={"files": (args.capture.name, source, "application/octet-stream")})
        response.raise_for_status()
        receipt["job"] = response.json()
        args.receipt.write_text(json.dumps(receipt, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(receipt["job"], ensure_ascii=True))


if __name__ == "__main__":
    main()
