import os
from pathlib import Path
import subprocess
import sys


def test_importing_api_does_not_create_database_or_start_worker(tmp_path):
    data = tmp_path / "must-not-be-created"
    env = dict(os.environ, SPACEFIT_DATA_DIR=str(data))
    subprocess.run([sys.executable, "-B", "-c", "import spacefit.api"], check=True,
                   cwd=Path(__file__).resolve().parents[2] / "backend", env=env, timeout=20)
    assert not data.exists()
