"""Install official portable reconstruction tools under .tools (no registry/PATH changes)."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]
TOOLS = ROOT / ".tools"
PACKAGES = [
    ("colmap-4.2.0", "https://github.com/colmap/colmap/releases/download/4.2.0/colmap-x64-windows-nocuda.zip", "7dd1e72f9632199b7f6d80ab6f545fa8d2a2ea023e4802cab3a4624e309f18a1"),
    ("openmvs-2.4.0", "https://github.com/cdcseacave/openMVS/releases/download/v2.4.0/OpenMVS_Windows_x64.zip", "0c31660c15c9ebc4c106873cf67564d9570d404aef7a6403451da1b6178b2167"),
]


def download(url, destination, digest):
    if destination.exists() and hashlib.file_digest(destination.open("rb"), "sha256").hexdigest() == digest:
        return
    temporary = destination.with_suffix(".download")
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "AR-SpaceFit-Setup/1.0"}), timeout=120) as response:
        with temporary.open("wb") as output:
            shutil.copyfileobj(response, output)
    with temporary.open("rb") as file:
        actual = hashlib.file_digest(file, "sha256").hexdigest()
    if actual != digest:
        raise RuntimeError(f"SHA256 mismatch for {destination.name}; archive was not extracted")
    temporary.replace(destination)


def unpack(archive, target):
    target.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as bundle:
        for info in bundle.infolist():
            if not (target / info.filename).resolve().is_relative_to(target.resolve()):
                raise ValueError("Unsafe path inside archive")
        bundle.extractall(target)


def configure():
    config = ROOT / "backend" / ".env"
    if config.exists():
        print("Existing backend/.env preserved. Use the detected paths below if needed.")
        return
    def find(name):
        return next((str(path.resolve()) for path in TOOLS.rglob(name)), "")
    colmap, mvs, blender = find("colmap.exe"), find("DensifyPointCloud.exe"), find("blender.exe")
    lines = ["SPACEFIT_TOKEN=" + secrets.token_hex(24), "SPACEFIT_HOST=127.0.0.1", "SPACEFIT_PORT=8000",
             "COLMAP_BIN=" + (colmap or "colmap"), "OPENMVS_BIN_DIR=" + (str(Path(mvs).parent) if mvs else ""),
             "BLENDER_BIN=" + (blender or "blender"), "SPACEFIT_USE_GPU=0", "SPACEFIT_THREADS=4"]
    config.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("Created backend/.env with a private random pairing token; open that file to pair Android.")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--blender-url")
    parser.add_argument("--blender-sha256")
    args = parser.parse_args()
    if os.name != "nt":
        raise SystemExit("This portable installer is for Windows x64.")
    TOOLS.mkdir(exist_ok=True)
    packages = PACKAGES.copy()
    if args.blender_url:
        if not args.blender_sha256 or len(args.blender_sha256) != 64:
            raise SystemExit("Supply Blender's official SHA256 checksum")
        packages.append(("blender", args.blender_url, args.blender_sha256))
    for name, url, digest in packages:
        archive = TOOLS / (name + ".zip")
        target = TOOLS / name
        if not (target / ".installed").exists():
            print(f"Downloading {name} from official distribution...", flush=True)
            download(url, archive, digest)
            unpack(archive, target)
            (target / ".installed").write_text(digest, encoding="ascii")
            print(f"Installed {name}; SHA256 verified.", flush=True)
    configure()
    for name in ("colmap.exe", "DensifyPointCloud.exe", "blender.exe"):
        print(name + ": " + next((str(path) for path in TOOLS.rglob(name)), "not installed"))


if __name__ == "__main__":
    main()
