"""Scoped workspace preflight contracts with an explicit mocked WSL boundary.

No WSL distro, Docker daemon, production DB or unsigned native fixture is started.
The PowerShell functions are parsed from a copied script in a pytest-owned repo.
"""
import json
import os
from pathlib import Path
import shutil
import subprocess

import pytest


pytestmark = pytest.mark.skipif(os.name != "nt", reason="Windows PowerShell workspace mount contract")
SCRIPT = Path(__file__).resolve().parents[2] / "tools" / "Ensure-OpenMvsDockerWorkspace.ps1"


def ps_quote(value):
    return "'" + str(value).replace("'", "''") + "'"


@pytest.fixture
def workspace(tmp_path):
    root = tmp_path / "owned workspace with spaces"
    (root / "tools").mkdir(parents=True)
    (root / "backend" / "spacefit").mkdir(parents=True)
    (root / "AGENTS.md").write_bytes(b"test-rules")
    (root / "backend" / "spacefit" / "config.py").write_bytes(b"test-config")
    copied = root / "tools" / SCRIPT.name
    shutil.copyfile(SCRIPT, copied)
    return root, copied


def run_fixture(workspace, scenario="missing", source=None):
    root, copied = workspace
    source = root if source is None else source
    statement = f"""
$ErrorActionPreference='Stop'
$tokens=$null; $parseErrors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile({ps_quote(copied)},[ref]$tokens,[ref]$parseErrors)
if($parseErrors.Count) {{ throw 'Script did not parse' }}
$definitions=$ast.FindAll({{ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] }},$true)
foreach($definition in $definitions) {{ Invoke-Expression $definition.Extent.Text }}
$script:calls=[Collections.Generic.List[object]]::new()
$script:mounted=$false
$script:scenario={ps_quote(scenario)}
function Invoke-WorkspaceWsl([string[]]$Arguments) {{
    $script:calls.Add([string[]]$Arguments)
    $code=0; $output=''
    if($Arguments[0] -eq 'test' -and $Arguments.Count -ne 3) {{ throw 'WSL test argv lost its path boundary' }}
    if($Arguments[0] -eq 'stat' -and ($Arguments.Count -ne 5 -or $Arguments[3] -ne '--')) {{ throw 'WSL stat argv lost its path boundary' }}
    if($script:scenario -eq 'wsl-failure') {{
        return [PSCustomObject]@{{ExitCode=2;Output='private-fixture-output-must-not-be-printed'}}
    }}
    switch($Arguments[0]) {{
        'test' {{
            switch($Arguments[1]) {{
                '-f' {{ $code=if($script:scenario -in @('shared','mismatch')) {{0}} else {{1}} }}
                '-e' {{ $code=if($script:scenario -in @('empty','nonempty','file','existing-mount')) {{0}} else {{1}} }}
                '-d' {{ $code=if($script:scenario -eq 'file') {{1}} else {{0}} }}
                '-L' {{ $code=if($script:scenario -eq 'symlink') {{0}} else {{1}} }}
                default {{ throw 'Unexpected test flag in mock' }}
            }}
        }}
        'stat' {{
            if($script:scenario -eq 'post-mount-failure') {{ $code=1 }}
            elseif($script:scenario -eq 'mismatch') {{ $output='999' }}
            elseif($script:mounted -or $script:scenario -eq 'shared') {{
                $output=if($Arguments[-1].EndsWith('/AGENTS.md')) {{'10'}} else {{'11'}}
            }} else {{ $code=1 }}
        }}
        'mountpoint' {{ $code=if($script:scenario -eq 'existing-mount') {{0}} else {{32}} }}
        'find' {{ $output=if($script:scenario -eq 'nonempty') {{'foreign-entry'}} else {{''}} }}
        'mkdir' {{ if($script:scenario -eq 'mkdir-failure') {{ $code=1 }} }}
        'mount' {{
            if($script:scenario -eq 'mount-failure') {{ $code=1 }}
            else {{ $script:mounted=$true }}
        }}
        default {{ throw 'Unexpected external command in mock' }}
    }}
    return [PSCustomObject]@{{ExitCode=$code;Output=$output}}
}}
$failure=$null
try {{ Ensure-OpenMvsDockerWorkspace {ps_quote(source)} }} catch {{ $failure=$_.Exception.Message }}
$wire=ConvertTo-WorkspaceWslArguments @('test','-f','fixture path with spaces','fixture"quote')
Write-Output ('FIXTURE=' + (@{{ calls=@($script:calls.ToArray()); error=$failure; wire=$wire }} | ConvertTo-Json -Depth 8 -Compress))
"""
    result = subprocess.run(["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", statement],
                            capture_output=True, text=True, timeout=20)
    assert result.returncode == 0, result.stderr
    report = json.loads(next(line.removeprefix("FIXTURE=") for line in result.stdout.splitlines() if line.startswith("FIXTURE=")))
    assert "private-fixture-output-must-not-be-printed" not in result.stdout + result.stderr
    return report


def commands(report, name):
    return [args for args in report["calls"] if args[0] == name]


def target(root):
    return "/mnt/host/" + root.drive[0].lower() + "/" + root.as_posix()[3:]


def test_already_shared_workspace_is_verified_without_any_mutation(workspace):
    root, copied = workspace
    report = run_fixture(workspace, "shared")
    assert report["error"] is None
    assert len(commands(report, "stat")) == 2
    assert not commands(report, "mkdir") and not commands(report, "mount")
    assert not commands(report, "find")
    assert commands(report, "test") == [["test", "-f", target(root) + "/AGENTS.md"],
                                          ["test", "-f", target(root) + "/backend/spacefit/config.py"]]


@pytest.mark.parametrize("scenario,creates_target", [("missing", True), ("empty", False)])
def test_missing_mount_recovers_only_the_exact_owned_workspace(workspace, scenario, creates_target):
    root, copied = workspace
    report = run_fixture(workspace, scenario)
    assert report["error"] is None
    assert commands(report, "mount") == [["mount", "-t", "drvfs", "-o", "metadata", "--", str(root), target(root)]]
    assert commands(report, "mkdir") == ([["mkdir", "-p", "--", target(root)]] if creates_target else [])
    assert len(commands(report, "stat")) == 2
    assert not any(args[0] in {"rm", "rmdir", "umount", "shutdown", "terminate", "wsl.conf"} for args in report["calls"])
    assert not any("--shutdown" in args or "--unregister" in args for args in report["calls"])
    assert str(root.anchor) not in commands(report, "mount")[0]


@pytest.mark.parametrize("scenario", ["nonempty", "file", "existing-mount", "symlink", "wsl-failure", "mismatch"])
def test_existing_data_or_failed_preflight_is_not_overwritten(workspace, scenario):
    report = run_fixture(workspace, scenario)
    assert report["error"]
    assert not commands(report, "mount") and not commands(report, "mkdir")


@pytest.mark.parametrize("scenario", ["mkdir-failure", "mount-failure", "post-mount-failure"])
def test_failed_workspace_creation_mount_or_verification_is_not_success(workspace, scenario):
    report = run_fixture(workspace, scenario)
    assert report["error"]
    assert not any(args[0] in {"rm", "umount"} for args in report["calls"])


@pytest.mark.parametrize("source", ["drive-root", "unc", "relative", "comma", "newline", "escaped-root"])
def test_unsafe_windows_sources_are_rejected_before_any_wsl_command(workspace, source):
    root, copied = workspace
    candidates = {"drive-root": root.anchor, "unc": r"\\unreachable-fixture\share\workspace",
                  "relative": "relative-workspace", "comma": str(root) + ",other",
                  "newline": str(root) + "\nother", "escaped-root": root.anchor + "folder\\.."}
    report = run_fixture(workspace, source=candidates[source])
    assert report["error"] and report["calls"] == []


def test_workspace_or_ancestor_junction_cannot_redirect_the_source(workspace, tmp_path):
    root, copied = workspace
    link = tmp_path / "owned-junction"
    result = subprocess.run(["cmd.exe", "/d", "/c", "mklink", "/J", str(link), str(root)],
                            capture_output=True, timeout=10)
    assert result.returncode == 0
    try:
        report = run_fixture(workspace, source=str(link))
        assert report["error"] and report["calls"] == []
    finally:
        os.rmdir(link)
    assert root.is_dir()


def test_script_fixed_boundary_never_resets_wsl_or_broadly_shares_drives():
    source = SCRIPT.read_text(encoding="utf-8-sig")
    assert "'--distribution', 'docker-desktop', '--user', 'root', '--cd', '/', '--exec'" in source
    assert "UseShellExecute = $false" in source
    assert "Join-Path $PSScriptRoot '..'" in source
    assert not any(term in source.lower() for term in ["--shutdown", "--unregister", "--terminate", "umount ", "wsl.conf", "set-mppreference"])


def test_wsl_wire_quoting_keeps_the_executable_bare_and_preserves_argument_boundaries(workspace):
    # A fully quoted --exec program name actually returned exit 127 in the
    # read-only real WSL diagnostic. This contract prevents that regression;
    # the suite itself still never starts WSL.
    report = run_fixture(workspace, "shared")
    assert report["wire"] == 'test -f "fixture path with spaces" "fixture\\"quote"'
