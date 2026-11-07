# Native output is text, including stderr warnings. Only the exit code determines
# command failure; PowerShell 5.1 must not promote Java warnings to terminating errors.
function Write-QAFileHash([string]$Path) {
    $checksum=Get-FileHash -LiteralPath $Path -Algorithm SHA256
    Write-Output "SHA256=$($checksum.Hash) path=$($checksum.Path)"
}

function ConvertTo-NativeArguments([string[]]$Values) {
    return (($Values | ForEach-Object {
        '"' + (($_ -replace '(\\*)"', '$1$1\"') -replace '(\\+)$', '$1$1') + '"'
    }) -join ' ')
}

function Invoke-QACommand(
    [string]$FilePath,
    [string[]]$Arguments,
    [int]$TimeoutSeconds = 1800,
    [switch]$Capture
) {
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $FilePath
    $startInfo.Arguments = ConvertTo-NativeArguments $Arguments
    $startInfo.WorkingDirectory = (Get-Location).Path
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.StandardOutputEncoding = [Text.Encoding]::UTF8
    $startInfo.StandardErrorEncoding = [Text.Encoding]::UTF8
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    $started=$false
    $lines = [Collections.Generic.List[string]]::new()
    try {
        if (-not $process.Start()) { throw 'Native command did not start.' }
        $started=$true
        $timer = [Diagnostics.Stopwatch]::StartNew()
        $outputTask = $process.StandardOutput.ReadLineAsync()
        $errorTask = $process.StandardError.ReadLineAsync()
        while ($null -ne $outputTask -or $null -ne $errorTask -or -not $process.HasExited) {
            if ($timer.Elapsed.TotalSeconds -gt $TimeoutSeconds) { throw "Command timed out after $TimeoutSeconds seconds: $FilePath" }
            if ($null -ne $outputTask -and $outputTask.IsCompleted) {
                $line = $outputTask.GetAwaiter().GetResult()
                if ($null -eq $line) { $outputTask=$null }
                else {
                    if ($Capture) { $lines.Add($line) } else { Write-Output $line }
                    $outputTask=$process.StandardOutput.ReadLineAsync()
                }
            }
            if ($null -ne $errorTask -and $errorTask.IsCompleted) {
                $line = $errorTask.GetAwaiter().GetResult()
                if ($null -eq $line) { $errorTask=$null }
                else {
                    if ($Capture) { $lines.Add($line) } else { Write-Output $line }
                    $errorTask=$process.StandardError.ReadLineAsync()
                }
            }
            [Threading.Thread]::Sleep(20)
        }
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) { throw "Command exited with code $($process.ExitCode): $FilePath" }
        if ($Capture) { return ($lines -join [Environment]::NewLine) }
    } finally {
        if ($started -and -not $process.HasExited) { $process.Kill(); $process.WaitForExit() }
        $process.Dispose()
    }
}
