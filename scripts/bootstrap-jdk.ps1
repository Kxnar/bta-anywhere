[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$jdkVersion = '21.0.12.1+1'
$archiveName = 'OpenJDK21U-jdk_x64_windows_hotspot_21.0.12.1_1.zip'
$archiveSha256 = 'f9d6e191ab098c0d416e7d588a24420a8621cd2f4720dab2459b8b7b2d2d8b4e'
$archiveUrl = "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/$archiveName"

$repository = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$toolRoot = [System.IO.Path]::GetFullPath((Join-Path $repository '.tools'))
$jdkHome = [System.IO.Path]::GetFullPath((Join-Path $toolRoot 'jdk-21'))

if ((Test-Path -LiteralPath (Join-Path $jdkHome 'bin\java.exe')) -and
	(Select-String -LiteralPath (Join-Path $jdkHome 'release') -SimpleMatch 'JAVA_VERSION="21.0.12.1"' -Quiet)) {
	Write-Host "Temurin $jdkVersion is already installed at $jdkHome"
	Write-Host "For this PowerShell session: `$env:JAVA_HOME='$jdkHome'"
	exit 0
}

New-Item -ItemType Directory -Path $toolRoot -Force | Out-Null
$workRoot = [System.IO.Path]::GetFullPath((Join-Path $toolRoot ("jdk-bootstrap-" + [guid]::NewGuid().ToString('N'))))
$archive = Join-Path $workRoot $archiveName
$extractRoot = Join-Path $workRoot 'extract'
$oldJdk = $null
New-Item -ItemType Directory -Path $extractRoot -Force | Out-Null

try {
	Write-Host "Downloading Eclipse Temurin $jdkVersion..."
	Invoke-WebRequest -UseBasicParsing -Uri $archiveUrl -OutFile $archive
	$actualSha256 = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
	if ($actualSha256 -ne $archiveSha256) {
		throw "Temurin archive checksum mismatch: expected $archiveSha256, got $actualSha256"
	}

	Expand-Archive -LiteralPath $archive -DestinationPath $extractRoot
	$extracted = @(Get-ChildItem -LiteralPath $extractRoot -Directory)
	if ($extracted.Count -ne 1 -or -not (Test-Path -LiteralPath (Join-Path $extracted[0].FullName 'bin\java.exe'))) {
		throw 'Unexpected Temurin archive layout'
	}

	if (Test-Path -LiteralPath $jdkHome) {
		$oldJdk = [System.IO.Path]::GetFullPath((Join-Path $toolRoot ("jdk-old-" + [guid]::NewGuid().ToString('N'))))
		Move-Item -LiteralPath $jdkHome -Destination $oldJdk
	}
	try {
		Move-Item -LiteralPath $extracted[0].FullName -Destination $jdkHome
		if (-not (Test-Path -LiteralPath (Join-Path $jdkHome 'bin\java.exe')) -or
			-not (Select-String -LiteralPath (Join-Path $jdkHome 'release') -SimpleMatch 'JAVA_VERSION="21.0.12.1"' -Quiet)) {
			throw 'Installed Temurin directory failed post-install validation'
		}
	} catch {
		if (Test-Path -LiteralPath $jdkHome) {
			$failedJdk = [System.IO.Path]::GetFullPath((Join-Path $workRoot 'failed-jdk'))
			Move-Item -LiteralPath $jdkHome -Destination $failedJdk
		}
		if ($null -ne $oldJdk -and (Test-Path -LiteralPath $oldJdk)) {
			Move-Item -LiteralPath $oldJdk -Destination $jdkHome
		}
		throw
	}

	if ($null -ne $oldJdk -and (Test-Path -LiteralPath $oldJdk)) {
		if (-not $oldJdk.StartsWith($toolRoot + [System.IO.Path]::DirectorySeparatorChar)) {
			throw "Refusing to remove unexpected path $oldJdk"
		}
		Remove-Item -LiteralPath $oldJdk -Recurse -Force
	}

	Write-Host "Installed checksum-verified Temurin $jdkVersion at $jdkHome"
	Write-Host "For this PowerShell session: `$env:JAVA_HOME='$jdkHome'"
} finally {
	if ((Test-Path -LiteralPath $workRoot) -and
		$workRoot.StartsWith($toolRoot + [System.IO.Path]::DirectorySeparatorChar)) {
		Remove-Item -LiteralPath $workRoot -Recurse -Force
	}
}
