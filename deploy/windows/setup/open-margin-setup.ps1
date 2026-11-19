#Requires -Version 5.1
param(
    [Parameter(Position = 0)]
    [ValidateSet(
        'status', 'check', 'install-deps', 'deploy', 'verify', 'disable', 'enable', 'repair',
        'logs', 'rotate-token', 'pair-qr', 'approve-funnel', 'create-owner',
        'pause-watchdog', 'resume-watchdog', 'install-watchdog', 'uninstall-watchdog', 'watchdog-status',
        'set-probe-mode'
    )]
    [string]$Command = 'status',

    [string]$Mode = 'remote_sync',
    [string]$ProbeMode = 'auto',
    [string]$AdminUser = '',
    [string]$AdminPassword = '',
    [string]$AdminPasswordFile = '',
    [string]$AdminEmail = '',
    [switch]$AckPublic,
    [switch]$SkipFunnel,
    [switch]$InstallWatchdog,
    [switch]$NoBuild,
    [switch]$Json,
    [switch]$NonInteractive,
    [switch]$DryRun,
    [int]$Tail = 200,

    [string]$StateRoot = '',
    [string]$RepoRoot = '',
    [string]$ProjectName = 'personal-finance-system',

    [int]$ApiPort = 0,
    [int]$WallosPort = 0,
    [int]$GatewayPort = 0,
    [int]$SetupPort = 0,

    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$ExtraArgs = @()
)

$ErrorActionPreference = 'Stop'
$modulePath = Join-Path $PSScriptRoot 'OpenMargin.Setup.psd1'
Import-Module $modulePath -Force -DisableNameChecking

# PowerShell does not bind GNU-style --options to declared parameters.
# Accept them explicitly so `open-margin-setup.ps1 status --json` works.
function Set-OmCliOption {
    param([string]$Name, [AllowNull()][string]$Value)
    switch ($Name.ToLowerInvariant()) {
        'json' { $script:Json = $true }
        'non-interactive' { $script:NonInteractive = $true }
        'dry-run' { $script:DryRun = $true }
        'ack-public' { $script:AckPublic = $true }
        'skip-funnel' { $script:SkipFunnel = $true }
        'install-watchdog' { $script:InstallWatchdog = $true }
        'no-build' { $script:NoBuild = $true }
        'mode' { if ($Value) { $script:Mode = $Value } }
        'probe-mode' { if ($Value) { $script:ProbeMode = $Value } }
        'admin-user' { if ($Value) { $script:AdminUser = $Value } }
        'admin-password-file' { if ($Value) { $script:AdminPasswordFile = $Value } }
        'admin-email' { if ($Value) { $script:AdminEmail = $Value } }
        'state-root' { if ($Value) { $script:StateRoot = $Value } }
        'repo-root' { if ($Value) { $script:RepoRoot = $Value } }
        'project-name' { if ($Value) { $script:ProjectName = $Value } }
        'api-port' { if ($Value) { $script:ApiPort = [int]$Value } }
        'wallos-port' { if ($Value) { $script:WallosPort = [int]$Value } }
        'gateway-port' { if ($Value) { $script:GatewayPort = [int]$Value } }
        'setup-port' { if ($Value) { $script:SetupPort = [int]$Value } }
        'tail' { if ($Value) { $script:Tail = [int]$Value } }
        default { throw ('unknown option: --' + $Name) }
    }
}

function Set-OmCliJsonOutputEncoding {
    # JSON is a machine-readable contract. Windows PowerShell 5.1 otherwise
    # writes through the active console code page; characters such as curly
    # quotes can be substituted and make the serialized JSON invalid.
    try { [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false) } catch { }
}

for ($index = 0; $index -lt $ExtraArgs.Count; $index++) {
    $token = [string]$ExtraArgs[$index]
    if ($token -match '^--([A-Za-z0-9-]+)=(.*)$') {
        Set-OmCliOption -Name $matches[1] -Value $matches[2]
        continue
    }
    if ($token -match '^--([A-Za-z0-9-]+)$') {
        $name = $matches[1]
        $value = $null
        if ($index + 1 -lt $ExtraArgs.Count -and -not ([string]$ExtraArgs[$index + 1]).StartsWith('--')) {
            $value = [string]$ExtraArgs[$index + 1]
            $index++
        }
        Set-OmCliOption -Name $name -Value $value
        continue
    }
}

function Write-OmCliResult {
    param($Result, [int]$ExitCode = 0)
    if ($Json) {
        Set-OmCliJsonOutputEncoding
        $Result | ConvertTo-Json -Depth 12
    } else {
        if ($Result -is [string]) {
            Write-Output $Result
        } elseif ($Result -is [array]) {
            $Result | Format-List * | Out-String | Write-Output
        } else {
            $Result | Format-List * | Out-String | Write-Output
        }
    }
    exit $ExitCode
}

function Write-OmCliActionRequired {
    param([string]$Code, [string]$Message, [string]$Action = 'user_action', $Extra = $null)
    $result = [ordered]@{
        ok = $false; code = $Code; message = $Message; action_required = $Action; retryable = $true
    }
    if ($null -ne $Extra) {
        foreach ($property in $Extra.PSObject.Properties) { $result[$property.Name] = $property.Value }
    }
    Write-OmCliResult ([pscustomobject]$result) 10
}

function Get-OmCliPassword {
    if ($AdminPassword) { return $AdminPassword }
    if ($AdminPasswordFile) {
        if ($AdminPasswordFile -eq '-') {
            $stdin = [Console]::In.ReadToEnd()
            if ([string]::IsNullOrEmpty($stdin)) { return $null }
            return $stdin.Trim()
        }
        if (-not (Test-Path $AdminPasswordFile)) { throw ('password file not found: ' + $AdminPasswordFile) }
        return (Get-Content -Path $AdminPasswordFile -Raw -Encoding UTF8).Trim()
    }
    if ($NonInteractive) { return $null }
    $secure = Read-Host -AsSecureString '管理员密码'
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}

function Get-OmDeployExitCode {
    param($Result)
    if ($null -eq $Result) { return 30 }
    if ($Result.ok -eq $true) { return 0 }
    if ($Result.action_required -or $Result.action -in @('open_url', 'relaunch_as_admin', 'abort') -or $Result.code -in @('ADMIN_REQUIRED', 'ADMIN_DENIED', 'MISSING_ADMIN', 'ACK_REQUIRED')) { return 10 }
    if ($Result.retryable -eq $true) { return 20 }
    return 30
}

try {
    if (-not $RepoRoot) {
        $RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
    }
    if (-not $StateRoot) {
        $StateRoot = Join-Path $env:LOCALAPPDATA 'OpenMargin'
    }
    Initialize-OmPaths -StateRoot $StateRoot -RepoRoot $RepoRoot
    $state = Read-OmState
    $ports = [pscustomobject]@{
        api     = $(if ($ApiPort -gt 0) { $ApiPort } elseif ($state.ports.api) { [int]$state.ports.api } else { 8767 })
        wallos  = $(if ($WallosPort -gt 0) { $WallosPort } elseif ($state.ports.wallos) { [int]$state.ports.wallos } else { 18082 })
        gateway = $(if ($GatewayPort -gt 0) { $GatewayPort } elseif ($state.ports.gateway) { [int]$state.ports.gateway } else { 18768 })
        setup   = $(if ($SetupPort -gt 0) { $SetupPort } elseif ($state.ports.setup) { [int]$state.ports.setup } else { 18770 })
    }
    $state.ports = $ports
    Save-OmState $state

    # The CLI is frequently run while the setup wizard is listening. Its own
    # setup port is not a conflict: ignore the configured port and the port
    # recorded by the live wizard (setup-web.json may have advanced past 18770).
    $ignorePorts = @([int]$ports.setup)
    $setupWebPath = Join-Path $StateRoot 'setup-web.json'
    if (Test-Path -LiteralPath $setupWebPath) {
        try {
            $setupWeb = Get-Content -Path $setupWebPath -Raw -Encoding UTF8 | ConvertFrom-Json
            $livePort = 0
            if ($setupWeb.port -and [int]::TryParse([string]$setupWeb.port, [ref]$livePort) -and $livePort -gt 0) {
                if ($ignorePorts -notcontains $livePort) { $ignorePorts += $livePort }
            }
        } catch { }
    }

    switch ($Command) {
        'status' {
            $current = Read-OmState
            $checks = @(Get-OmEnvironmentChecks -SkipNetwork -Ports $ports -RepoRoot $RepoRoot -IgnorePorts $ignorePorts)
            $summary = Get-OmCheckSummary $checks
            $watchdog = Invoke-OmWatchdogStatus -StateRoot $StateRoot
            Write-OmCliResult ([pscustomobject]@{
                ok = $true; experience = 'remote_sync'; state = $current; checks = $checks;
                summary = $summary; drift = @(Test-OmStateDrift $current); watchdog = $watchdog
            }) 0
        }
        'check' {
            $checks = @(Get-OmEnvironmentChecks -Ports $ports -RepoRoot $RepoRoot -IgnorePorts $ignorePorts)
            $summary = Get-OmCheckSummary $checks
            Write-OmCliResult ([pscustomobject]@{ ok = $summary.ok; checks = $checks; summary = $summary }) $(if ($summary.ok) { 0 } else { 10 })
        }
        'install-deps' {
            $current = Read-OmState
            if (Test-OmStepTransition -From ([string]$current.step) -To 'INSTALLING_DEPS') {
                $null = Set-OmStep 'INSTALLING_DEPS'
            } else {
                $null = Set-OmStep 'INSTALLING_DEPS' -Force
            }
            if (-not (Test-OmAdmin) -and -not $DryRun) {
                Write-OmCliActionRequired -Code 'ADMIN_REQUIRED' -Message '安装运行环境需要管理员权限。请用管理员 PowerShell 运行 install-deps。' -Action 'relaunch_as_admin'
            }
            $result = Install-OmDependencies -SetupPort $ports.setup -DryRun:$DryRun
            if ($result.ok) {
                if ($result.pendingReboot -and -not $DryRun) {
                    $null = Set-OmStateValue -Name pendingReboot -Value $true
                    $null = Set-OmStep 'WAITING_REBOOT'
                    $resumeScript = Join-Path $PSScriptRoot 'Start-OpenMarginSetupWeb.ps1'
                    $resume = Register-OmResumeRunOnce -ScriptPath $resumeScript -Arguments @('-Port', [string]$ports.setup, '-Resume')
                    if (-not $resume.ok) { Write-OmLog -Level WARN -Message ('RunOnce registration failed: ' + $resume.code) }
                } else {
                    $null = Set-OmStateValue -Name pendingReboot -Value ([bool]$result.pendingReboot)
                    $null = Set-OmStep 'READY' -Force
                    $null = Unregister-OmResumeRunOnce
                }
            } else {
                $null = Set-OmStep 'FAILED' ([pscustomobject]@{ step = 'install-deps'; code = $result.code; message = $result.message }) -Force
            }
            $exit = 0
            if (-not $result.ok) { $exit = $(if ($result.code -eq 'ADMIN_REQUIRED') { 10 } elseif ($result.retryable -eq $true) { 20 } else { 30 }) }
            Write-OmCliResult $result $exit
        }
        'deploy' {
            if (-not $AdminUser) { Write-OmCliActionRequired -Code 'MISSING_ADMIN' -Message '请用 --admin-user 提供管理员用户名。' -Action 'admin_user' }
            if (-not $SkipFunnel -and -not $AckPublic) { Write-OmCliActionRequired -Code 'ACK_REQUIRED' -Message '请加 --ack-public 确认公网暴露说明。' -Action 'ack_public' }
            $password = Get-OmCliPassword
            if (-not $password) { Write-OmCliActionRequired -Code 'ACTION_REQUIRED' -Message '需要管理员密码。请用 --admin-password-file，或在交互模式输入。' -Action 'admin_password' }
            $result = Invoke-OmRemoteDeploy -RepoRoot $RepoRoot -Ports $ports -AdminUser $AdminUser -AdminPassword $password -AdminEmail $AdminEmail -ProjectName $ProjectName -SkipFunnel:$SkipFunnel -InstallWatchdog:$InstallWatchdog -NoBuild:$NoBuild -DryRun:$DryRun
            Write-OmCliResult $result (Get-OmDeployExitCode $result)
        }
        'verify' {
            $current = Read-OmState
            $base = $(if ($current.publicSyncUrl) { $current.publicSyncUrl } else { 'http://127.0.0.1:' + $ports.gateway + '/finance' })
            $token = Get-OmDeviceTokenFromFile
            if (-not $token) { Write-OmCliActionRequired -Code 'NO_DEVICE_TOKEN' -Message '还没有设备密钥，请先完成部署。' -Action 'deploy' }
            $result = Invoke-OmVerify -BaseUrl $base -Token $token -ProjectName $ProjectName -RepoRoot $RepoRoot -DryRun:$DryRun
            Write-OmCliResult $result $(if ($result.ok) { 0 } else { 20 })
        }
        'disable' {
            $result = Invoke-OmDisableRemote -Ports $ports -ProjectName $ProjectName -DryRun:$DryRun
            Write-OmCliResult $result (Get-OmDeployExitCode $result)
        }
        'enable' {
            $result = Invoke-OmEnableRemote -Ports $ports -ProjectName $ProjectName -RepoRoot $RepoRoot -DryRun:$DryRun
            Write-OmCliResult $result (Get-OmDeployExitCode $result)
        }
        'repair' {
            $result = Invoke-OmRepairRemote -Ports $ports -ProjectName $ProjectName -RepoRoot $RepoRoot -DryRun:$DryRun
            Write-OmCliResult $result $(if ($result.ok) { 0 } else { $(if ($result.retryable) { 20 } else { 30 }) })
        }
        'logs' {
            if ($Tail -lt 1 -or $Tail -gt 1000) { $Tail = 200 }
            Write-OmCliResult ([pscustomobject]@{ ok = $true; lines = @(Get-OmLogTail -Lines $Tail) }) 0
        }
        'rotate-token' {
            $result = Rotate-OmDeviceToken -ProjectName $ProjectName -RepoRoot $RepoRoot -Ports $ports -DryRun:$DryRun
            Write-OmCliResult $result $(if ($result.ok) { 0 } else { 20 })
        }
        'pair-qr' {
            $current = Read-OmState
            if (-not $current.publicSyncUrl) { Write-OmCliActionRequired -Code 'NO_PUBLIC_URL' -Message '还没有公网地址，请先部署。' -Action 'deploy' }
            $token = Get-OmDeviceTokenFromFile
            if (-not $token) { Write-OmCliActionRequired -Code 'NO_DEVICE_TOKEN' -Message '设备密钥缺失。' -Action 'deploy' }
            if (-not (Test-OmPairingUrl -Url $current.publicSyncUrl)) {
                Write-OmCliResult ([pscustomobject]@{ ok = $false; code = 'INVALID_PUBLIC_URL'; message = '公网地址不是 https://*.ts.net/finance。'; retryable = $false }) 30
            }
            $serverResult = Get-OmServerIdResult -BaseUrl $current.publicSyncUrl -Token $token
            if (-not $serverResult.ok) {
                Write-OmCliResult ([pscustomobject]@{ ok = $false; code = 'SERVER_ID_UNAVAILABLE'; message = '无法读取账本身份。'; detail = $serverResult.error; retryable = $true }) 20
            }
            $payload = New-OmPairingPayload -Url $current.publicSyncUrl -Token $token -ServerId $serverResult.serverId
            Write-OmCliResult ([pscustomobject]@{ ok = $true; payload = $payload; serverId = $serverResult.serverId; expiresInSeconds = 600 }) 0
        }
        'approve-funnel' {
            $result = Invoke-OmApproveFunnel -Ports $ports -ProjectName $ProjectName -RepoRoot $RepoRoot -DryRun:$DryRun
            Write-OmCliResult $result (Get-OmDeployExitCode $result)
        }
        'create-owner' {
            if (-not $AdminUser) { Write-OmCliActionRequired -Code 'MISSING_ADMIN' -Message '请用 --admin-user 提供管理员用户名。' -Action 'admin_user' }
            $password = Get-OmCliPassword
            if (-not $password) { Write-OmCliActionRequired -Code 'ACTION_REQUIRED' -Message '需要管理员密码。' -Action 'admin_password' }
            $existing = Test-OmWallosOwner -ProjectName $ProjectName -RepoRoot $RepoRoot -DryRun:$DryRun
            if ($existing.ok) {
                Write-OmCliResult ([pscustomobject]@{ ok = $true; code = 'OWNER_EXISTS'; count = $existing.count }) 0
            }
            $result = New-OmWallosOwner -WallosPort $ports.wallos -Username $AdminUser -Password $password -Email $AdminEmail -DryRun:$DryRun
            Write-OmCliResult $result $(if ($result.ok) { 0 } elseif ($result.retryable) { 20 } else { 30 })
        }
        'set-probe-mode' {
            if ($ProbeMode -notin @('auto', 'direct', 'system')) {
                Write-OmCliResult ([pscustomobject]@{
                    ok = $false; code = 'INVALID_PROBE_MODE';
                    message = '公网自测网络只支持 auto、direct 或 system。'; retryable = $false
                }) 30
            }
            if ($DryRun) {
                Write-OmCliResult ([pscustomobject]@{ ok = $true; code = 'DRY_RUN'; mode = $ProbeMode }) 0
            }
            Write-OmCliResult (Set-OmPublicProbeMode -Mode $ProbeMode) 0
        }
        'pause-watchdog' {
            Write-OmCliResult (Set-OmWatchdogPaused -Paused $true -Minutes 60) 0
        }
        'resume-watchdog' {
            Write-OmCliResult (Set-OmWatchdogPaused -Paused $false) 0
        }
        'install-watchdog' {
            $result = Install-OmWatchdog -StateRoot $StateRoot -RepoRoot $RepoRoot -Ports $ports -ProjectName $ProjectName -DryRun:$DryRun
            if ($result.ok) { $null = Set-OmStateValue -Name watchdogInstalled -Value $true }
            Write-OmCliResult $result $(if ($result.ok) { 0 } else { 20 })
        }
        'uninstall-watchdog' {
            $result = Uninstall-OmWatchdog -StateRoot $StateRoot -DryRun:$DryRun
            if ($result.ok) { $null = Set-OmStateValue -Name watchdogInstalled -Value $false }
            Write-OmCliResult $result $(if ($result.ok) { 0 } else { 20 })
        }
        'watchdog-status' {
            Write-OmCliResult (Invoke-OmWatchdogStatus -StateRoot $StateRoot) 0
        }
    }
} catch {
    $errorResult = [pscustomobject]@{
        ok = $false
        code = 'CLI_ERROR'
        message = (Protect-OmText $_.Exception.Message)
        step = $Command
        retryable = $false
    }
    if ($Json) {
        Set-OmCliJsonOutputEncoding
        $errorResult | ConvertTo-Json -Depth 8
    } else {
        Write-Error (Protect-OmText $_.Exception.Message)
    }
    exit 30
}
