#Requires -Version 5.1
<#
.SYNOPSIS
    Run the Windows setup real-install acceptance tests.

.DESCRIPTION
    Runs deploy\windows\setup\tests\Acceptance.Tests.ps1 with Pester 5.
    The acceptance tests start temporary wizard servers on loopback ports
    18790+, compile fake tailscale/netsh commands in a temp directory, and
    never touch a real Open Margin install or the real state root.

    Exit code 0 means every acceptance assertion passed. A failing assertion
    is a product bug report for the Windows installer/wizard.

.PARAMETER ModulePath
    Optional directory containing Pester 5. Defaults to
    E:\DevCaches\PowerShellModules when present, then the per-user module cache.

.PARAMETER TestPath
    Optional path to Acceptance.Tests.ps1. Defaults to the setup test file.

.PARAMETER CI
    Emit a TRX result file and less verbose output.

.PARAMETER OutputFile
    Optional TRX output path. Implies a result file.
#>
param(
    [string]$ModulePath = '',
    [string]$TestPath = '',
    [switch]$CI,
    [string]$OutputFile = ''
)

$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $TestPath) {
    $TestPath = Join-Path $repoRoot 'deploy\windows\setup\tests\Acceptance.Tests.ps1'
}
if (-not (Test-Path $TestPath)) {
    throw ('acceptance test file not found: ' + $TestPath)
}

$moduleDirs = [System.Collections.Generic.List[string]]::new()
if ($ModulePath) { $moduleDirs.Add($ModulePath) }
if (Test-Path 'E:\DevCaches\PowerShellModules') { $moduleDirs.Add('E:\DevCaches\PowerShellModules') }
$moduleDirs.Add((Join-Path $env:LOCALAPPDATA 'PowerShellModules'))
foreach ($dir in $moduleDirs) {
    if ((Test-Path $dir) -and ($env:PSModulePath -notlike ($dir + '*'))) {
        $env:PSModulePath = $dir + ';' + $env:PSModulePath
    }
}

$pester = Get-Module -ListAvailable -Name Pester |
    Where-Object { $_.Version -ge [version]'5.0.0' -and $_.Version -lt [version]'6.0.0' } |
    Sort-Object Version -Descending |
    Select-Object -First 1
if (-not $pester) {
    $pester = Get-Module -ListAvailable -Name Pester |
        Where-Object { $_.Version -ge [version]'5.0.0' } |
        Sort-Object Version -Descending |
        Select-Object -First 1
}
if (-not $pester) {
    throw 'Pester 5 is required. Install it with: Save-Module Pester -Path <dir> -RequiredVersion 5.7.1'
}
Import-Module $pester.Path -Force
Write-Host ('Using Pester ' + $pester.Version + ' from ' + $pester.Path)

$configuration = New-PesterConfiguration
$configuration.Run.Path = $TestPath
$configuration.Run.PassThru = $true
$configuration.Output.Verbosity = $(if ($CI) { 'Normal' } else { 'Detailed' })
if ($CI -or $OutputFile) {
    $configuration.TestResult.Enabled = $true
    if (-not $OutputFile) {
        $OutputFile = Join-Path $repoRoot 'deploy\windows\setup\tests\acceptance-results.xml'
    }
    $configuration.TestResult.OutputPath = $OutputFile
}

$result = Invoke-Pester -Configuration $configuration
Write-Host ('Acceptance passed: ' + $result.PassedCount + '; failed: ' + $result.FailedCount + '; skipped: ' + $result.SkippedCount + '; duration: ' + $result.Duration.TotalSeconds.ToString('0.0') + 's')
if ($result.FailedCount -gt 0) {
    exit 1
}
exit 0
