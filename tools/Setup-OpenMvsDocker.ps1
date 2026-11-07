[CmdletBinding()]
param(
    [string]$DockerBin = 'C:\Users\GIGABYTE\AppData\Local\Programs\DockerDesktop\resources\bin\docker.exe',
    [ValidatePattern('^[A-Za-z0-9_.-]+$')][string]$Context = 'desktop-linux',
    [string]$ArchivePath,
    [ValidateRange(512, 8192)][int]$MemoryMB = 4096,
    [ValidateRange(1, 8)][int]$Threads = 4,
    [switch]$ForceUpdate
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$imageSource = Join-Path $repoRoot 'infra\docker\openmvs'
$configPath = Join-Path $repoRoot 'backend\native-docker.json'
$archiveSHA = '7104ae1ddd6ca38fbca9e0e4a70b20af59e21e0b497eb7181c864fbf38ca8d00'
$archiveURL = 'https://github.com/cdcseacave/openMVS/releases/download/v2.4.0/OpenMVS_Ubuntu_x64.zip'
$cacheArchive = Join-Path $repoRoot '.tools\downloads\OpenMVS_Ubuntu_x64-v2.4.0.zip'
$toolNames = @('InterfaceCOLMAP', 'DensifyPointCloud', 'ReconstructMesh', 'RefineMesh', 'TextureMesh')
$tag = 'ar-spacefit/openmvs:2.4.0-cpu'
. (Join-Path $PSScriptRoot 'Test-Support.ps1')

function Invoke-SetupNative([string[]]$Values, [int]$TimeoutSeconds = 120) {
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $DockerBin
    $startInfo.Arguments = ConvertTo-NativeArguments $Values
    $startInfo.WorkingDirectory = $repoRoot
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    try {
        if (-not $process.Start()) { throw 'Docker CLI did not start.' }
        $outputTask = $process.StandardOutput.ReadToEndAsync()
        $errorTask = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            $process.Kill()
            $process.WaitForExit()
            throw "Docker command timed out after $TimeoutSeconds seconds."
        }
        [pscustomobject]@{
            ExitCode = $process.ExitCode
            Output = $outputTask.GetAwaiter().GetResult() + $errorTask.GetAwaiter().GetResult()
        }
    } finally { $process.Dispose() }
}

if (-not (Test-Path -LiteralPath $DockerBin -PathType Leaf)) { throw 'Docker CLI not found; provide -DockerBin.' }
if ((Test-Path -LiteralPath $configPath) -and -not $ForceUpdate) {
    throw 'native-docker.json already exists. Preserve it, or explicitly use -ForceUpdate after reviewing its settings.'
}
$version = Invoke-SetupNative @('--context', $Context, 'info', '--format', '{{.OSType}}')
if ($version.ExitCode -ne 0 -or $version.Output.Trim() -ne 'linux') { throw 'A running Linux Docker daemon is required.' }

# Docker Desktop can lose its scoped workspace mount after a WSL restart.
# Check it before downloading/building so failed sharing cannot publish a
# runtime configuration. Other contexts must not modify Desktop's WSL distro.
if ($Context -eq 'desktop-linux') {
    & (Join-Path $PSScriptRoot 'Ensure-OpenMvsDockerWorkspace.ps1')
    if (-not $?) { throw 'Docker workspace preflight failed. Configuration was not published.' }
}

$runId = 'openmvs-docker-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
$runDir = Join-Path $repoRoot ('.tools\qa\' + $runId)
$buildContext = Join-Path $runDir 'build-context'
New-Item -ItemType Directory -Path $buildContext | Out-Null
Copy-Item -LiteralPath (Join-Path $imageSource 'Dockerfile') -Destination $buildContext
Copy-Item -LiteralPath (Join-Path $imageSource '.dockerignore') -Destination $buildContext
$stagedArchive = Join-Path $buildContext 'OpenMVS_Ubuntu_x64.zip'
if (-not $ArchivePath -and (Test-Path -LiteralPath $cacheArchive -PathType Leaf)) {
    $ArchivePath = $cacheArchive
}
if ($ArchivePath) {
    $sourceArchive = (Resolve-Path -LiteralPath $ArchivePath).Path
    if ((Get-FileHash -LiteralPath $sourceArchive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $archiveSHA) {
        throw 'OpenMVS release SHA256 mismatch; archive was not copied or executed.'
    }
    Copy-Item -LiteralPath $sourceArchive -Destination $stagedArchive
} else {
    Invoke-WebRequest -Uri $archiveURL -OutFile $stagedArchive -UseBasicParsing
}
if ((Get-FileHash -LiteralPath $stagedArchive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $archiveSHA) {
    throw 'OpenMVS release SHA256 mismatch; build has not started.'
}
if (-not (Test-Path -LiteralPath $cacheArchive)) {
    New-Item -ItemType Directory -Force -Path (Split-Path $cacheArchive) | Out-Null
    Copy-Item -LiteralPath $stagedArchive -Destination $cacheArchive
}
Write-Output "Building verified official OpenMVS CPU image; evidence: $runDir"
$build = Invoke-SetupNative @('--context', $Context, 'build', '--platform', 'linux/amd64', '--progress', 'plain', '--tag', $tag, $buildContext) 1800
$build.Output | Set-Content -LiteralPath (Join-Path $runDir 'build.log') -Encoding UTF8
if ($build.ExitCode -ne 0) { throw "Docker image build failed. See $runDir\build.log" }
$inspection = Invoke-SetupNative @('--context', $Context, 'image', 'inspect', '--format', '{{.Id}}', $tag)
$imageId = $inspection.Output.Trim()
if ($inspection.ExitCode -ne 0 -or $imageId -notmatch '^sha256:[a-f0-9]{64}$') { throw 'Could not resolve an immutable image ID.' }
$results = @()
foreach ($tool in $toolNames) {
    $containerName = $runId + '-' + $tool.ToLowerInvariant()
    $qaFolder = Join-Path $runDir $tool
    New-Item -ItemType Directory -Path $qaFolder | Out-Null
    $values = @('--context', $Context, 'run', '--rm', '--pull', 'never', '--name', $containerName,
        '--label', 'io.ar-spacefit.owner=openmvs-setup', '--network', 'none', '--cap-drop', 'ALL',
        '--security-opt', 'no-new-privileges', '--read-only', '--tmpfs', '/tmp:rw,noexec,nosuid,size=256m',
        '--memory', ($MemoryMB.ToString() + 'm'), '--cpus', $Threads.ToString(), '--pids-limit', '256',
        '--mount', ('type=bind,source=' + $qaFolder + ',target=/workspace'), '--workdir', '/workspace', $imageId, $tool, '-h')
    try {
        $probe = Invoke-SetupNative $values 120
        $probe.Output | Set-Content -LiteralPath (Join-Path $qaFolder 'help.log') -Encoding UTF8
        $passed = ($probe.ExitCode -in @(0, 1)) -and ($probe.Output -match 'OpenMVS x64 v2\.4\.0\b') -and ($probe.Output -match '(?i)options')
        $results += [pscustomobject]@{ tool = $tool; exit_code = $probe.ExitCode; passed = $passed }
        if (-not $passed) { throw "Native startup failed: $tool. See $qaFolder\help.log" }
    } finally {
        # A timeout can leave the daemon-side container alive after its client
        # exits. Remove only this invocation's exact name after label verification.
        $owner = Invoke-SetupNative @('--context', $Context, 'container', 'inspect', '--format', '{{index .Config.Labels "io.ar-spacefit.owner"}}', $containerName)
        if ($owner.ExitCode -eq 0 -and $owner.Output.Trim() -eq 'openmvs-setup') {
            $removed = Invoke-SetupNative @('--context', $Context, 'container', 'rm', '--force', $containerName)
            if ($removed.ExitCode -ne 0) { throw "Owned setup container could not be cleaned: $containerName. Configuration was not published." }
        } elseif ($owner.ExitCode -eq 0) {
            # Never remove a container whose identity changed or whose label
            # does not match this setup invocation. Its presence also means
            # cleanup was not proved; do not publish a ready configuration.
            throw "Setup container has an unexpected ownership label: $containerName. Foreign container was not removed; configuration was not published."
        } elseif ($owner.ExitCode -ne 0) {
            # --rm normally removes the container before inspection. A failed
            # inspection can also mean daemon failure, so prove absence rather
            # than silently treating every inspect error as successful cleanup.
            $remaining = Invoke-SetupNative @('--context', $Context, 'container', 'ls', '--all', '--filter', ('name=^/' + $containerName + '$'), '--format', '{{.Names}}')
            if ($remaining.ExitCode -ne 0 -or $remaining.Output.Trim()) {
                throw "Setup container cleanup could not be verified: $containerName. Configuration was not published."
            }
        }
    }
}
$configuration = [ordered]@{ image = $imageId; docker_bin = (Resolve-Path -LiteralPath $DockerBin).Path; context = $Context; memory_mb = $MemoryMB; threads = $Threads }
$configStagingPath = Join-Path $runDir 'native-docker.json'
[IO.File]::WriteAllText($configStagingPath, ($configuration | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
# Publish only a complete configuration; the existing file is preserved unless
# the caller explicitly selected -ForceUpdate.
Move-Item -LiteralPath $configStagingPath -Destination $configPath -Force:$ForceUpdate
[ordered]@{ image = $imageId; source_revision = '58117204c86bbb11a0b25b26a8987676cf11274d'; archive_sha256 = $archiveSHA; tests = $results } |
    ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $runDir 'startup-summary.json') -Encoding UTF8
Write-Output "OpenMVS Docker startup: 5 passed. Config: $configPath. Image: $imageId"
Write-Output 'No original input/job was retried or changed. Startup is not reconstruction-quality acceptance.'
