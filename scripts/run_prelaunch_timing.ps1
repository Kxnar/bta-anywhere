param(
    [Parameter(Mandatory = $true)]
    [string]$BaselineCheckout,
    [Parameter(Mandatory = $true)]
    [string]$JavaHome,
    [ValidateRange(1, 10)]
    [int]$Rounds = 3
)

$ErrorActionPreference = 'Stop'
$candidate = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$baseline = (Resolve-Path -LiteralPath $BaselineCheckout).Path
$javaHomePath = (Resolve-Path -LiteralPath $JavaHome).Path
$javaExecutable = Join-Path $javaHomePath 'bin\java.exe'
if (-not (Test-Path -LiteralPath $javaExecutable -PathType Leaf)) {
    throw 'JavaHome must contain a Windows java.exe.'
}
if ($candidate -eq $baseline) {
    throw 'Use a separate detached historical baseline checkout.'
}
if (-not [System.Runtime.InteropServices.RuntimeInformation]::IsOSPlatform(
        [System.Runtime.InteropServices.OSPlatform]::Windows) -or
    [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture -ne
        [System.Runtime.InteropServices.Architecture]::X64) {
    throw 'Pre-launch timing requires Windows x86-64.'
}
$baselineCommit = (& git -C $baseline rev-parse HEAD).Trim()
$candidateCommit = (& git -C $candidate rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or
    $baselineCommit -ne '05d0c2d4c54a0525316aa2bdd2621d73f0c20456') {
    throw 'Historical baseline commit does not match the timing protocol.'
}
& git -C $baseline apply --unidiff-zero --reverse --check (Join-Path $PSScriptRoot 'prelaunch-baseline-instrumentation.patch')
if ($LASTEXITCODE -ne 0) {
    throw 'Historical baseline timing hook is not applied.'
}
$runId = [guid]::NewGuid().ToString('N')
$runDirectory = Join-Path $candidate ('.dev\prelaunch-timing\run-' + $runId)
New-Item -ItemType Directory -Path $runDirectory -ErrorAction Stop | Out-Null
$os = Get-CimInstance -ClassName Win32_OperatingSystem
$cpu = Get-CimInstance -ClassName Win32_Processor | Select-Object -First 1
$computer = Get-CimInstance -ClassName Win32_ComputerSystem
$batteries = @(Get-CimInstance -ClassName Win32_Battery -ErrorAction SilentlyContinue)
$manifest = @{
    schemaVersion = 1
    startedAtUtc = [DateTime]::UtcNow.ToString('o')
    candidateCommit = $candidateCommit
    baselineCommit = $baselineCommit
    candidateStatus = @(& git -C $candidate status --porcelain)
    baselineStatus = @(& git -C $baseline status --porcelain)
    baselineInstrumentationSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $PSScriptRoot 'prelaunch-baseline-instrumentation.patch')).Hash
    sharedProbeSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $candidate 'bta-mod\src\test\java\io\github\kxnar\btaanywhere\mod\hosting\PrelaunchTimingTest.java')).Hash
    windowsBuild = $os.BuildNumber
    os = $os.Caption
    cpu = $cpu.Name
    logicalCores = [Environment]::ProcessorCount
    ramBytes = $computer.TotalPhysicalMemory
    powerSource = $(if ($batteries.Count -eq 0) { 'desktop/no battery reported' } else {
        'battery status codes: ' + (($batteries | ForEach-Object { $_.BatteryStatus }) -join ',')
    })
    java = (& $javaExecutable -version 2>&1 | Select-Object -First 1).ToString()
    rust = (& rustc --version).Trim()
    rounds = $Rounds
    warmupsPerModePerRound = 5
    measuredSamplesPerModePerRound = 30
    worldBytes = 2097152
    boundary = 'continueAfterWorldClosed() call to BEFORE_SUPERVISOR_LAUNCH callback'
    gradleCommand = '.\gradlew.bat --no-daemon :bta-mod:test --tests io.github.kxnar.btaanywhere.mod.hosting.PrelaunchTimingTest --rerun-tasks'
    interpretation = 'test-only instrumented synthetic world; no game client or server process'
}
$manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $runDirectory 'manifest.json') -Encoding utf8
$previousJavaHome = $env:JAVA_HOME
$previousResults = $env:BTA_PRELAUNCH_RESULTS
$env:JAVA_HOME = $javaHomePath
try {
    for ($round = 1; $round -le $Rounds; $round++) {
        $order = if ($round % 2 -eq 1) { @('candidate', 'baseline') } else { @('baseline', 'candidate') }
        foreach ($label in $order) {
            $checkout = if ($label -eq 'candidate') { $candidate } else { $baseline }
            $resultPath = Join-Path $runDirectory "$label-$round.jsonl"
            $logPath = Join-Path $runDirectory "$label-$round.log"
            $env:BTA_PRELAUNCH_RESULTS = $resultPath
            Push-Location -LiteralPath $checkout
            try {
                & .\gradlew.bat --no-daemon :bta-mod:test --tests io.github.kxnar.btaanywhere.mod.hosting.PrelaunchTimingTest --rerun-tasks *> $logPath
                if ($LASTEXITCODE -ne 0) {
                    throw "Pre-launch timing failed for $label round $round; inspect $logPath"
                }
            } finally {
                Pop-Location
            }
        }
    }
} finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:BTA_PRELAUNCH_RESULTS = $previousResults
}
$summaryArguments = @((Join-Path $PSScriptRoot 'summarize_prelaunch_timing.py'))
for ($round = 1; $round -le $Rounds; $round++) {
    $summaryArguments += @('--baseline', (Join-Path $runDirectory "baseline-$round.jsonl"))
    $summaryArguments += @('--candidate', (Join-Path $runDirectory "candidate-$round.jsonl"))
}
$summaryArguments += @('--output', (Join-Path $runDirectory 'summary'))
& python @summaryArguments
if ($LASTEXITCODE -ne 0) {
    throw "Could not summarize pre-launch timing; raw results are at $runDirectory"
}
Write-Host "Pre-launch timing raw results and summary: $runDirectory"
