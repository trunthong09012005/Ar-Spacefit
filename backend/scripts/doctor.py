"""Read-only executable diagnostics. Does not print secrets from backend/.env."""
import argparse
import json
import os
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from spacefit.native_runtime import diagnose_tools


def load_config():
    path = ROOT / ".env"
    if path.exists():
        for line in path.read_text(encoding="utf-8-sig").splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                key, value = line.split("=", 1)
                os.environ[key.strip()] = value.strip()
    if not os.getenv("SPACEFIT_OPENMVS_RUNTIME") and (ROOT / "native-docker.json").is_file():
        os.environ["SPACEFIT_OPENMVS_RUNTIME"] = "docker"
    if not os.getenv("SPACEFIT_COLMAP_RUNTIME") and (ROOT / "native-colmap-docker.json").is_file():
        os.environ["SPACEFIT_COLMAP_RUNTIME"] = "docker"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--no-config", action="store_true", help="Do not load backend/.env (isolated tests)")
    parser.add_argument("--diagnostics-dir", type=Path, default=ROOT / "data" / "diagnostics")
    parser.add_argument("--timeout", type=float, default=30, help="Seconds per native executable")
    args = parser.parse_args(argv)
    if not 1 <= args.timeout <= 120:
        parser.error("--timeout must be between 1 and 120 seconds")
    if not args.no_config:
        load_config()
    from spacefit.config import Settings
    settings = Settings()
    print("Pairing token: " + ("configured" if len(settings.token) >= 16 else "missing"), flush=True)
    results = diagnose_tools(settings, args.diagnostics_dir, args.timeout)
    for result in results:
        print(json.dumps(result), flush=True)
    return 0 if results and all(result["status"] == "ready" for result in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
