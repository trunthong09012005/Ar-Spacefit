from types import SimpleNamespace
from pathlib import Path
import subprocess

import pytest

import native_fixture_workspace as workspace


SID = "S-1-5-21-111-222-333-1001"


def result(code=0, stdout=""):
    return SimpleNamespace(returncode=code, stdout=stdout)


def test_non_windows_creates_new_child_without_acl_commands(tmp_path, monkeypatch):
    monkeypatch.setattr(workspace, "_windows", lambda: False)
    monkeypatch.setattr(workspace, "_run", lambda _: pytest.fail("No Windows command expected"))
    assert workspace.new_native_workspace(tmp_path) == tmp_path / "native-interop"
    assert (tmp_path / "native-interop").is_dir()


def test_windows_grants_only_current_sid_on_new_child(tmp_path, monkeypatch):
    calls = []
    monkeypatch.setattr(workspace, "_windows", lambda: True)
    def run(argv):
        calls.append(argv)
        return result(stdout=f'"HOST\\user","{SID}"\n') if len(calls) == 1 else result()
    monkeypatch.setattr(workspace, "_run", run)
    child = workspace.new_native_workspace(tmp_path)
    assert calls == [["whoami.exe", "/user", "/fo", "csv", "/nh"],
                     ["icacls.exe", str(child), "/grant", f"*{SID}:(OI)(CI)M"]]
    assert child.parent == tmp_path
    assert not any(value in {"/T", "/reset", "/inheritance:e"} for call in calls for value in call)


def test_existing_child_is_never_modified(tmp_path, monkeypatch):
    child = tmp_path / "native-interop"
    child.mkdir()
    marker = child / "keep.txt"
    marker.write_text("preserved", encoding="utf-8")
    monkeypatch.setattr(workspace, "_run", lambda _: pytest.fail("No ACL change allowed"))
    with pytest.raises(FileExistsError):
        workspace.new_native_workspace(tmp_path)
    assert marker.read_text(encoding="utf-8") == "preserved"


def test_missing_parent_is_rejected(tmp_path):
    with pytest.raises(ValueError, match="ordinary directory"):
        workspace.new_native_workspace(tmp_path / "missing")


def test_symlink_parent_is_rejected_before_creating_child(tmp_path, monkeypatch):
    monkeypatch.setattr(Path, "is_symlink", lambda self: self == tmp_path)
    with pytest.raises(ValueError, match="ordinary directory"):
        workspace.new_native_workspace(tmp_path)
    assert not (tmp_path / "native-interop").exists()


@pytest.mark.parametrize("identity", [result(1), result(stdout="bad"),
                                     result(stdout='"user","Everyone"'),
                                     result(stdout=f'"user","{SID}"\n"another","{SID}"')])
def test_invalid_identity_does_not_grant_permissions(tmp_path, monkeypatch, identity):
    calls = []
    monkeypatch.setattr(workspace, "_windows", lambda: True)
    def run(argv):
        calls.append(argv)
        return identity
    monkeypatch.setattr(workspace, "_run", run)
    with pytest.raises(RuntimeError):
        workspace.new_native_workspace(tmp_path)
    assert len(calls) == 1 and calls[0][0] == "whoami.exe"


def test_acl_failure_is_not_silenced(tmp_path, monkeypatch):
    monkeypatch.setattr(workspace, "_windows", lambda: True)
    replies = iter([result(stdout=f'"user","{SID}"'), result(1)])
    monkeypatch.setattr(workspace, "_run", lambda _: next(replies))
    with pytest.raises(RuntimeError, match="fixture permissions"):
        workspace.new_native_workspace(tmp_path)


def test_command_timeout_is_not_silenced(tmp_path, monkeypatch):
    monkeypatch.setattr(workspace, "_windows", lambda: True)
    def run(argv):
        raise subprocess.TimeoutExpired(argv, 15)
    monkeypatch.setattr(workspace, "_run", run)
    with pytest.raises(subprocess.TimeoutExpired):
        workspace.new_native_workspace(tmp_path)
