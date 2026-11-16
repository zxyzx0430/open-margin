#Requires -Version 5.1
param(
    [Parameter(Position = 0)]
    [ValidateSet('status', 'check', 'install-deps', 'deploy', 'verify', 'disable', 'enable', 'repair', 'logs', 'rotate-token', 'pair-qr', 'pause-watchdog', 'resume-watchdog', 'install-watchdog', 'uninstall-watchdog')]
    [string]$Command = 'status',

    [string]$Mode = 'remote_sync',
    [string]$AdminUser = '',
    [string]$AdminPassword = '',
    [string]$AdminPasswordFile = '',
    [string]$AdminEmail = '',
    [switch]$AckPublic,
    [switch]$SkipFunnel,
    [switch]$InstallWatchdog,
    [switch]$Json,
    [switch]$NonInteractive,
    [switch]$DryRun,

    [string]$StateRoot = '',
    [string]$RepoRoot = '',
    [string]$ProjectName = 'personal-finance-system',

    [int]$ApiPort = 8767,
    [int]$WallosPort = 18082,
    [int]$GatewayPort = 18768,
    [int]$SetupPort = 18770
)

$ErrorActionPreference = 'Stop'
$modulePath = Join-Path $PSScriptRoot 'OpenMargin.Setup.psd1'
Import-Module $modulePath -Force

function Write-OmCliResult {
    param($Result, [int]$ExitCode = 0)
    if ($Json) {
        $Result | ConvertTo-Json -Depth 12
    } else {
        if ($Result -is [string]) {
            Write-Output $Result
        } else {
            Write-Output ($Result | Format-List * | Out-String)
        }
    }
    exit $ExitCode
}

function Get-OmCliPassword {
    if ($AdminPassword) { return $AdminPassword }
    if ($AdminPasswordFile) {
        if (-not (Test-Path $AdminPasswordFile)) { throw ('password file not found: ' + $AdminPasswordFile) }
        return (Get-Content -Path $AdminPasswordFile -Raw -Encoding UTF8).Trim()
    }
    if ($NonInteractive) { throw 'admin password is required in non-interactive mode' }
    $secure = Read-Host -AsSecureString '管理员密码'
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}

try {
    if (-not $RepoRoot) {
        $RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
    }
    if (-not $StateRoot) {
        $StateRoot = Join-Path $env:LOCALAPPDATA 'OpenMargin'
    }
    Initialize-OmPaths -StateRoot $StateRoot -RepoRoot $RepoRoot
    $ports = [pscustomobject]@{ api = $ApiPort; wallos = $WallosPort; gateway = $GatewayPort; setup = $SetupPort }
    $state = Read-OmState
    $state.ports = $ports
    Save-OmState $state

    switch ($Command) {
        'status' {
            $current = Read-OmState
            $checks = @(Get-OmEnvironmentChecks -SkipNetwork -Ports $ports -RepoRoot $RepoRoot)
            $summary = Get-OmCheckSummary $checks
            Write-OmCliResult ([pscustomobject]@{
                ok = $true
                state = $current
                checks = $checks
                summary = $summary
                drift = @(Test-OmStateDrift $current)
            })
        }
        'check' {
            $checks = @(Get-OmEnvironmentChecks -Ports $ports -RepoRoot $RepoRoot)
            $summary = Get-OmCheckSummary $checks
            Write-OmCliResult ([pscustomobject]@{ ok = $summary.ok; checks = $checks; summary = $summary })
        }
        'install-deps' {
            $result = Install-OmDependencies -SetupPort $SetupPort -DryRun:$DryRun
            if ($result.pendingReboot) {
                Set-OmStateValue -Name pendingReboot -Value $true | Out-Null
                Set-OmStep 'WAITING_REBOOT' | Out-Null
            }
            Write-OmCliResult $result $(if ($result.ok) { 0 } else { 30 })
        }
        'deploy' {
            if (-not $AdminUser) { throw 'AdminUser is required' }
            if (-not $SkipFunnel -and -not $AckPublic) { throw 'AckPublic is required for remote deployment' }
            $password = Get-OmCliPassword
            $result = Invoke-OmRemoteDeploy -RepoRoot $RepoRoot -Ports $ports -AdminUser $AdminUser -AdminPassword $password -AdminEmail $AdminEmail -ProjectName $ProjectName -SkipFunnel:$SkipFunnel -InstallWatchdog:$InstallWatchdog -DryRun:$DryRun
            Write-OmCliResult $result $(if ($result.ok) { 0 } elseif ($result.action) { 10 } else { 20 })
        }
        'verify' {
            $current = Read-OmState
            $base = $(if ($current.publicSyncUrl) { $current.publicSyncUrl } else { 'http://127.0.0.1:' + $GatewayPort + '/finance' })
            $token = Get-OmDeviceTokenFromFile
            if (-not $token) { throw 'device token missing; run deploy first' }
            $result = Invoke-OmVerify -BaseUrl $base -Token $token -ProjectName $ProjectName
            Write-OmCliResult $result $(if ($result.ok) { 0 } else { 20 })
        }
        'disable' {
            $result = Disable-OmFunnel -DryRun:$DryRun
            if ($result.ok) {
                $null = Set-OmStep 'DISABLED'
                $null = Set-OmStateValue -Name mode -Value 'disabled'
            }
            Write-OmCliResult $result $(if ($result.ok) { 0 } else { 20 })
        }
        'enable' {
            $result = Enable-OmFunnel -GatewayPort $GatewayPort -DryRun:$DryRun
            if ($result.ok) {
                $null = Set-OmStateValue -Name publicSyncUrl -Value $result.url
                $null = Set-OmStateValue -Name mode -Value 'remote_sync'
                $null = Set-OmStep 'COMPLETE'
            }
            Write-OmCliResult $result $(if ($result.ok) { 0 } else { 20 })
        }
        'repair' {
            $check = @(Get-OmEnvironmentChecks -Ports $ports -RepoRoot $RepoRoot)
            $summary = Get-OmCheckSummary $check
            $compose = Invoke-OmComposeUp -RepoRoot $RepoRoot -Ports $ports -ProjectName $ProjectName
            $result = [pscustomobject]@{ ok = ($summary.ok -and $compose.ok); checks = $check; compose = $compose }
            Write-OmCliResult $result $(if ($result.ok) { 0 } else { 20 })
        }
        'logs' {
            Write-OmCliResult ([pscustomobject]@{ ok = $true; lines = @(Get-OmLogTail -Lines 200) })
        }
        'rotate-token' {
            $result = Rotate-OmDeviceToken -ProjectName $ProjectName -Ports $ports -DryRun:$DryRun
            Write-OmCliResult $result $(if ($result.ok) { 0 } else { 20 })
        }
        'pair-qr' {
            $current = Read-OmState
            if (-not $current.publicSyncUrl) { throw 'public sync URL missing; deploy remote first' }
            $token = Get-OmDeviceTokenFromFile
            $serverId = Get-OmServerId -BaseUrl $current.publicSyncUrl -Token $token
            $payload = New-OmPairingPayload -Url $current.publicSyncUrl -Token $token -ServerId $serverId
            Write-OmCliResult ([pscustomobject]@{ ok = $true; payload = $payload; serverId = $serverId })
        }
        'pause-watchdog' {
            $result = Set-OmWatchdogPaused -Paused $true -Minutes 60
            Write-OmCliResult $result 0
        }
        'resume-watchdog' {
            $result = Set-OmWatchdogPaused -Paused $false
            Write-OmCliResult $result 0
        }
        'install-watchdog' {
            $result = Install-OmWatchdog -StateRoot $StateRoot -RepoRoot $RepoRoot -Ports $ports -ProjectName $ProjectName -DryRun:$DryRun
            Write-OmCliResult $result $(if ($result.ok) { 0 } else { 20 })
        }
        'uninstall-watchdog' {
            $result = Uninstall-OmWatchdog -StateRoot $StateRoot -DryRun:$DryRun
            Write-OmCliResult $result $(if ($result.ok) { 0 } else { 20 })
        }
    }
} catch {
    $errorResult = [pscustomobject]@{
        ok = $false
        code = 'CLI_ERROR'
        message = $_.Exception.Message
        step = $Command
    }
    if ($Json) { $errorResult | ConvertTo-Json -Depth 8 } else { Write-Error $_.Exception.Message }
    exit 30
}
