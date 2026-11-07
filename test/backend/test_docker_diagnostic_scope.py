"""Diagnostic placement/cache regressions; mock help is NOT native acceptance."""
from pathlib import Path
import re
import time
import pytest

from spacefit.config import Settings
from spacefit.docker_runtime import DockerConfig
from spacefit.native_runtime import NativeReadiness, TOOL_NAMES


def completed(checker):
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        state = checker.snapshot()
        if state["runtime"]["checked"]:
            return state
        time.sleep(.01)
    raise AssertionError("Owned diagnostic fixture did not finish")


@pytest.mark.parametrize("runtime", ["openmvs", "colmap"])
def test_docker_probes_share_only_new_empty_children_on_actual_data_drive(tmp_path, runtime):
    data = tmp_path / "isolated-data"
    data.mkdir()
    marker = data / "preserved-user-fixture.txt"
    marker.write_text("fixture not to mount", encoding="utf-8")
    config = DockerConfig(binary="fixture-docker", image="sha256:" + "a"*64)
    settings = Settings(data_dir=data, **({"openmvs_runtime":"docker", "docker":config} if runtime == "openmvs"
                                        else {"colmap_runtime":"docker", "colmap_docker":config}))
    settings.executables = lambda: {name: "fixture-" + name for name in TOOL_NAMES}
    directories = []
    def probe(name, path, log_dir, timeout):
        directories.append(log_dir)
        assert log_dir.parent.parent == data
        assert log_dir.name == name and log_dir.parent.name.startswith("spacefit-native-")
        assert not log_dir.exists() and not (log_dir / marker.name).exists()
        return dict(status="ready", reason="ok")
    checker = NativeReadiness(settings, probe=probe)
    try:
        assert completed(checker)["ready"] is True
        assert len(directories) == 7
        assert all(not path.parent.exists() for path in directories)
        assert list(data.iterdir()) == [marker]
        original = settings.native_identity()
        settings.data_dir = tmp_path / "changed-data-drive-fixture"
        assert settings.native_identity() != original
    finally:
        checker.close()


def test_doctor_wrapper_budget_covers_all_probe_and_cleanup_timeouts():
    source = (Path(__file__).resolve().parents[2] / "tools" / "Test-All.ps1").read_text(encoding="utf-8-sig")
    invocation = next(line for line in source.splitlines() if "backend/scripts/doctor.py" in line)
    timeout = int(re.search(r"-TimeoutSeconds\s+(\d+)", invocation).group(1))
    assert timeout > 7*60 + 6*10
