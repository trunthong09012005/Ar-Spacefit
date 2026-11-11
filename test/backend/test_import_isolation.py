import os
from pathlib import Path
import subprocess
import sys

import pytest


def test_importing_api_does_not_create_database_or_start_worker(tmp_path):
    data = tmp_path / "must-not-be-created"
    env = dict(os.environ, SPACEFIT_DATA_DIR=str(data))
    subprocess.run([sys.executable, "-B", "-c", "import spacefit.api"], check=True,
                   cwd=Path(__file__).resolve().parents[2] / "backend", env=env, timeout=20)
    assert not data.exists()


@pytest.mark.parametrize("initial_runtime", [None, "native", "docker"])
def test_doctor_tests_restore_runtime_environment(tmp_path, initial_runtime):
    """Run the actual doctor cases, then verify the next test sees original state."""
    env = dict(os.environ)
    for key in ("SPACEFIT_COLMAP_RUNTIME", "SPACEFIT_OPENMVS_RUNTIME"):
        env.pop(key, None)
    if initial_runtime is not None:
        env["SPACEFIT_COLMAP_RUNTIME"] = initial_runtime
    code = """
import os
import pytest
import sys
keys = ('SPACEFIT_COLMAP_RUNTIME', 'SPACEFIT_OPENMVS_RUNTIME')
before = {key: os.environ.get(key) for key in keys}
result = pytest.main([
    'test/backend/test_doctor.py::test_doctor_selects_verified_colmap_config_but_honors_explicit_native',
    '-q', '-p', 'no:cacheprovider', '--basetemp', sys.argv[1],
])
after = {key: os.environ.get(key) for key in keys}
assert before == after, f'Doctor tests leaked runtime state: {before} -> {after}'
raise SystemExit(result)
"""
    result = subprocess.run([
        sys.executable, "-B", "-c", code, str(tmp_path / "child-pytest"),
    ], cwd=Path(__file__).resolve().parents[2], env=env,
        capture_output=True, text=True, timeout=30)
    assert result.returncode == 0, result.stdout + result.stderr
