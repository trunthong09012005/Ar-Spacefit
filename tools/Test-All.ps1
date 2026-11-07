param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$PythonPath = '',
    [string]$BlenderBin = $env:BLENDER_BIN,
    [ValidatePattern('^emulator-[0-9]+$')][string]$EmulatorSerial = '',
    [string]$AdbPath = '',
    [switch]$Offline,
    [switch]$SkipAndroid,
    [switch]$RequireBlender,
    [switch]$RequireDevice,
    [switch]$RequireNative,
    [switch]$RequireDocker,
    [switch]$RequireColmapDocker
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'Test-Support.ps1')
$runName = 'run-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8)
$runRoot = Join-Path $projectRoot ('.tools/qa/' + $runName)
if (Test-Path -LiteralPath $runRoot) { throw 'Refusing to reuse a test output directory.' }
$null = New-Item -ItemType Directory -Path $runRoot
$results = [Collections.Generic.List[object]]::new()
$oldJava = $env:JAVA_HOME
$oldBlender = $env:BLENDER_BIN
$oldDockerTestConfig = $env:SPACEFIT_OPENMVS_DOCKER_TEST_CONFIG
$oldColmapTestConfig = $env:SPACEFIT_COLMAP_DOCKER_TEST_CONFIG

function Add-Result([string]$Name, [string]$Status, [string]$Detail) {
    $results.Add([pscustomobject]@{ name=$Name; status=$Status; detail=$Detail })
    $results | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $runRoot 'summary.json') -Encoding UTF8
    Write-Host ('[' + $Status + '] ' + $Name + ': ' + $Detail)
}

function Invoke-TestGroup([string]$Name, [scriptblock]$Action) {
    $log = Join-Path $runRoot ($Name + '.log')
    Write-Host ('Running ' + $Name + ' ...')
    try {
        & $Action *>&1 | Tee-Object -FilePath $log -OutVariable groupOutput | ForEach-Object { Write-Host $_ }
        $countLine=@($groupOutput | ForEach-Object { $_.ToString() } | Where-Object { $_ -match '^total=' }) | Select-Object -Last 1
        Add-Result $Name 'PASS' (($countLine + ' | ' + $log).Trim())
    } catch {
        $_ | Out-String | Add-Content -LiteralPath $log -Encoding UTF8
        Add-Result $Name 'FAIL' ($_.Exception.Message + ' | ' + $log)
    }
}

function Read-JUnit([string]$Directory) {
    $total=0; $failed=0; $errors=0; $skipped=0
    $files = @(Get-ChildItem -LiteralPath $Directory -Filter '*.xml' -File)
    if ($files.Count -eq 0) { throw "No JUnit XML found: $Directory" }
    foreach ($file in $files) {
        [xml]$xml = Get-Content -LiteralPath $file.FullName -Raw
        foreach ($suite in $xml.SelectNodes('//testsuite')) {
            $total += [int]$suite.tests; $failed += [int]$suite.failures
            $errors += [int]$suite.errors; $skipped += [int]$suite.skipped
        }
    }
    if ($total -eq 0 -or $failed -gt 0 -or $errors -gt 0 -or $skipped -gt 0) { throw "JUnit did not fully pass: total=$total failed=$failed errors=$errors skipped=$skipped" }
    return "total=$total passed=$($total-$failed-$errors-$skipped) failed=$failed errors=$errors skipped=$skipped"
}

function Invoke-Pytest([string]$Name, [string[]]$TestArguments) {
    $directory = Join-Path $runRoot $Name
    $null = New-Item -ItemType Directory -Path $directory
    $baseTemp = Join-Path $directory 'pytest-temp'
    # pytest may delete --basetemp; this path must be new, beneath this unique run.
    if (Test-Path -LiteralPath $baseTemp) { throw 'Unsafe pytest basetemp already exists.' }
    Invoke-QACommand $PythonPath (@('-B','-m','pytest') + $TestArguments + @('-q','-p','no:cacheprovider','--basetemp',$baseTemp,'--junitxml',(Join-Path $directory 'results.xml')))
    Read-JUnit $directory
}

Push-Location $projectRoot
try {
    if (-not $PythonPath) { $PythonPath = Join-Path $projectRoot 'backend/.venv/Scripts/python.exe' }
    Invoke-TestGroup 'backend-core' {
        if (-not (Test-Path -LiteralPath $PythonPath)) { throw 'Install backend/requirements-dev.txt in backend/.venv first.' }
        Invoke-Pytest 'backend-core' @('test/backend','--ignore=test/backend/test_http_blackbox.py','--ignore=test/backend/test_blender_integration.py','--ignore=test/backend/test_openmvs_docker_integration.py','--ignore=test/backend/test_colmap_docker_integration.py')
    }
    Invoke-TestGroup 'backend-http-blackbox' { Invoke-Pytest 'backend-http-blackbox' @('test/backend/test_http_blackbox.py') }

    if ($BlenderBin -and (Test-Path -LiteralPath $BlenderBin)) {
        $env:BLENDER_BIN = (Resolve-Path -LiteralPath $BlenderBin).Path
        Invoke-TestGroup 'blender-integration' { Invoke-Pytest 'blender-integration' @('test/backend/test_blender_integration.py') }
    } else {
        Add-Result 'blender-integration' $(if($RequireBlender){'FAIL'}else{'SKIP'}) 'Pass -BlenderBin with the installed blender executable.'
    }

    $dockerTestConfig=Join-Path $projectRoot 'backend/native-docker.json'
    if(Test-Path -LiteralPath $dockerTestConfig){ $env:SPACEFIT_OPENMVS_DOCKER_TEST_CONFIG=$dockerTestConfig }
    $colmapTestConfig=Join-Path $projectRoot 'backend/native-colmap-docker.json'
    if(Test-Path -LiteralPath $colmapTestConfig){
        $env:SPACEFIT_COLMAP_DOCKER_TEST_CONFIG=$colmapTestConfig
        Invoke-TestGroup 'colmap-docker-integration' { Invoke-Pytest 'colmap-docker-integration' @('test/backend/test_colmap_docker_integration.py') }
    }else{
        Add-Result 'colmap-docker-integration' $(if($RequireColmapDocker){'FAIL'}else{'SKIP'}) 'COLMAP Docker has not been configured; no real COLMAP container test was run.'
    }
    if(Test-Path -LiteralPath $dockerTestConfig){
        $env:SPACEFIT_OPENMVS_DOCKER_TEST_CONFIG=$dockerTestConfig
        Invoke-TestGroup 'openmvs-docker-integration' { Invoke-Pytest 'openmvs-docker-integration' @('test/backend/test_openmvs_docker_integration.py') }
    }else{
        Add-Result 'openmvs-docker-integration' $(if($RequireDocker){'FAIL'}else{'SKIP'}) 'Run tools/Setup-OpenMvsDocker.ps1 after owner approval; no Docker fixture was run.'
    }

    if ($SkipAndroid) {
        Add-Result 'android-build-unit-lint' 'SKIP' 'Explicit -SkipAndroid; this is not a full quality gate.'
    } else {
        Invoke-TestGroup 'android-build-unit-lint' {
            if ($JavaHome) { $env:JAVA_HOME=$JavaHome }
            if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) { throw 'Pass -JavaHome with an installed JDK directory.' }
            $arguments=@('testDebugUnitTest','--rerun','lintDebug','assembleDebug','assembleDebugAndroidTest','--max-workers=2','--console=plain')
            if ($Offline) { $arguments+='--offline' }
            Invoke-QACommand (Join-Path $env:JAVA_HOME 'bin/java.exe') (@('-Dorg.gradle.appname=gradlew','-classpath',(Join-Path $projectRoot 'gradle/wrapper/gradle-wrapper.jar'),'org.gradle.wrapper.GradleWrapperMain')+$arguments)
            $junitDirectory=Join-Path $runRoot 'android-junit'
            $null=New-Item -ItemType Directory -Path $junitDirectory
            Copy-Item -Path 'apps/android/build/test-results/testDebugUnitTest/TEST-*.xml' -Destination $junitDirectory
            Read-JUnit $junitDirectory
            Copy-Item -LiteralPath 'apps/android/build/reports/lint-results-debug.xml' -Destination $runRoot
            Write-QAFileHash 'apps/android/build/outputs/apk/debug/app-debug.apk'
        }
    }

    if ($EmulatorSerial -and -not $SkipAndroid) {
        Invoke-TestGroup 'android-ui-blackbox' {
            if (@($results | Where-Object { $_.name -eq 'android-build-unit-lint' -and $_.status -eq 'PASS' }).Count -eq 0) { throw 'Build did not pass; refusing to install stale APKs.' }
            if (-not $AdbPath) { $AdbPath=Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe' }
            if (-not (Test-Path -LiteralPath $AdbPath)) { throw 'Pass -AdbPath with the Android SDK adb.exe path.' }
            $qemu=(Invoke-QACommand $AdbPath @('-s',$EmulatorSerial,'shell','getprop','ro.kernel.qemu') -Capture -TimeoutSeconds 30).Trim()
            $avd=Invoke-QACommand $AdbPath @('-s',$EmulatorSerial,'emu','avd','name') -Capture -TimeoutSeconds 30
            if ($qemu -ne '1' -or $avd -notmatch '(?m)^SpaceFitQA\s*$') { throw 'Only the dedicated SpaceFitQA emulator is allowed for state-changing UI tests.' }
            Invoke-QACommand $AdbPath @('-s',$EmulatorSerial,'install','-r','apps/android/build/outputs/apk/debug/app-debug.apk')
            Invoke-QACommand $AdbPath @('-s',$EmulatorSerial,'install','-r','apps/android/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
            $output=Invoke-QACommand $AdbPath @('-s',$EmulatorSerial,'shell','am','instrument','-w','-e','class','com.example.WorkspaceSmokeTest','com.spacefit.app.test/androidx.test.runner.AndroidJUnitRunner') -Capture -TimeoutSeconds 600
            Write-Output $output
            if ($output -notmatch 'OK \([1-9][0-9]* tests?\)' -or $output -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed') { throw 'Instrumentation did not report a successful test run.' }
            $testCount=[regex]::Match($output,'OK \(([0-9]+) tests?\)').Groups[1].Value
            Write-Output "total=$testCount passed=$testCount failed=0 errors=0 skipped=0"
            Invoke-QACommand $AdbPath @('-s',$EmulatorSerial,'pull','/sdcard/Android/data/com.spacefit.app/files/qa',(Join-Path $runRoot 'emulator-evidence'))
        }
    } else {
        Add-Result 'android-ui-blackbox' $(if($RequireDevice){'FAIL'}else{'SKIP'}) 'No QA emulator selected; pass -EmulatorSerial emulator-NNNN.'
    }
    if($RequireNative){
        Invoke-TestGroup 'native-reconstruction-tools' {
            # Sequential doctor: 7*60s probes plus 6*10s Docker cleanup, with margin.
            Invoke-QACommand $PythonPath @('-B','backend/scripts/doctor.py','--timeout','60','--diagnostics-dir',(Join-Path $runRoot 'native-diagnostics')) -TimeoutSeconds 520
        }
    }else{
        Add-Result 'native-reconstruction-tools' 'MANUAL' 'Pass -RequireNative to check executable startup/DLL loading. Path existence is not readiness.'
    }
    Add-Result 'manual-real-scan-ar' 'MANUAL' 'See docs/TESTING.md; automated fixtures do not verify real reconstruction/AR accuracy.'
} finally {
    $env:JAVA_HOME=$oldJava
    $env:BLENDER_BIN=$oldBlender
    $env:SPACEFIT_OPENMVS_DOCKER_TEST_CONFIG=$oldDockerTestConfig
    $env:SPACEFIT_COLMAP_DOCKER_TEST_CONFIG=$oldColmapTestConfig
    Pop-Location
    $results | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $runRoot 'summary.json') -Encoding UTF8
    $results | Format-Table -AutoSize | Out-String | Set-Content -LiteralPath (Join-Path $runRoot 'summary.txt') -Encoding UTF8
    Write-Host ('Reports: ' + $runRoot)
}
if (@($results | Where-Object status -eq 'FAIL').Count -gt 0) { exit 1 }
exit 0
