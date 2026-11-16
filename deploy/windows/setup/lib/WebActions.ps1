# Open Margin setup engine: actions callable from the local web wizard and CLI.
# Every action is fixed; no arbitrary command is accepted. Secrets are never
# echoed into logs; pairing payloads are only returned to the local caller.

function Get-OmJobProgressPath {
    param([string]$StateRoot, [string]$JobId)
    if ([string]::IsNullOrWhiteSpace($JobId)) { return '' }
    return (Join-Path (Join-Path $StateRoot 'jobs') ($JobId + '.progress.json'))
}

function Invoke-OmWatchdogStatus {
    param([string]$StateRoot = (Get-OmStateRoot), [string]$TaskName = 'OpenMarginWatchdog')
    $state = Read-OmState
    $installed = $false
    try {
        $task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
        $installed = ($null -ne $task)
    } catch { }
    $watchdogState = $null
    $watchdogStatePath = Join-Path $StateRoot 'watchdog-state.json'
    if (Test-Path $watchdogStatePath) {
        try { $watchdogState = Get-Content -Path $watchdogStatePath -Raw -Encoding UTF8 | ConvertFrom-Json } catch { }
    }
    $paused = [bool]$state.watchdogPaused
    $until = $state.watchdogPausedUntil
    if ($paused -and $until) {
        try { if ((Get-Date) -ge [datetime]$until) { $paused = $false } } catch { }
    }
    return [pscustomobject]@{
        ok = $true
        installed = $installed
        paused = $paused
        pausedUntil = $until
        state = $(if ($paused) { 'paused' } elseif ($installed) { 'running' } else { 'not_installed' })
        lastCheck = $(if ($watchdogState) { $watchdogState.lastCheck } else { $null })
        lastFailure = $(if ($watchdogState) { $watchdogState.lastFailure } else { $null })
        actionRequired = $(if ($watchdogState) { $watchdogState.lastActionRequired } else { $null })
    }
}

function Invoke-OmWebAction {
    param(
        [Parameter(Mandatory = $true)][string]$Action,
        $Params = $null,
        $Ports = (Read-OmState).ports,
        [string]$ProjectName = 'personal-finance-system',
        [string]$StateRoot = (Get-OmStateRoot),
        [string]$RepoRoot = (Get-OmRepoRoot),
        [string]$JobId = '',
        [switch]$DryRun
    )
    if ($null -eq $Params) { $Params = [pscustomobject]@{} }
    $state = Read-OmState
    $dryRunRequested = $DryRun -or ([bool]$Params.dryRun)
    switch ($Action) {
        'check' {
            $ignorePorts = @()
            if ($Params.PSObject.Properties['setupPort'] -and $Params.setupPort) {
                $ignorePorts += [int]$Params.setupPort
            }
            $checks = @(Get-OmEnvironmentChecks -Ports $Ports -RepoRoot $RepoRoot -IgnorePorts $ignorePorts)
            $summary = Get-OmCheckSummary $checks
            $state.lastCheck = [pscustomobject]@{ at = (Get-Date).ToString('o'); summary = $summary }
            Save-OmState $state
            return [pscustomobject]@{ ok = $summary.ok; checks = $checks; summary = $summary; dryRun = $false }
        }
        'install-deps' {
            $current = Read-OmState
            if (Test-OmStepTransition -From ([string]$current.step) -To 'INSTALLING_DEPS') {
                $null = Set-OmStep 'INSTALLING_DEPS'
            } else {
                $null = Set-OmStep 'INSTALLING_DEPS' -Force
            }
            $progressPath = Get-OmJobProgressPath -StateRoot $StateRoot -JobId $JobId
            if (-not (Test-OmAdmin)) {
                $result = Invoke-OmElevatedDependencyInstall -SetupPort $Ports.setup -ProgressPath $progressPath -DryRun:$dryRunRequested
            } else {
                $result = Install-OmDependencies -SetupPort $Ports.setup -ProgressPath $progressPath -DryRun:$dryRunRequested
            }
            if ($result.ok) {
                if ($result.pendingReboot -and -not $dryRunRequested) {
                    $null = Set-OmStateValue -Name pendingReboot -Value $true
                    $null = Set-OmStep 'WAITING_REBOOT'
                    $resumePort = $(if ($Params.PSObject.Properties['setupPort'] -and $Params.setupPort) { [int]$Params.setupPort } else { [int]$Ports.setup })
                    $resumeScript = Join-Path $script:OmSetupRoot 'Start-OpenMarginSetupWeb.ps1'
                    $resume = Register-OmResumeRunOnce -ScriptPath $resumeScript -Arguments @('-Port', [string]$resumePort, '-Resume')
                    if (-not $resume.ok) { Write-OmLog -Level WARN -Message ('RunOnce registration failed: ' + $resume.code) }
                } else {
                    $null = Set-OmStateValue -Name pendingReboot -Value ([bool]$result.pendingReboot)
                    $null = Set-OmStep 'READY' -Force
                    $null = Unregister-OmResumeRunOnce
                }
            } elseif ($result.code -eq 'ADMIN_DENIED' -or $result.code -eq 'ADMIN_REQUIRED') {
                $null = Set-OmStep 'NEEDS_USER' -Force
            } else {
                $null = Set-OmStep 'FAILED' ([pscustomobject]@{ step = 'install-deps'; code = $result.code; message = $result.message }) -Force
            }
            return $result
        }
        'deploy' {
            $adminUser = [string]$Params.adminUser
            $adminPassword = [string]$Params.adminPassword
            $adminEmail = [string]$Params.adminEmail
            $ackPublic = [bool]$Params.ackPublic
            $skipFunnel = [bool]$Params.skipFunnel
            $noBuild = [bool]$Params.noBuild
            if (-not $adminUser -or -not $adminPassword) {
                return [pscustomobject]@{ ok = $false; code = 'MISSING_ADMIN'; message = '请填写管理员用户名和密码。'; retryable = $true }
            }
            if (-not $skipFunnel -and -not $ackPublic) {
                return [pscustomobject]@{ ok = $false; code = 'ACK_REQUIRED'; message = '请先确认公网暴露说明。'; retryable = $true }
            }
            $deploy = Invoke-OmRemoteDeploy -RepoRoot $RepoRoot -Ports $Ports -AdminUser $adminUser -AdminPassword $adminPassword -AdminEmail $adminEmail -ProjectName $ProjectName -SkipFunnel:$skipFunnel -InstallWatchdog -NoBuild:$noBuild -DryRun:$dryRunRequested
            # The pairing payload contains the device token. Web jobs persist
            # their result on disk until cleanup; the wizard fetches the QR
            # through /api/qr instead, so never write the token here.
            if ($deploy.PSObject.Properties['pairingPayload']) { $deploy.PSObject.Properties.Remove('pairingPayload') }
            return $deploy
        }
        'create-owner' {
            $adminUser = [string]$Params.adminUser
            $adminPassword = [string]$Params.adminPassword
            if (-not $adminUser -or -not $adminPassword) {
                return [pscustomobject]@{ ok = $false; code = 'MISSING_ADMIN'; message = '请填写管理员用户名和密码。' }
            }
            $existing = Test-OmWallosOwner -ProjectName $ProjectName -RepoRoot $RepoRoot -DryRun:$dryRunRequested
            if ($existing.ok) {
                return [pscustomobject]@{ ok = $true; code = 'OWNER_EXISTS'; message = '管理员已存在。'; count = $existing.count }
            }
            $created = New-OmWallosOwner -WallosPort $Ports.wallos -Username $adminUser -Password $adminPassword -Email ([string]$Params.adminEmail) -DryRun:$dryRunRequested
            if ($created.ok -and -not $dryRunRequested) {
                $wait = Wait-OmWallosOwner -TimeoutSec 60 -ProjectName $ProjectName -RepoRoot $RepoRoot
                if (-not $wait.ok) { return [pscustomobject]@{ ok = $false; code = 'OWNER_NOT_VISIBLE'; message = 'Wallos 没有确认管理员创建成功。'; retryable = $true } }
                $null = Set-OmStateValue -Name ownerCreated -Value $true
            }
            return $created
        }
        'login-tailscale' {
            return (Start-OmTailscaleLogin -DryRun:$dryRunRequested)
        }
        'approve-funnel' {
            return (Invoke-OmApproveFunnel -Ports $Ports -ProjectName $ProjectName -RepoRoot $RepoRoot -DryRun:$dryRunRequested)
        }
        'verify' {
            $current = Read-OmState
            $base = $(if ($current.publicSyncUrl) { $current.publicSyncUrl } else { 'http://127.0.0.1:' + $Ports.gateway + '/finance' })
            $token = Get-OmDeviceTokenFromFile
            if (-not $token) { return [pscustomobject]@{ ok = $false; code = 'NO_TOKEN'; message = '还没有设备密钥，请先部署。'; retryable = $true } }
            return (Invoke-OmVerify -BaseUrl $base -Token $token -ProjectName $ProjectName -RepoRoot $RepoRoot -DryRun:$dryRunRequested)
        }
        'disable' {
            return (Invoke-OmDisableRemote -Ports $Ports -ProjectName $ProjectName -DryRun:$dryRunRequested)
        }
        'enable' {
            return (Invoke-OmEnableRemote -Ports $Ports -ProjectName $ProjectName -RepoRoot $RepoRoot -DryRun:$dryRunRequested)
        }
        'repair' {
            return (Invoke-OmRepairRemote -Ports $Ports -ProjectName $ProjectName -RepoRoot $RepoRoot -DryRun:$dryRunRequested)
        }
        'backup' {
            $result = Invoke-OmComposeBackup -ProjectName $ProjectName -RepoRoot $RepoRoot
            return [pscustomobject]@{
                ok = ($result.exitCode -eq 0); code = 'BACKUP';
                message = $(if ($result.exitCode -eq 0) { '备份已写入 Docker 卷 finance-backups。' } else { '备份失败。' });
                detail = (Protect-OmText $result.output)
            }
        }
        'pair-qr' {
            $current = Read-OmState
            if (-not $current.publicSyncUrl) {
                return [pscustomobject]@{ ok = $false; code = 'NO_PUBLIC_URL'; message = '还没有公网地址，请先部署。'; retryable = $true }
            }
            $token = Get-OmDeviceTokenFromFile
            if (-not $token) { return [pscustomobject]@{ ok = $false; code = 'NO_TOKEN'; message = '设备密钥缺失。'; retryable = $false } }
            if (-not (Test-OmPairingUrl -Url $current.publicSyncUrl)) {
                return [pscustomobject]@{ ok = $false; code = 'INVALID_PUBLIC_URL'; message = '公网地址不是 https://*.ts.net/finance，无法生成配对码。' }
            }
            $serverResult = Get-OmServerIdResult -BaseUrl $current.publicSyncUrl -Token $token
            if (-not $serverResult.ok) {
                return [pscustomobject]@{ ok = $false; code = 'SERVER_ID_UNAVAILABLE'; message = '无法从同步接口读取账本身份。'; detail = $serverResult.error; retryable = $true }
            }
            $payload = New-OmPairingPayload -Url $current.publicSyncUrl -Token $token -ServerId $serverResult.serverId
            $check = Test-OmPairingPayload -Payload $payload
            if (-not $check.ok) { return [pscustomobject]@{ ok = $false; code = $check.code; message = $check.message } }
            return [pscustomobject]@{
                ok = $true; payload = $payload; serverId = $serverResult.serverId;
                expiresInSeconds = 600; generatedAt = (Get-Date).ToString('o')
            }
        }
        'rotate-token' {
            $rotated = Rotate-OmDeviceToken -ProjectName $ProjectName -RepoRoot $RepoRoot -Ports $Ports -DryRun:$dryRunRequested
            if ($rotated.PSObject.Properties['token']) {
                # Web jobs must not persist the new device token; the caller
                # reloads the QR through /api/qr after rotation.
                $rotated.PSObject.Properties.Remove('token')
            }
            return $rotated
        }
        'pause-watchdog' {
            $minutes = 60
            if ($Params.PSObject.Properties['minutes'] -and $Params.minutes) { $minutes = [int]$Params.minutes }
            return (Set-OmWatchdogPaused -Paused $true -Minutes $minutes)
        }
        'resume-watchdog' {
            return (Set-OmWatchdogPaused -Paused $false)
        }
        'install-watchdog' {
            $result = Install-OmWatchdog -StateRoot $StateRoot -RepoRoot $RepoRoot -Ports $Ports -ProjectName $ProjectName -DryRun:$dryRunRequested
            if ($result.ok) { $null = Set-OmStateValue -Name watchdogInstalled -Value $true }
            return $result
        }
        'uninstall-watchdog' {
            $result = Uninstall-OmWatchdog -StateRoot $StateRoot -DryRun:$dryRunRequested
            if ($result.ok) { $null = Set-OmStateValue -Name watchdogInstalled -Value $false }
            return $result
        }
        'watchdog-status' {
            return (Invoke-OmWatchdogStatus -StateRoot $StateRoot)
        }
        default {
            return [pscustomobject]@{ ok = $false; code = 'UNKNOWN_ACTION'; message = ('unknown action: ' + $Action); retryable = $false }
        }
    }
}
