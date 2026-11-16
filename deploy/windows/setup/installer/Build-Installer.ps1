#Requires -Version 5.1
<#
.SYNOPSIS
    Build OpenMargin-Setup.exe with Inno Setup.

.DESCRIPTION
    Locates ISCC.exe (Inno Setup 6.3+) and compiles installer/OpenMargin.iss.
    If Inno Setup is missing, the script can install JRSoftware.InnoSetup with
    winget. It never installs unrelated software and never touches secrets.

    The produced installer is unsigned by design for v1. The script prints and
    writes a SHA256 file next to the executable for the GitHub Release page.
#>
param(
    [string]$Version = '',
    [string]$OutputDir = '',
    [switch]$NoAutoInstall,
    [switch]$Quiet
)

$ErrorActionPreference = 'Stop'

$installerRoot = $PSScriptRoot
$repoRoot = (Resolve-Path (Join-Path $installerRoot '..\..\..\..')).Path
$issPath = Join-Path $installerRoot 'OpenMargin.iss'
if (-not (Test-Path $issPath)) { throw ('Inno Setup script not found: ' + $issPath) }

function Write-Step {
    param([string]$Message)
    if (-not $Quiet) { Write-Host ('[build-installer] ' + $Message) }
}

function Find-Iscc {
    $candidates = New-Object System.Collections.Generic.List[string]
    if ($env:INNO_SETUP_HOME) { $candidates.Add((Join-Path $env:INNO_SETUP_HOME 'ISCC.exe')) }
    foreach ($base in @($env:ProgramFiles, ${env:ProgramFiles(x86)})) {
        if ($base) {
            $candidates.Add((Join-Path $base 'Inno Setup 6\ISCC.exe'))
            foreach ($dir in @(Get-ChildItem -Path $base -Directory -Filter 'Inno Setup *' -ErrorAction SilentlyContinue)) {
                $candidates.Add((Join-Path $dir.FullName 'ISCC.exe'))
            }
        }
    }
    $command = Get-Command iscc.exe -ErrorAction SilentlyContinue
    if ($command) { $candidates.Add($command.Source) }

    $registryKeys = @(
        'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\Inno Setup 6_is1',
        'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\Inno Setup 6_is1'
    )
    foreach ($key in $registryKeys) {
        try {
            $location = (Get-ItemProperty -Path $key -ErrorAction Stop).InstallLocation
            if ($location) { $candidates.Add((Join-Path $location 'ISCC.exe')) }
        } catch { }
    }

    # winget installs portable packages under LocalAppData; PATH may not be
    # refreshed in the current process.
    $wingetPackages = Join-Path $env:LOCALAPPDATA 'Microsoft\WinGet\Packages'
    if (Test-Path $wingetPackages) {
        Get-ChildItem -Path $wingetPackages -Filter ISCC.exe -Recurse -File -ErrorAction SilentlyContinue |
            ForEach-Object { $candidates.Add($_.FullName) }
    }

    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path $candidate)) {
            return (Resolve-Path $candidate).Path
        }
    }
    return $null
}

function Resolve-BuildVersion {
    param([string]$Requested)
    if ($Requested) { return $Requested.TrimStart('v') }
    $git = Get-Command git.exe -ErrorAction SilentlyContinue
    if (-not $git) { $git = Get-Command git -ErrorAction SilentlyContinue }
    if ($git) {
        try {
            $tag = (& $git.Source -C $repoRoot describe --tags --always 2>$null | Select-Object -First 1)
            if ($LASTEXITCODE -eq 0 -and $tag) { return ([string]$tag).Trim().TrimStart('v') }
        } catch { }
    }
    return '1.3.0'
}

function ConvertTo-NumericVersion {
    param([string]$Value)
    $match = [regex]::Match($Value, '(\d+)\.(\d+)\.(\d+)(?:\.(\d+))?')
    if (-not $match.Success) { return '1.3.0.0' }
    $parts = @(
        [int]$match.Groups[1].Value,
        [int]$match.Groups[2].Value,
        [int]$match.Groups[3].Value,
        $(if ($match.Groups[4].Success) { [int]$match.Groups[4].Value } else { 0 })
    )
    foreach ($part in $parts) {
        if ($part -lt 0 -or $part -gt 65535) { return '1.3.0.0' }
    }
    return ($parts -join '.')
}

if (-not $Version) { $Version = Resolve-BuildVersion '' }
if (-not $OutputDir) { $OutputDir = Join-Path $installerRoot 'Output' }
$OutputDir = [IO.Path]::GetFullPath($OutputDir)
if (-not (Test-Path $OutputDir)) { New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null }

$iscc = Find-Iscc
if (-not $iscc) {
    if ($NoAutoInstall) {
        Write-Step 'Inno Setup (ISCC.exe) not found.'
        Write-Step 'Install Inno Setup 6.3+ from https://jrsoftware.org/isdl.php'
        Write-Step 'or run: winget install --id JRSoftware.InnoSetup --exact'
        exit 2
    }

    $winget = Get-Command winget.exe -ErrorAction SilentlyContinue
    if (-not $winget) { $winget = Get-Command winget -ErrorAction SilentlyContinue }
    if (-not $winget) {
        Write-Step 'Inno Setup (ISCC.exe) not found and winget is unavailable.'
        Write-Step 'Install Inno Setup 6.3+ from https://jrsoftware.org/isdl.php and re-run this script.'
        exit 2
    }

    Write-Step 'Inno Setup not found; installing the related package JRSoftware.InnoSetup via winget...'
    & $winget.Source install --id JRSoftware.InnoSetup --exact --silent --accept-package-agreements --accept-source-agreements --disable-interactivity
    $iscc = Find-Iscc
    if (-not $iscc) {
        Write-Step 'winget finished but ISCC.exe is still not found.'
        Write-Step 'Install Inno Setup 6.3+ from https://jrsoftware.org/isdl.php and re-run this script.'
        exit 2
    }
}

Write-Step ('using ISCC: ' + $iscc)
Write-Step ('version: ' + $Version)
Write-Step ('output: ' + $OutputDir)

$numericVersion = ConvertTo-NumericVersion $Version
$arguments = @(
    ('/DAppVersion=' + $Version),
    ('/DVersionInfoVersion=' + $numericVersion),
    ('/DBuildOutputDir=' + $OutputDir),
    $issPath
)
& $iscc @arguments
if ($LASTEXITCODE -ne 0) {
    throw ('Inno Setup failed with exit code ' + $LASTEXITCODE)
}

$exePath = Join-Path $OutputDir 'OpenMargin-Setup.exe'
if (-not (Test-Path $exePath)) {
    throw ('Installer was not produced at ' + $exePath)
}

$hash = (Get-FileHash -Path $exePath -Algorithm SHA256).Hash.ToLowerInvariant()
$shaPath = $exePath + '.sha256'
$utf8 = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText($shaPath, ($hash + '  ' + [IO.Path]::GetFileName($exePath) + "`r`n"), $utf8)

$info = Get-Item $exePath
Write-Step ('built: ' + $exePath)
Write-Step ('size: ' + [math]::Round($info.Length / 1MB, 2) + ' MiB')
Write-Step ('sha256: ' + $hash)
Write-Step 'code signing: skipped (v1 has no signing certificate by design)'
Write-Output $exePath
Write-Output $shaPath
