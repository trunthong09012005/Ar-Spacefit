param([string]$ListenHost = '', [int]$Port = 0)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$backendRoot = Join-Path $projectRoot 'backend'
$configPath = Join-Path $backendRoot '.env'
if (Test-Path -LiteralPath $configPath) {
    foreach ($line in Get-Content -LiteralPath $configPath) {
        if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
            [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2].Trim(), 'Process')
        }
    }
}
if (-not $env:SPACEFIT_TOKEN -or $env:SPACEFIT_TOKEN.Length -lt 16) {
    throw 'Set SPACEFIT_TOKEN (at least 16 characters) in backend/.env and enter the same token in the Android app.'
}
# Setup writes this non-secret config only after verifying all five Linux tools.
# Explicit SPACEFIT_OPENMVS_RUNTIME=native takes precedence for rollback.
if (-not $env:SPACEFIT_OPENMVS_RUNTIME -and (Test-Path -LiteralPath (Join-Path $backendRoot 'native-docker.json'))) {
    $env:SPACEFIT_OPENMVS_RUNTIME = 'docker'
}
if (-not $env:SPACEFIT_COLMAP_RUNTIME -and (Test-Path -LiteralPath (Join-Path $backendRoot 'native-colmap-docker.json'))) {
    $env:SPACEFIT_COLMAP_RUNTIME = 'docker'
}
if ($env:SPACEFIT_OPENMVS_RUNTIME -eq 'docker' -or $env:SPACEFIT_COLMAP_RUNTIME -eq 'docker') {
    & (Join-Path $PSScriptRoot 'Ensure-OpenMvsDockerWorkspace.ps1')
    if (-not $?) { throw 'Docker workspace preflight failed; backend was not started.' }
}
if (-not $ListenHost) { $ListenHost = if ($env:SPACEFIT_HOST) { $env:SPACEFIT_HOST } else { '127.0.0.1' } }
if (-not $Port) { $Port = if ($env:SPACEFIT_PORT) { [int]$env:SPACEFIT_PORT } else { 8000 } }
$pythonPath = Join-Path $backendRoot '.venv/Scripts/python.exe'
if (-not (Test-Path -LiteralPath $pythonPath)) { throw 'Create backend/.venv and install backend/requirements.txt first. See backend/README.md.' }
Push-Location $backendRoot
try { & $pythonPath -m uvicorn spacefit.api:create_app --factory --host $ListenHost --port $Port --workers 1 }
finally { Pop-Location }
