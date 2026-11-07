"""Private QA workspace readable by the Windows user after Docker creates files.

pytest uses mode 0700, which creates an OWNER RIGHTS ACL on recent Windows
Python. Docker/DrvFS can create children with a different Windows owner. Grant
only the current user's SID Modify on a NEW fixture child, never on the pytest
root, the repository, or production data. This does not alter native assertions.
"""
import csv
import os
from pathlib import Path
import re
import subprocess


def _windows():
    return os.name == "nt"


def _run(argv):
    return subprocess.run(argv, capture_output=True, text=True, timeout=15,
                          creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))


def new_native_workspace(parent: Path) -> Path:
    parent = Path(parent)
    if parent.is_symlink() or not parent.is_dir():
        raise ValueError("Native QA parent must be an existing ordinary directory")
    workspace = parent / "native-interop"
    # No exist_ok: never modify permissions on an existing path or symlink.
    workspace.mkdir()
    if not _windows():
        return workspace
    identity = _run(["whoami.exe", "/user", "/fo", "csv", "/nh"])
    if identity.returncode:
        raise RuntimeError("Cannot identify Windows QA user")
    rows = list(csv.reader(identity.stdout.splitlines()))
    if len(rows) != 1 or len(rows[0]) != 2 or not re.fullmatch(r"S-1-(?:\d+-)+\d+", rows[0][1]):
        raise RuntimeError("Invalid Windows QA user SID")
    sid = rows[0][1]
    result = _run(["icacls.exe", str(workspace), "/grant", f"*{sid}:(OI)(CI)M"])
    if result.returncode:
        raise RuntimeError("Cannot prepare Windows/Docker fixture permissions")
    return workspace
