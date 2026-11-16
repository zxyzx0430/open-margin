#Requires -Version 5.1
param(
    [string]$ModulePath = '',
    [switch]$CI
)

$ErrorActionPreference = 'Stop'

if (-not $ModulePath) {
    $ModulePath = 'E:\DevCaches\PowerShellModules'
    if (-not (Test-Path 'E:\')) {
        $ModulePath = Join-Path $env:LOCALAPPDATA 'PowerShellModules'
    }
}
if (-not (Test-Path $ModulePath)) {
    New-Item -ItemType Directory -Force -Path $ModulePath | Out-Null
}
$env:PSModulePath = $ModulePath + ';' + $env:PSModulePath

function Install-PesterModule {
    Write-Host "Pester 5 not found; installing into $ModulePath"
    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
        if (-not (Get-PackageProvider -Name NuGet -ErrorAction SilentlyContinue)) {
            Install-PackageProvider -Name NuGet -MinimumVersion 2.8.5.201 -Force -Scope CurrentUser | Out-Null
        }
        try { Set-PSRepository -Name PSGallery -InstallationPolicy Trusted -ErrorAction SilentlyContinue } catch { }
        Save-Module -Name Pester -Path $ModulePath -RequiredVersion 5.7.1 -Force -ErrorAction Stop
    } catch {
        Write-Warning ('Save-Module failed: ' + $_.Exception.Message)
        Install-Module -Name Pester -MinimumVersion 5.0.0 -Scope CurrentUser -Force -SkipPublisherCheck -ErrorAction Stop
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
    Install-PesterModule
    $pester = Get-Module -ListAvailable -Name Pester |
        Where-Object { $_.Version -ge [version]'5.0.0' -and $_.Version -lt [version]'6.0.0' } |
        Sort-Object Version -Descending |
        Select-Object -First 1
}
if (-not $pester) {
    throw 'Pester 5 could not be installed. Install it manually: Save-Module Pester -Path ' + $ModulePath
}
Import-Module $pester.Path -Force

Write-Host ('Using Pester ' + $pester.Version + ' from ' + $pester.Path)
$configuration = New-PesterConfiguration
$configuration.Run.Path = $PSScriptRoot
$configuration.Run.PassThru = $true
$configuration.Output.Verbosity = $(if ($CI) { 'Normal' } else { 'Detailed' })
$configuration.TestResult.Enabled = $CI.IsPresent
if ($CI) { $configuration.TestResult.OutputPath = Join-Path $PSScriptRoot 'pester-results.xml' }

$result = Invoke-Pester -Configuration $configuration
if ($result.FailedCount -gt 0) {
    Write-Error ($result.FailedCount.ToString() + ' Pester test(s) failed.')
    exit 1
}
Write-Host ('Pester passed: ' + $result.PassedCount + ' tests in ' + $result.Duration.TotalSeconds.ToString('0.0') + 's')
exit 0
