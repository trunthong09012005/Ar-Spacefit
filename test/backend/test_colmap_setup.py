"""Installer contracts with explicit external-command mocks; never starts Docker."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess

import pytest

SOURCE = Path(__file__).resolve().parents[2] / "backend/scripts/setup_colmap_docker.py"
spec = importlib.util.spec_from_file_location("qa_colmap_installer", SOURCE)
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)
IMAGE = "sha256:" + "d"*64


@pytest.fixture
def fixture(tmp_path, monkeypatch):
    image_dir = tmp_path / "infra/docker/colmap"
    image_dir.mkdir(parents=True)
    shutil.copyfile(SOURCE.parents[2] / "infra/docker/colmap/Dockerfile", image_dir / "Dockerfile")
    (tmp_path / "backend").mkdir()
    (tmp_path / "backend/.env").write_text("FAKE-secret-must-never-enter-build-context", encoding="utf-8")
    state = {"scenario":"success", "calls":[], "cleanups":[]}
    monkeypatch.setattr(installer.shutil, "which", lambda _: "fixture-docker")
    def reply(argv, **kwargs):
        state["calls"].append(argv)
        scenario = state["scenario"]
        output, code = "", 0
        if argv[0] == "powershell.exe":
            if scenario == "preflight":
                code = 1
        elif argv[3] == "info":
            output = "linux" if scenario != "daemon" else "windows"
        elif argv[3] == "build":
            context = Path(argv[-1])
            assert sorted(p.name for p in context.iterdir()) == ["Dockerfile"]
            assert "FAKE-secret" not in (context / "Dockerfile").read_text()
            code = 1 if scenario == "build" else 0
        elif argv[3:5] == ["image", "inspect"]:
            output = "mutable-image:latest" if scenario == "image" else IMAGE
        elif argv[3] == "run":
            args = argv[argv.index("colmap")+1:]
            if args == ["-h"]:
                output = "COLMAP 3.9.1\nUsage:" if scenario != "help" else "unrelated CLI"
            elif args[-1] == "-h":
                output = " ".join(installer.ColmapDocker.commands[args[0]]) if scenario != "flags" else "wrong options"
            elif args[0] == "feature_extractor":
                code = 1 if scenario == "features" else 0
                with sqlite3.connect(kwargs["cwd"] / "database.db") as db:
                    db.executescript("CREATE TABLE images(id INTEGER); INSERT INTO images VALUES(1); CREATE TABLE keypoints(rows INTEGER);")
                    db.execute("INSERT INTO keypoints VALUES(?)", (0 if scenario == "empty-features" else 12,))
            elif args[0] == "exhaustive_matcher":
                code = 1 if scenario == "matching" else 0
            else:
                raise AssertionError("Unexpected mock CLI")
        else:
            raise AssertionError("Unexpected external boundary")
        return subprocess.CompletedProcess(argv, code, output, "")
    def cleanup(argv, **kwargs):
        state["cleanups"].append(argv)
        assert argv[3:5] == ["container", "rm"]
        return subprocess.CompletedProcess(argv, 2 if state["scenario"] == "cleanup" else 0, b"", b"fixture cleanup failure")
    monkeypatch.setattr(installer, "invoke", reply)
    monkeypatch.setattr(subprocess, "run", cleanup)
    return tmp_path, state


@pytest.mark.parametrize("context", ["desktop-linux", "remote-fixture"])
def test_publishes_only_after_all_help_cpu_and_cleanup_checks(fixture, context):
    root, state = fixture
    body = installer.setup(root, "fixture-docker", context=context)
    saved = json.loads((root / "backend/native-colmap-docker.json").read_text())
    assert saved == body and saved["image"] == IMAGE
    assert saved["colmap_version"] == "3.9.1"
    assert len(state["cleanups"]) == 7
    assert bool([c for c in state["calls"] if c[0] == "powershell.exe"]) == (context == "desktop-linux" and os.name == "nt")
    assert not list((root / "backend").glob("colmap-config-*.tmp"))


@pytest.mark.parametrize("scenario", ["daemon", "preflight", "build", "image", "help", "flags", "features", "empty-features", "matching", "cleanup"])
def test_failed_checks_never_replace_existing_config_even_with_force(fixture, scenario):
    if scenario == "preflight" and os.name != "nt":
        pytest.skip("Windows workspace preflight")
    root, state = fixture
    destination = root / "backend/native-colmap-docker.json"
    destination.write_text('{"preserve":"old reviewed configuration"}', encoding="utf-8")
    before = destination.read_bytes()
    state["scenario"] = scenario
    with pytest.raises((ValueError, RuntimeError)):
        installer.setup(root, "fixture-docker", force=True)
    assert destination.read_bytes() == before
    if scenario in {"daemon", "preflight"}:
        assert not (root / ".tools").exists()
    assert not list((root / "backend").glob("colmap-config-*.tmp"))


def test_existing_config_without_force_has_no_external_side_effects(fixture):
    root, state = fixture
    (root / "backend/native-colmap-docker.json").write_text("preserved", encoding="utf-8")
    with pytest.raises(ValueError, match="already exists"):
        installer.setup(root, "fixture-docker")
    assert state["calls"] == []


def test_atomic_publish_failure_preserves_old_config_and_removes_only_own_temp(fixture, monkeypatch):
    root, _ = fixture
    destination = root / "backend/native-colmap-docker.json"
    destination.write_text("preserved", encoding="utf-8")
    def refuse(source, target):
        assert source.parent == destination.parent and target == destination
        raise OSError("explicit atomic replace failure fixture")
    monkeypatch.setattr(installer.os, "replace", refuse)
    with pytest.raises(OSError):
        installer.setup(root, "fixture-docker", force=True)
    assert destination.read_text() == "preserved"
    assert not list((root / "backend").glob("colmap-config-*.tmp"))
