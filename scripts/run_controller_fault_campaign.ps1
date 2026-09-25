param(
    [ValidateSet('smoke', 'full')]
    [string]$Profile = 'smoke'
)

$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$outputRoot = Join-Path $repository '.dev\controller-fault-campaign'
$runId = [guid]::NewGuid().ToString('N')
$runDirectory = Join-Path $outputRoot ('run-' + $runId)

if (-not [System.Runtime.InteropServices.RuntimeInformation]::IsOSPlatform(
        [System.Runtime.InteropServices.OSPlatform]::Windows) -or
    [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture -ne
        [System.Runtime.InteropServices.Architecture]::X64) {
    throw 'The controller fault campaign requires Windows x86-64.'
}

$commit = (& git -C $repository rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $commit -notmatch '^[a-f0-9]{40}$') {
    throw 'Could not record the Git commit.'
}
$dirty = @(& git -C $repository status --porcelain)
if ($LASTEXITCODE -ne 0) {
    throw 'Could not inspect Git status.'
}
if ($Profile -eq 'full' -and $dirty.Count -gt 0) {
    throw 'Commit or preserve all changes before a full evidence run.'
}

$operatingSystem = Get-CimInstance -ClassName Win32_OperatingSystem
$processor = Get-CimInstance -ClassName Win32_Processor | Select-Object -First 1
$computer = Get-CimInstance -ClassName Win32_ComputerSystem
$rustVersion = (& rustc --version).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($rustVersion)) {
    throw 'Could not record the Rust toolchain.'
}

$values = @{
    BTA_CONTROLLER_CAMPAIGN = $Profile
    BTA_CONTROLLER_RUN_ID = $runId
    BTA_CONTROLLER_GIT_COMMIT = $commit
    BTA_CONTROLLER_WORKTREE_STATUS = $(if ($dirty.Count -eq 0) { 'clean' } else { 'dirty' })
    BTA_CONTROLLER_WINDOWS_BUILD = [string]$operatingSystem.BuildNumber
    BTA_CONTROLLER_CPU = [string]$processor.Name
    BTA_CONTROLLER_RAM_BYTES = [string]$computer.TotalPhysicalMemory
    BTA_CONTROLLER_RUST_VERSION = $rustVersion
}
$previous = @{}
foreach ($name in $values.Keys) {
    $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    [Environment]::SetEnvironmentVariable($name, $values[$name], 'Process')
}

Write-Host "Controller fault campaign results: $runDirectory"
try {
    Push-Location -LiteralPath $repository
    try {
        & .\gradlew.bat --no-daemon :bta-mod:test --tests io.github.kxnar.btaanywhere.mod.hosting.HostControllerFaultCampaignTest --rerun-tasks
        if ($LASTEXITCODE -ne 0) {
            throw "Controller fault campaign failed. Inspect $runDirectory and any retained fixture."
        }
    } finally {
        Pop-Location
    }
} finally {
    foreach ($name in $values.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process')
    }
}
