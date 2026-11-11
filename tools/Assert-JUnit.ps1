param([Parameter(Mandatory=$true)][string]$Path)
$ErrorActionPreference = 'Stop'

$reports = @(Get-ChildItem -Path $Path -File -ErrorAction Stop)
if ($reports.Count -eq 0) { throw 'No JUnit reports found.' }
$total = 0; $failed = 0; $errors = 0; $skipped = 0
foreach ($report in $reports) {
    [xml]$document = Get-Content -LiteralPath $report.FullName -Raw -Encoding UTF8
    $suites = @($document.SelectNodes('//testsuite'))
    if ($suites.Count -eq 0) { throw "No test suites in $($report.Name)." }
    foreach ($suite in $suites) {
        foreach ($attribute in @('tests', 'failures', 'errors', 'skipped')) {
            $value = $suite.GetAttribute($attribute)
            if ($value -notmatch '^\d+$') { throw "Invalid JUnit count: $attribute in $($report.Name)." }
        }
        $total += [int]$suite.tests
        $failed += [int]$suite.failures
        $errors += [int]$suite.errors
        $skipped += [int]$suite.skipped
    }
}
if ($total -eq 0 -or $failed -gt 0 -or $errors -gt 0 -or $skipped -gt 0) {
    throw "JUnit did not fully pass: total=$total failed=$failed errors=$errors skipped=$skipped"
}
Write-Output "total=$total passed=$total failed=0 errors=0 skipped=0"
