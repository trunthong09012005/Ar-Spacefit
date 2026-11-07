"""PowerShell provisioner contracts in a copied, disposable workspace.

The Docker command, SHA and download functions below are explicit MOCKS.
The workspace-preflight script also uses an explicit external WSL mock.
These tests never build/pull/start Docker, mount WSL or claim native reconstruction passed.
Actual official-image CLI startup has separate recorded Docker evidence.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

import pytest


pytestmark = pytest.mark.skipif(sys.platform != "win32", reason="PowerShell 5.1 Windows provisioner contract")
REPO_ROOT = Path(__file__).resolve().parents[2]
ARCHIVE_SHA = "7104ae1ddd6ca38fbca9e0e4a70b20af59e21e0b497eb7181c864fbf38ca8d00"
MOCK_IMAGE = "sha256:" + "a" * 64
TOOLS = ("InterfaceCOLMAP", "DensifyPointCloud", "ReconstructMesh", "RefineMesh", "TextureMesh")

@pytest.fixture
def provisioner_fixture(tmp_path):
    tools = tmp_path / "tools"
    image_source = tmp_path / "infra/docker/openmvs"
    tools.mkdir()
    image_source.mkdir(parents=True)
    shutil.copyfile(REPO_ROOT / "tools/Test-Support.ps1", tools / "Test-Support.ps1")
    # Mock only the external Docker command boundary in the disposable copy.
    # All validation/publishing/cleanup logic and assertions stay unchanged.
    # No unsigned fake .exe is executed or App Control policy altered.
    setup_source = (REPO_ROOT / "tools/Setup-OpenMvsDocker.ps1").read_text(encoding="utf-8-sig")
    helper_definition = "function Invoke-SetupNative("
    assert setup_source.count(helper_definition) == 1
    assert setup_source.count("Get-FileHash") == 2
    assert setup_source.count("Invoke-WebRequest") == 1
    (tools / "Setup-OpenMvsDocker.ps1").write_text(
        setup_source.replace(helper_definition, "function Invoke-ActualNativeNotUsedByMockContract(")
        .replace("Get-FileHash", "Get-ExplicitMockFileHash")
        .replace("Invoke-WebRequest", "Invoke-ExplicitMockDownload"), encoding="utf-8"
    )
    # Exercise the real workspace-preflight orchestration while replacing only
    # its external WSL boundary. No real distro or mount is touched by this suite.
    workspace_source = (REPO_ROOT / "tools/Ensure-OpenMvsDockerWorkspace.ps1").read_text(encoding="utf-8-sig")
    assert workspace_source.count("function Invoke-WorkspaceWsl(") == 1
    (tools / "Ensure-OpenMvsDockerWorkspace.ps1").write_text(
        workspace_source.replace("function Invoke-WorkspaceWsl(", "function Invoke-ActualWorkspaceWslNotUsedByMockContract("),
        encoding="utf-8",
    )
    (tmp_path / "AGENTS.md").write_bytes(b"test-rules")
    (tmp_path / "backend/spacefit").mkdir(parents=True)
    (tmp_path / "backend/spacefit/config.py").write_bytes(b"test-config")
    mock_docker_exe = tmp_path / "MOCK-never-executed-docker.exe"
    mock_docker_exe.write_text("Existence fixture only; external Docker calls are mocked")
    for filename in ("Dockerfile", ".dockerignore"):
        shutil.copyfile(REPO_ROOT / "infra/docker/openmvs" / filename, image_source / filename)
    archive = tmp_path / "MOCK-not-a-release-archive.zip"
    archive.write_text("Explicit mock archive; never unpacked or executed", encoding="utf-8")
    wrapper = tools / "Invoke-MockProvisioner.ps1"
    wrapper.write_text(
        r'''param([string]$DockerBin, [string]$Scenario, [switch]$Force, [switch]$UseCache, [int]$MemoryMB = 4096, [int]$Threads = 4, [string]$Context = 'desktop-linux')
$ErrorActionPreference = 'Stop'
function global:Get-ExplicitMockFileHash {
    param([string]$LiteralPath, [string]$Algorithm)
    Add-Content -LiteralPath (Join-Path $env:SPACEFIT_SETUP_FIXTURE_DIR 'boundary-order.txt') -Value 'hash'
    Add-Content -LiteralPath (Join-Path $env:SPACEFIT_SETUP_FIXTURE_DIR 'hash-calls.txt') -Value $LiteralPath
    $fixtureHash = if ($env:SPACEFIT_SETUP_FIXTURE_SCENARIO -eq 'bad-checksum') { '0' * 64 } else { 'ARCHIVE_SHA_PLACEHOLDER' }
    [pscustomobject]@{ Hash = $fixtureHash }
}
function global:Invoke-ExplicitMockDownload {
    param([string]$Uri, [string]$OutFile, [switch]$UseBasicParsing)
    Add-Content -LiteralPath (Join-Path $env:SPACEFIT_SETUP_FIXTURE_DIR 'boundary-order.txt') -Value 'download'
    Set-Content -LiteralPath (Join-Path $env:SPACEFIT_SETUP_FIXTURE_DIR 'download-called.txt') -Value $Uri
    Set-Content -LiteralPath $OutFile -Value 'MOCK archive, never unpacked'
}
function global:Invoke-WorkspaceWsl {
    param([string[]]$Arguments)
    Add-Content -LiteralPath (Join-Path $env:SPACEFIT_SETUP_FIXTURE_DIR 'boundary-order.txt') -Value ('wsl:' + $Arguments[0])
    Add-Content -LiteralPath (Join-Path $env:SPACEFIT_SETUP_FIXTURE_DIR 'wsl-calls.txt') -Value ($Arguments -join "`t")
    if ($env:SPACEFIT_SETUP_FIXTURE_SCENARIO -eq 'workspace-failure') {
        return [pscustomobject]@{ ExitCode = 2; Output = 'MOCK private WSL failure details' }
    }
    if ($Arguments[0] -eq 'test' -and $Arguments[1] -eq '-f') {
        return [pscustomobject]@{ ExitCode = 0; Output = '' }
    }
    if ($Arguments[0] -eq 'stat') {
        $size = if ($Arguments[-1].EndsWith('/AGENTS.md')) { '10' } else { '11' }
        return [pscustomobject]@{ ExitCode = 0; Output = $size }
    }
    throw 'Unexpected WSL command in explicit provisioner mock'
}
function global:Invoke-SetupNative {
    param([string[]]$Values, [int]$TimeoutSeconds = 120)
    Add-Content -LiteralPath (Join-Path $env:SPACEFIT_SETUP_FIXTURE_DIR 'boundary-order.txt') -Value ('docker:' + $Values[2])
    Add-Content -LiteralPath (Join-Path $env:SPACEFIT_SETUP_FIXTURE_DIR 'docker-calls.txt') -Value ($Values -join "`t")
    $scenario = $env:SPACEFIT_SETUP_FIXTURE_SCENARIO
    $result = [pscustomobject]@{ ExitCode = 0; Output = '' }
    switch ($Values[2]) {
        'info' {
            $result.Output = 'linux'
            if ($scenario -eq 'daemon-failure') { $result.ExitCode = 5; $result.Output = 'MOCK daemon unavailable' }
        }
        'build' {
            $result.Output = 'MOCK build; no Docker engine accessed'
            if ($scenario -eq 'build-failure') { $result.ExitCode = 7 }
        }
        'image' {
            $result.Output = 'sha256:' + ('a' * 64)
            if ($scenario -eq 'invalid-image') { $result.Output = 'mutable-tag-only' }
        }
        'run' {
            $result.ExitCode = 1
            $version = if ($scenario -eq 'wrong-version') { '2.5.0' } else { '2.4.0' }
            $result.Output = "MOCK: OpenMVS x64 v$version`nAvailable options: --help"
            if ($scenario -eq 'invalid-help') { $result.Output = 'MOCK unrelated executable options' }
            if ($scenario -eq 'loader-failure') { $result.ExitCode = 127; $result.Output = 'MOCK loader failed' }
        }
        'container' {
            if ($Values[3] -eq 'inspect') {
                $result.ExitCode = 1
                if ($scenario -in @('owned-cleanup', 'cleanup-failure')) { $result.ExitCode = 0; $result.Output = 'openmvs-setup' }
                if ($scenario -eq 'foreign-label') { $result.ExitCode = 0; $result.Output = 'some-other-owner' }
            } elseif ($Values[3] -eq 'rm' -and $scenario -eq 'cleanup-failure') { $result.ExitCode = 7 }
            elseif ($Values[3] -eq 'ls') {
                if ($scenario -eq 'cleanup-inspect-daemon-failure') { $result.ExitCode = 5; $result.Output = 'MOCK daemon lost' }
                if ($scenario -eq 'cleanup-inspect-leftover') { $result.Output = 'MOCK unverified leftover container' }
            }
        }
        default { throw 'Unknown MOCK Docker command' }
    }
    return $result
}
$params = @{ DockerBin = $DockerBin; MemoryMB = $MemoryMB; Threads = $Threads; Context = $Context }
if ($Force) { $params.ForceUpdate = $true }
if (-not $UseCache) { $params.ArchivePath = Join-Path $env:SPACEFIT_SETUP_FIXTURE_DIR 'MOCK-not-a-release-archive.zip' }
& (Join-Path $PSScriptRoot 'Setup-OpenMvsDocker.ps1') @params
'''.replace("ARCHIVE_SHA_PLACEHOLDER", ARCHIVE_SHA),
        encoding="utf-8",
    )

    def invoke(scenario="success", *, force=False, use_cache=False, missing_cli=False, extra_args=()):
        env = os.environ.copy()
        env["SPACEFIT_SETUP_FIXTURE_DIR"] = str(tmp_path)
        env["SPACEFIT_SETUP_FIXTURE_SCENARIO"] = scenario
        command = ["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(wrapper), "-DockerBin", str(tmp_path / "missing.exe" if missing_cli else mock_docker_exe), "-Scenario", scenario]
        if force:
            command.append("-Force")
        if use_cache:
            command.append("-UseCache")
        command.extend(extra_args)
        return subprocess.run(command, cwd=tmp_path, env=env, capture_output=True, text=True, timeout=45)

    return tmp_path, invoke


def read_calls(folder):
    path = folder / "docker-calls.txt"
    return [line.split("\t") for line in path.read_text().splitlines()] if path.exists() else []


def test_mock_success_publishes_immutable_config_only_after_five_restricted_probes(provisioner_fixture):
    folder, invoke = provisioner_fixture
    result = invoke()
    assert result.returncode == 0, result.stdout + result.stderr
    config_bytes = (folder / "backend/native-docker.json").read_bytes()
    assert not config_bytes.startswith(b"\xef\xbb\xbf")
    config = json.loads(config_bytes)
    assert config["image"] == MOCK_IMAGE
    assert config["threads"] == 4 and config["memory_mb"] == 4096
    calls = read_calls(folder)
    probes = [args for args in calls if args[2] == "run"]
    assert len(probes) == 5
    for args, tool in zip(probes, TOOLS):
        assert args[-3:] == [MOCK_IMAGE, tool, "-h"]
        for flag, value in (("--network", "none"), ("--cap-drop", "ALL"), ("--security-opt", "no-new-privileges"), ("--memory", "4096m"), ("--cpus", "4"), ("--pull", "never")):
            assert args[args.index(flag) + 1] == value
        assert "--read-only" in args and "--rm" in args
        assert "--privileged" not in args and "--publish" not in args
        assert "target=/workspace" in args[args.index("--mount") + 1]
    assert len((folder / "hash-calls.txt").read_text().splitlines()) == 2
    assert not (folder / "backend/.env").exists()


@pytest.mark.parametrize("scenario, expected_reason", (
    ("daemon-failure", "running Linux Docker daemon"),
    ("build-failure", "Docker image build failed"),
    ("invalid-image", "immutable image ID"),
    ("invalid-help", "Native startup failed"),
    ("wrong-version", "Native startup failed"),
    ("loader-failure", "Native startup failed"),
    ("bad-checksum", "release SHA256 mismatch"),
    ("cleanup-failure", "Owned setup container could not be cleaned"),
    ("cleanup-inspect-daemon-failure", "Setup container cleanup could not be verified"),
    ("cleanup-inspect-leftover", "Setup container cleanup could not be verified"),
))
def test_mock_errors_do_not_replace_existing_runtime_config(provisioner_fixture, scenario, expected_reason):
    folder, invoke = provisioner_fixture
    config_path = folder / "backend/native-docker.json"
    original = b'{"image":"preserve-original-owner-settings"}'
    config_path.write_bytes(original)
    result = invoke(scenario, force=True)
    assert result.returncode != 0
    assert expected_reason in result.stderr
    assert config_path.read_bytes() == original
    if scenario == "daemon-failure":
        assert not (folder / "wsl-calls.txt").exists()
    if scenario == "bad-checksum":
        assert not any(args[2] in ("build", "run") for args in read_calls(folder))


def test_existing_config_requires_explicit_update_and_does_not_touch_docker(provisioner_fixture):
    folder, invoke = provisioner_fixture
    config_path = folder / "backend/native-docker.json"
    config_path.write_text('{"owner":"do-not-overwrite"}')
    result = invoke()
    assert result.returncode != 0 and "already exists" in result.stderr
    assert read_calls(folder) == []
    assert not (folder / "wsl-calls.txt").exists()
    assert config_path.read_text() == '{"owner":"do-not-overwrite"}'


def test_missing_cli_reports_error_before_creating_run_or_config(provisioner_fixture):
    folder, invoke = provisioner_fixture
    result = invoke(missing_cli=True)
    assert result.returncode != 0 and "CLI not found" in result.stderr
    assert not (folder / "wsl-calls.txt").exists()
    assert not (folder / "backend/native-docker.json").exists()
    assert not (folder / ".tools/qa").exists()


def test_mock_cached_archive_is_reverified_without_network_download(provisioner_fixture):
    folder, invoke = provisioner_fixture
    cache = folder / ".tools/downloads/OpenMVS_Ubuntu_x64-v2.4.0.zip"
    cache.parent.mkdir(parents=True)
    cache.write_text("Explicit mock pre-existing archive")
    result = invoke(use_cache=True)
    assert result.returncode == 0, result.stdout + result.stderr
    assert not (folder / "download-called.txt").exists()
    hashes = (folder / "hash-calls.txt").read_text().splitlines()
    assert str(cache) == hashes[0] and len(hashes) == 2


def test_corrupt_mock_cached_archive_is_rejected_before_copy_or_build(provisioner_fixture):
    folder, invoke = provisioner_fixture
    cache = folder / ".tools/downloads/OpenMVS_Ubuntu_x64-v2.4.0.zip"
    cache.parent.mkdir(parents=True)
    cache.write_text("Explicit deliberately untrusted cache fixture")
    result = invoke("bad-checksum", use_cache=True)
    assert result.returncode != 0 and "release SHA256 mismatch" in result.stderr
    assert cache.read_text() == "Explicit deliberately untrusted cache fixture"
    assert not any(args[2] in ("build", "run") for args in read_calls(folder))
    assert not (folder / "backend/native-docker.json").exists()


def test_mock_download_uses_fixed_official_release_and_is_verified_before_build(provisioner_fixture):
    folder, invoke = provisioner_fixture
    result = invoke(use_cache=True)
    assert result.returncode == 0, result.stdout + result.stderr
    assert (folder / "download-called.txt").read_text().strip() == "https://github.com/cdcseacave/openMVS/releases/download/v2.4.0/OpenMVS_Ubuntu_x64.zip"
    assert len((folder / "hash-calls.txt").read_text().splitlines()) == 1
    contexts = list((folder / ".tools/qa").glob("*/build-context"))
    assert len(contexts) == 1
    assert {file.name for file in contexts[0].iterdir()} == {"Dockerfile", ".dockerignore", "OpenMVS_Ubuntu_x64.zip"}


@pytest.mark.parametrize("parameter, invalid_value", (("MemoryMB", "511"), ("MemoryMB", "8193"), ("Threads", "0"), ("Threads", "9"), ("Context", "../../unsafe")))
def test_invalid_resource_or_context_parameters_fail_before_docker(provisioner_fixture, parameter, invalid_value):
    folder, invoke = provisioner_fixture
    result = invoke(extra_args=("-" + parameter, invalid_value))
    assert result.returncode != 0 and "Cannot validate argument" in result.stderr
    assert read_calls(folder) == []
    assert not (folder / "backend/native-docker.json").exists()


@pytest.mark.parametrize("memory, threads", ((512, 1), (8192, 8)))
def test_mock_resource_bounds_are_preserved_in_probes_and_config(provisioner_fixture, memory, threads):
    folder, invoke = provisioner_fixture
    result = invoke(extra_args=("-MemoryMB", str(memory), "-Threads", str(threads)))
    assert result.returncode == 0, result.stdout + result.stderr
    config = json.loads((folder / "backend/native-docker.json").read_bytes())
    assert config["memory_mb"] == memory and config["threads"] == threads
    for args in read_calls(folder):
        if args[2] == "run":
            assert args[args.index("--memory") + 1] == f"{memory}m"
            assert args[args.index("--cpus") + 1] == str(threads)


@pytest.mark.parametrize("scenario, expected_removals", (("owned-cleanup", 5), ("foreign-label", 0)))
def test_mock_cleanup_requires_exact_owned_container_label(provisioner_fixture, scenario, expected_removals):
    folder, invoke = provisioner_fixture
    result = invoke(scenario)
    if scenario == "foreign-label":
        assert result.returncode != 0 and "unexpected ownership label" in result.stderr
        assert not (folder / "backend/native-docker.json").exists()
    else:
        assert result.returncode == 0, result.stdout + result.stderr
    calls = read_calls(folder)
    probes = [args for args in calls if args[2] == "run"]
    removed = [args for args in calls if args[2:4] == ["container", "rm"]]
    assert len(removed) == expected_removals
    owned_names = {args[args.index("--name") + 1] for args in probes}
    assert all(args[-1] in owned_names and "--force" in args for args in removed)


def test_mock_foreign_label_does_not_publish_or_replace_config_and_is_not_removed(provisioner_fixture):
    folder, invoke = provisioner_fixture
    config_path = folder / "backend/native-docker.json"
    original = b'{"image":"keep-the-existing-owner-configuration"}'
    config_path.write_bytes(original)
    result = invoke("foreign-label", force=True)
    assert result.returncode != 0 and "unexpected ownership label" in result.stderr
    assert config_path.read_bytes() == original
    calls = read_calls(folder)
    assert any(args[2:4] == ["container", "inspect"] for args in calls)
    assert not any(args[2:4] == ["container", "rm"] for args in calls)


@pytest.mark.parametrize("use_cache", (False, True))
def test_mock_workspace_preflight_precedes_archive_work_and_build(provisioner_fixture, use_cache):
    folder, invoke = provisioner_fixture
    result = invoke(use_cache=use_cache)
    assert result.returncode == 0, result.stdout + result.stderr
    order = (folder / "boundary-order.txt").read_text().splitlines()
    assert order[:5] == ["docker:info", "wsl:test", "wsl:test", "wsl:stat", "wsl:stat"]
    assert order.index("wsl:stat") < order.index("hash") < order.index("docker:build")
    if use_cache:
        assert order.index("wsl:stat") < order.index("download") < order.index("hash")
    assert (folder / "backend/native-docker.json").is_file()


@pytest.mark.parametrize("force_update", (False, True))
def test_mock_workspace_failure_never_publishes_config_or_builds(provisioner_fixture, force_update):
    folder, invoke = provisioner_fixture
    config_path = folder / "backend/native-docker.json"
    original = b'{"image":"keep-owner-runtime-after-workspace-failure"}'
    if force_update:
        config_path.write_bytes(original)
    result = invoke("workspace-failure", force=force_update)
    assert result.returncode != 0
    assert "Docker workspace preflight failed" in result.stderr
    assert "MOCK private WSL failure details" not in result.stdout + result.stderr
    if force_update:
        assert config_path.read_bytes() == original
    else:
        assert not config_path.exists()
    assert [args[2] for args in read_calls(folder)] == ["info"]
    assert (folder / "wsl-calls.txt").is_file()
    assert not (folder / "hash-calls.txt").exists()
    assert not (folder / "download-called.txt").exists()
    assert not (folder / ".tools/qa").exists()


def test_mock_other_context_does_not_modify_desktop_wsl_and_still_requires_five_probes(provisioner_fixture):
    folder, invoke = provisioner_fixture
    result = invoke(extra_args=("-Context", "owner-other-context"))
    assert result.returncode == 0, result.stdout + result.stderr
    assert not (folder / "wsl-calls.txt").exists()
    calls = read_calls(folder)
    assert all(args[:2] == ["--context", "owner-other-context"] for args in calls)
    assert len([args for args in calls if args[2] == "run"]) == 5
