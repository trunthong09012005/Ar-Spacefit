# Recover only this repository's scoped drvfs bind after a Docker/WSL restart.
# Never reset WSL, share a whole drive, unmount an existing filesystem or change
# Windows security. Native command output stays internal and is not printed.
function ConvertTo-WorkspaceWslArguments([string[]]$Values) {
    return (($Values | ForEach-Object {
        # wsl.exe --exec forwards a command line rather than applying normal
        # Windows argv parsing to its program name. Do not quote safe tokens
        # such as test/stat: a quoted name is otherwise looked up literally.
        if ($_ -match '^[A-Za-z0-9_./:%=\\-]+$') { $_ }
        else { '"' + (($_ -replace '(\\*)"', '$1$1\"') -replace '(\\+)$', '$1$1') + '"' }
    }) -join ' ')
}

function Invoke-WorkspaceWsl([string[]]$Arguments) {
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = Join-Path $env:WINDIR 'System32\wsl.exe'
    $wslArguments = @('--distribution', 'docker-desktop', '--user', 'root', '--cd', '/', '--exec') + $Arguments
    $startInfo.Arguments = ConvertTo-WorkspaceWslArguments $wslArguments
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.StandardOutputEncoding = [Text.Encoding]::UTF8
    $startInfo.StandardErrorEncoding = [Text.Encoding]::UTF8
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    $started = $false
    try {
        if (-not $process.Start()) { throw 'Workspace WSL command did not start.' }
        $started = $true
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(30000)) { throw 'Workspace WSL command timed out.' }
        $output = $stdout.GetAwaiter().GetResult()
        $null = $stderr.GetAwaiter().GetResult()
        return [PSCustomObject]@{ ExitCode = $process.ExitCode; Output = $output.Trim() }
    } catch {
        throw 'Workspace WSL command failed. Docker Desktop must be running.'
    } finally {
        if ($started -and -not $process.HasExited) { $process.Kill(); $process.WaitForExit() }
        $process.Dispose()
    }
}

function Get-WorkspaceMountSpec([string]$WorkspacePath) {
    if ([string]::IsNullOrWhiteSpace($WorkspacePath) -or
        $WorkspacePath -notmatch '^[A-Za-z]:\\' -or $WorkspacePath -match '[,\x00-\x1f\x7f]') {
        throw 'Workspace must be an ordinary absolute local directory.'
    }
    $fullPath = [IO.Path]::GetFullPath($WorkspacePath).TrimEnd('\')
    $driveRoot = [IO.Path]::GetPathRoot($fullPath)
    if ($fullPath + '\' -eq $driveRoot -or
        [IO.DriveInfo]::new($driveRoot).DriveType -ne [IO.DriveType]::Fixed) {
        throw 'Refusing a drive root or non-local workspace.'
    }
    $directory = Get-Item -LiteralPath $fullPath -ErrorAction Stop
    if (-not $directory.PSIsContainer) { throw 'Workspace is not a directory.' }
    # Resolve no junction/symlink at this boundary: the caller must use the real
    # repository path, not a reparse point leading to another folder/drive.
    $ancestor = $directory
    while ($null -ne $ancestor) {
        if ($ancestor.Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw 'Refusing a reparse-point workspace or ancestor.'
        }
        $ancestor = $ancestor.Parent
    }
    $sentinels = @('AGENTS.md', 'backend\spacefit\config.py')
    $sizes = @()
    foreach ($relative in $sentinels) {
        $file = Get-Item -LiteralPath (Join-Path $fullPath $relative) -ErrorAction Stop
        if ($file.PSIsContainer -or ($file.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw 'Workspace sentinel must be an ordinary file.'
        }
        $sizes += [long]$file.Length
    }
    $drive = $fullPath.Substring(0, 1).ToLowerInvariant()
    $relativePath = $fullPath.Substring(3).Replace('\', '/')
    if (-not $relativePath -or $relativePath -match '(^|/)\.\.?(/|$)' -or $relativePath.Contains(':')) {
        throw 'Invalid scoped workspace target.'
    }
    return [PSCustomObject]@{
        Source = $fullPath
        Target = '/mnt/host/' + $drive + '/' + $relativePath
        Sizes = $sizes
    }
}

function Test-WorkspaceWsl([string[]]$Arguments) {
    $result = Invoke-WorkspaceWsl $Arguments
    if ($result.ExitCode -notin @(0, 1)) { throw 'Workspace WSL preflight failed.' }
    return $result.ExitCode -eq 0
}

function Confirm-WorkspaceSentinels($Spec) {
    $files = @(($Spec.Target + '/AGENTS.md'), ($Spec.Target + '/backend/spacefit/config.py'))
    for ($index = 0; $index -lt $files.Count; $index++) {
        $result = Invoke-WorkspaceWsl @('stat', '-c', '%s', '--', $files[$index])
        if ($result.ExitCode -ne 0 -or $result.Output -notmatch '^\d+$' -or
            [long]$result.Output -ne $Spec.Sizes[$index]) {
            throw 'Docker workspace sentinel metadata does not match the repository.'
        }
    }
}

function Ensure-OpenMvsDockerWorkspace([string]$WorkspacePath) {
    $spec = Get-WorkspaceMountSpec $WorkspacePath
    $hasRules = Test-WorkspaceWsl @('test', '-f', ($spec.Target + '/AGENTS.md'))
    $hasConfig = Test-WorkspaceWsl @('test', '-f', ($spec.Target + '/backend/spacefit/config.py'))
    if ($hasRules -and $hasConfig) {
        Confirm-WorkspaceSentinels $spec
        Write-Output ('Docker workspace already available: ' + $spec.Source)
        return
    }
    # Refuse any Linux symlink ancestor before mkdir/mount could escape the
    # derived /mnt/host/<drive>/<repository> target.
    $pieces = $spec.Target.TrimStart('/').Split('/')
    $ancestor = ''
    foreach ($piece in $pieces) {
        $ancestor += '/' + $piece
        if (Test-WorkspaceWsl @('test', '-L', $ancestor)) {
            throw 'Refusing a symlink Docker workspace target or ancestor.'
        }
    }
    if (Test-WorkspaceWsl @('test', '-e', $spec.Target)) {
        if (-not (Test-WorkspaceWsl @('test', '-d', $spec.Target))) {
            throw 'Existing Docker workspace target is not a directory.'
        }
        $mountpoint = Invoke-WorkspaceWsl @('mountpoint', '-q', '--', $spec.Target)
        if ($mountpoint.ExitCode -eq 0) { throw 'Refusing to cover an existing workspace mount.' }
        if ($mountpoint.ExitCode -notin @(1, 32)) { throw 'Workspace mountpoint check failed.' }
        $contents = Invoke-WorkspaceWsl @('find', $spec.Target, '-mindepth', '1', '-maxdepth', '1', '-print', '-quit')
        if ($contents.ExitCode -ne 0 -or $contents.Output) {
            throw 'Refusing to cover a non-empty Docker workspace target.'
        }
    } else {
        $mkdir = Invoke-WorkspaceWsl @('mkdir', '-p', '--', $spec.Target)
        if ($mkdir.ExitCode -ne 0) { throw 'Could not create the scoped Docker workspace target.' }
    }
    $mount = Invoke-WorkspaceWsl @('mount', '-t', 'drvfs', '-o', 'metadata', '--', $spec.Source, $spec.Target)
    if ($mount.ExitCode -ne 0) { throw 'Could not mount the scoped Docker workspace.' }
    Confirm-WorkspaceSentinels $spec
    Write-Output ('Docker scoped workspace available: ' + $spec.Source)
}

try {
    Ensure-OpenMvsDockerWorkspace (Join-Path $PSScriptRoot '..')
} catch {
    Write-Error ('Docker workspace preflight failed: ' + $_.Exception.Message)
    exit 1
}
