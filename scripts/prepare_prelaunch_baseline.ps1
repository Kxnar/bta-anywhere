param(
    [Parameter(Mandatory = $true)]
    [string]$BaselineCheckout
)

$ErrorActionPreference = 'Stop'
$candidate = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$baseline = (Resolve-Path -LiteralPath $BaselineCheckout).Path
$expected = '05d0c2d4c54a0525316aa2bdd2621d73f0c20456'

if ($baseline -eq $candidate) {
    throw 'The historical baseline must be a separate detached worktree.'
}
$head = (& git -C $baseline rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $head -ne $expected) {
    throw "Expected historical baseline commit $expected."
}
$dirty = @(& git -C $baseline status --porcelain)
if ($LASTEXITCODE -ne 0 -or $dirty.Count -gt 0) {
    throw 'The baseline checkout must be clean before instrumentation.'
}
$patch = Join-Path $PSScriptRoot 'prelaunch-baseline-instrumentation.patch'
& git -C $baseline apply --unidiff-zero --check $patch
if ($LASTEXITCODE -ne 0) {
    throw 'The historical source no longer matches the timing patch.'
}
& git -C $baseline apply --unidiff-zero $patch
if ($LASTEXITCODE -ne 0) {
    throw 'Could not apply the test-only timing patch.'
}
$relative = 'bta-mod\src\main\java\io\github\kxnar\btaanywhere\mod\hosting\HostingFaults.java'
Copy-Item -LiteralPath (Join-Path $candidate $relative) -Destination (Join-Path $baseline $relative)
$relative = 'bta-mod\src\test\java\io\github\kxnar\btaanywhere\mod\hosting\PrelaunchTimingTest.java'
Copy-Item -LiteralPath (Join-Path $candidate $relative) -Destination (Join-Path $baseline $relative)
Write-Host "Instrumented detached baseline: $baseline"
Write-Host 'This checkout is deliberately dirty for the test-only timing probe.'
