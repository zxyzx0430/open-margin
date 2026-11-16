#Requires -Version 5.1
<#
.SYNOPSIS
    Ensure installed PowerShell scripts are UTF-8 with BOM.

.DESCRIPTION
    Windows PowerShell 5.1 reads a UTF-8 file without BOM using the active ANSI
    code page, which garbles non-ASCII literals. The source tree intentionally
    keeps normal Git files (no BOM), so the installer normalizes the installed
    copies once. This is idempotent and only rewrites files that are valid UTF-8
    and contain non-ASCII characters.

    Run from the elevated installer after [Files] has copied the payload.
#>
param(
    [Parameter(Mandatory = $true)][string]$Root
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path $Root)) {
    Write-Warning ('Root not found: ' + $Root)
    exit 0
}

$utf8Strict = New-Object System.Text.UTF8Encoding($false, $true)
$utf8Bom = New-Object System.Text.UTF8Encoding($true)
$fixed = 0
$skipped = 0

$files = @(Get-ChildItem -Path $Root -Recurse -File -Include '*.ps1', '*.psm1', '*.psd1' -ErrorAction SilentlyContinue)
foreach ($file in $files) {
    try {
        $bytes = [IO.File]::ReadAllBytes($file.FullName)
        $hasBom = ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF)
        if ($hasBom) { continue }

        $text = $utf8Strict.GetString($bytes)
        if ($text -notmatch '[^\x00-\x7F]') { continue }

        [IO.File]::WriteAllText($file.FullName, $text, $utf8Bom)
        $fixed++
    } catch {
        $skipped++
        Write-Warning ('Could not normalize encoding: ' + $file.FullName + ' :: ' + $_.Exception.Message)
    }
}

Write-Host ('Encoding normalization: fixed=' + $fixed + ' skipped=' + $skipped)
exit 0
