# Open Margin setup engine: deployment orchestration.
# Windows PowerShell 5.1 compatible. Passwords stay in memory and are never
# logged or written to the state file.

function Enter-OmDeployingStep {
    $state = Read-OmState
    if (-not (Test-OmStepTransition -From ([string]$state.step) -To 'DEPLOYING')) {
        $null = Set-OmStep 'READY' -Force
    }
    return (Set-OmStep 'DEPLOYING')
}

function New-OmDeployFailure {
    param(
        [string]$Step,
        [string]$Code,
        [string]$Message,
        [string]$Detail = '',
        [switch]$Retryable,
        [string]$Action = '',
        $Extra = $null
    )
    $state = Read-OmState
    $errorInfo = [pscustomobject]@{ step = $Step; code = $Code; message = $Message; at = (Get-Date).ToString('o') }
    $failed = $true
    if (-not (Test-OmStepTransition -From ([string]$state.step) -To 'FAILED')) {
        $null = Set-OmStep 'FAILED' $errorInfo -Force
    } else {
        $null = Set-OmStep 'FAILED' $errorInfo
    }
    $result = [ordered]@{
        ok = $false; step = $Step; code = $Code; message = $Message;
        detail = (Protect-OmText $Detail); retryable = [bool]$Retryable; action = $Action
    }
    if ($null -ne $Extra) {
        foreach ($property in $Extra.PSObject.Properties) {
            $result[$property.Name] = $property.Value
        }
    }
    return [pscustomobject]$result
}

function New-OmDeployWaitingResult {
    param(
        [string]$Step,
        [string]$Code,
        [string]$Message,
        [string]$Action = 'open_url',
        $Extra = $null
    )
    $state = Read-OmState
    $errorInfo = [pscustomobject]@{ step = $Step; code = $Code; message = $Message; at = (Get-Date).ToString('o') }
    if (Test-OmStepTransition -From ([string]$state.step) -To 'WAITING_USER') {
        $null = Set-OmStep 'WAITING_USER' $errorInfo
    } else {
        $null = Set-OmStep 'WAITING_USER' $errorInfo -Force
    }
    $result = [ordered]@{
        ok = $false; step = $Step; code = $Code; message = $Message;
        action = $Action; action_required = $true; retryable = $true
    }
    if ($null -ne $Extra) {
        foreach ($property in $Extra.PSObject.Properties) {
            $result[$property.Name] = $property.Value
        }
    }
    return [pscustomobject]$result
}

function Set-OmRunningSyncPublicUrl {
    param([string]$Url, $Ports, [string]$ProjectName, [string]$RepoRoot)
    Set-OmEnvValueInFile -Name 'FINANCE_SYNC_PUBLIC_URL' -Value $Url -Path (Get-OmBackendEnvPath)
    $result = Invoke-OmDockerCompose -Arguments @('--profile', 'windows-public-sync', 'up', '-d', '--no-deps', '--no-build', '--force-recreate', 'finance-api') -ProjectName $ProjectName -RepoRoot $RepoRoot
    if ($result.exitCode -ne 0) { throw ('更新同步地址后服务重启失败: ' + (Protect-OmText $result.output)) }
    if (-not (Wait-OmHttpOk -Url ('http://127.0.0.1:' + $Ports.api + '/health') -TimeoutSec 240)) {
        throw '更新同步地址后服务未恢复健康。'
    }
}

function Invoke-OmRemoteDeploy {
    param(
        [string]$RepoRoot = (Get-OmRepoRoot),
        $Ports = (Read-OmState).ports,
        [Parameter(Mandatory = $true)][string]$AdminUser,
        [Parameter(Mandatory = $true)][string]$AdminPassword,
        [string]$AdminEmail = '',
        [string]$ProjectName = 'personal-finance-system',
        [switch]$SkipFunnel,
        [switch]$InstallWatchdog,
        [switch]$NoBuild,
        [switch]$DryRun
    )
    $state = Read-OmState
    if (-not $NoBuild -and [bool]$state.offlineImported) {
        # Images were loaded by Import-OmOfflineBundle. Rebuilding from a
        # possibly incomplete source tree would defeat the offline import.
        $NoBuild = $true
        Write-OmLog -Level INFO -Message 'offline bundle is imported; compose will use --no-build'
    }
    $state.ports = $Ports
    $state.mode = $(if ($SkipFunnel) { 'local_test' } else { 'remote_sync' })
    $state.dryRun = [bool]$DryRun
    Save-OmState $state
    $null = Enter-OmDeployingStep
    $null = Set-OmProgress -Stage 'compose' -Percent 5 -Message '准备数据目录和服务配置'

    if ($DryRun) {
        foreach ($stage in @(
            @('compose', 20, 'DRY RUN: compose up'), @('owner', 45, 'DRY RUN: Wallos owner'),
            @('funnel', 70, 'DRY RUN: Tailscale Funnel'), @('verify', 90, 'DRY RUN: verify')
        )) {
            $null = Set-OmProgress -Stage $stage[0] -Percent $stage[1] -Message $stage[2]
            Start-Sleep -Milliseconds 250
        }
        $verify = Invoke-OmVerify -BaseUrl 'https://example.ts.net/finance' -Token 'dry-run-token' -DryRun
        $null = Set-OmStep 'READY' -Force
        $null = Set-OmProgress -Stage 'dry-run' -Percent 100 -Message '演练完成，没有改动系统。'
        return [pscustomobject]@{
            ok = $true; dryRun = $true; step = 'dry-run';
            message = '演练完成，没有启动容器、没有开启 Funnel、没有安装依赖。';
            checks = $verify.checks; serverId = $verify.serverId;
            publicSyncUrl = $null; pairingPayload = $null; action = 'none'
        }
    }

    try {
        $secrets = New-OmSecrets -RepoRoot $RepoRoot -Ports $Ports -FinancePublicUrl $state.financePublicUrl
        Write-OmLog -Level INFO -Message ('secrets ready: created=' + $secrets.created)

        $compose = Invoke-OmComposeUp -RepoRoot $RepoRoot -Ports $Ports -ProjectName $ProjectName -NoBuild:$NoBuild
        if (-not $compose.ok) {
            return (New-OmDeployFailure -Step 'compose' -Code 'COMPOSE_FAILED' -Message $(if ($compose.step -eq 'pull') { '镜像拉取失败，请在依赖检查页面导入离线镜像包后重试。' } else { '启动清衡服务失败。' }) -Detail $compose.output -Retryable)
        }

        $null = Set-OmProgress -Stage 'owner' -Percent 35 -Message '检查 Wallos 管理员'
        $owner = Test-OmWallosOwner -ProjectName $ProjectName -RepoRoot $RepoRoot
        if (-not $owner.ok) {
            $created = New-OmWallosOwner -WallosPort $Ports.wallos -Username $AdminUser -Password $AdminPassword -Email $AdminEmail
            if (-not $created.ok) {
                $manual = $(if ($created.manual) { $created.manual } else { Get-OmWallosManualSteps -WallosPort $Ports.wallos })
                return (New-OmDeployWaitingResult -Step 'owner' -Code $created.code -Message $created.message -Extra ([pscustomobject]@{
                    manualUrl = $manual.url; manualSteps = $manual.steps
                }))
            }
            $wait = Wait-OmWallosOwner -TimeoutSec 60 -ProjectName $ProjectName -RepoRoot $RepoRoot
            if (-not $wait.ok) {
                return (New-OmDeployFailure -Step 'owner' -Code 'OWNER_NOT_VISIBLE' -Message 'Wallos 没有确认管理员创建成功。' -Retryable)
            }
        }
        $state = Read-OmState
        $state.ownerCreated = $true
        Save-OmState $state

        $publicUrl = $null
        if (-not $SkipFunnel) {
            $null = Set-OmProgress -Stage 'funnel' -Percent 60 -Message '开启 Tailscale Funnel'
            $funnel = Enable-OmFunnel -GatewayPort $Ports.gateway -StateRoot (Get-OmStateRoot)
            if (-not $funnel.ok) {
                if ($funnel.code -in @('FUNNEL_APPROVAL_REQUIRED', 'FUNNEL_PENDING')) {
                    return (New-OmDeployWaitingResult -Step 'funnel' -Code $funnel.code -Message $funnel.message -Extra ([pscustomobject]@{
                        approvalUrl = $funnel.approvalUrl; pending = $true
                    }))
                }
                return (New-OmDeployFailure -Step 'funnel' -Code $funnel.code -Message $funnel.message -Detail $funnel.detail -Retryable:([bool]$funnel.retryable) -Action $funnel.action)
            }
            $publicUrl = $funnel.url
            $state = Read-OmState
            $state.publicSyncUrl = $publicUrl
            Save-OmState $state
            if ($publicUrl) {
                Set-OmRunningSyncPublicUrl -Url $publicUrl -Ports $Ports -ProjectName $ProjectName -RepoRoot $RepoRoot
            }
            $null = Set-OmProgress -Stage 'dns' -Percent 72 -Message '等待公网地址生效'
            $dns = Wait-OmPublicDns -Url ($publicUrl.TrimEnd('/') + '/health') -TimeoutSec 600
            if (-not $dns.ok) {
                return (New-OmDeployFailure -Step 'dns' -Code $dns.code -Message $dns.message -Detail $dns.detail -Retryable)
            }
        }

        $verifyBase = $(if ($publicUrl) { $publicUrl } else { 'http://127.0.0.1:' + $Ports.gateway + '/finance' })
        $null = Set-OmProgress -Stage 'verify' -Percent 85 -Message '运行 4 项自测'
        $null = Set-OmStep 'VERIFYING'
        $token = Get-OmDeviceTokenFromFile
        if (-not $token) {
            return (New-OmDeployFailure -Step 'verify' -Code 'NO_DEVICE_TOKEN' -Message 'secrets/backend.env 缺少设备密钥。')
        }
        $verify = Invoke-OmVerify -BaseUrl $verifyBase -Token $token -ProjectName $ProjectName -RepoRoot $RepoRoot
        if (-not $verify.ok) {
            return (New-OmDeployFailure -Step 'verify' -Code 'VERIFY_FAILED' -Message '自测未通过。' -Retryable -Extra ([pscustomobject]@{ checks = $verify.checks }))
        }

        $serverId = $verify.serverId
        $pairingPayload = $null
        if ($publicUrl) {
            if ([string]::IsNullOrWhiteSpace($serverId)) {
                return (New-OmDeployFailure -Step 'pairing' -Code 'SERVER_ID_UNAVAILABLE' -Message '无法从同步接口读取账本身份。' -Retryable)
            }
            $pairingPayload = New-OmPairingPayload -Url $publicUrl -Token $token -ServerId $serverId
        }

        if ($InstallWatchdog) {
            $watchdog = Install-OmWatchdog -StateRoot (Get-OmStateRoot) -RepoRoot $RepoRoot -Ports $Ports -ProjectName $ProjectName
            if ($watchdog.ok) {
                $null = Set-OmStateValue -Name watchdogInstalled -Value $true
            } else {
                Write-OmLog -Level WARN -Message ('watchdog install failed: ' + $watchdog.code)
            }
        }

        $state = Read-OmState
        $state.publicSyncUrl = $publicUrl
        $state.phoneVerified = $false
        Save-OmState $state
        $null = Set-OmProgress -Stage 'complete' -Percent 100 -Message '部署完成'
        $null = Set-OmStep 'COMPLETE'
        $null = Update-OmPhoneVerified -BaseUrl ('http://127.0.0.1:' + $Ports.api)
        return [pscustomobject]@{
            ok = $true; step = 'complete';
            publicSyncUrl = $publicUrl;
            pairingPayload = $pairingPayload;
            serverId = $serverId;
            checks = $verify.checks;
            action = 'none'
        }
    } catch {
        return (New-OmDeployFailure -Step 'exception' -Code 'DEPLOY_EXCEPTION' -Message $_.Exception.Message -Retryable)
    }
}

function Invoke-OmApproveFunnel {
    param(
        $Ports = (Read-OmState).ports,
        [string]$ProjectName = 'personal-finance-system',
        [string]$RepoRoot = (Get-OmRepoRoot),
        [int]$TimeoutSec = 60,
        [switch]$DryRun
    )
    $wait = Wait-OmFunnelApproval -GatewayPort $Ports.gateway -TimeoutSec $TimeoutSec -DryRun:$DryRun
    if (-not $wait.ok) { return $wait }
    $state = Read-OmState
    $state.publicSyncUrl = $wait.url
    Save-OmState $state
    $null = Set-OmProgress -Stage 'funnel' -Percent 75 -Message 'Funnel 已授权'
    if (-not $DryRun) {
        Set-OmRunningSyncPublicUrl -Url $wait.url -Ports $Ports -ProjectName $ProjectName -RepoRoot $RepoRoot
        $dns = Wait-OmPublicDns -Url ($wait.url.TrimEnd('/') + '/health') -TimeoutSec 600
        if (-not $dns.ok) { return (New-OmDeployFailure -Step 'dns' -Code $dns.code -Message $dns.message -Detail $dns.detail -Retryable) }
    }
    $token = Get-OmDeviceTokenFromFile
    if (-not $token) { return (New-OmDeployFailure -Step 'verify' -Code 'NO_DEVICE_TOKEN' -Message 'secrets/backend.env 缺少设备密钥。') }
    $verify = Invoke-OmVerify -BaseUrl $wait.url -Token $token -ProjectName $ProjectName -RepoRoot $RepoRoot -DryRun:$DryRun
    if (-not $verify.ok) {
        return [pscustomobject]@{ ok = $false; code = 'VERIFY_FAILED'; message = 'Funnel 已开启，但自测未通过。'; checks = $verify.checks; retryable = $true }
    }
    if ([string]::IsNullOrWhiteSpace($verify.serverId)) {
        return (New-OmDeployFailure -Step 'pairing' -Code 'SERVER_ID_UNAVAILABLE' -Message '无法从同步接口读取账本身份。' -Retryable)
    }
    # The funnel-approval resume path must leave the same watchdog behind as a
    # full deploy; otherwise a deployment finished here has no logon recovery.
    if (-not $DryRun) {
        $watchdog = Install-OmWatchdog -StateRoot (Get-OmStateRoot) -RepoRoot $RepoRoot -Ports $Ports -ProjectName $ProjectName
        if ($watchdog.ok) {
            $null = Set-OmStateValue -Name watchdogInstalled -Value $true
        } else {
            Write-OmLog -Level WARN -Message ('watchdog install failed after funnel approval: ' + $watchdog.code)
        }
    }
    $null = Set-OmStateValue -Name phoneVerified -Value $false
    $null = Set-OmStateValue -Name mode -Value 'remote_sync'
    $null = Set-OmProgress -Stage 'complete' -Percent 100 -Message '部署完成'
    $null = Set-OmStep 'COMPLETE' -Force
    $null = Update-OmPhoneVerified -BaseUrl ('http://127.0.0.1:' + $Ports.api)
    return [pscustomobject]@{
        ok = $true; code = 'FUNNEL_READY'; message = 'Funnel 已开启，自测通过。';
        publicSyncUrl = $wait.url; serverId = $verify.serverId; checks = $verify.checks
    }
}

function Invoke-OmDisableRemote {
    param(
        $Ports = (Read-OmState).ports,
        [string]$ProjectName = 'personal-finance-system',
        [switch]$DryRun
    )
    $state = Read-OmState
    if ($state.step -notin @('COMPLETE', 'PHONE_VERIFIED', 'DISABLED')) {
        return [pscustomobject]@{ ok = $false; code = 'NOT_DEPLOYED'; message = '当前没有可关闭的远程同步。'; retryable = $false }
    }
    if ($state.step -eq 'DISABLED') {
        return [pscustomobject]@{ ok = $true; code = 'ALREADY_DISABLED'; message = '远程同步已经是关闭状态。'; publicSyncUrl = $null }
    }
    if ($state.step -ne 'DISABLED' -and (Test-OmStepTransition -From ([string]$state.step) -To 'DISABLING')) {
        $null = Set-OmStep 'DISABLING'
    }
    $null = Set-OmProgress -Stage 'disable' -Percent 20 -Message '关闭公网入口'
    $result = Disable-OmFunnel -GatewayPort $Ports.gateway -DryRun:$DryRun
    if (-not $result.ok) {
        return (New-OmDeployFailure -Step 'disable' -Code $result.code -Message $result.message -Detail $result.detail -Retryable:([bool]$result.retryable))
    }
    $null = Set-OmWatchdogPaused -Paused $true -Minutes 0 | Out-Null
    $null = Set-OmStateValue -Name mode -Value 'disabled'
    $null = Set-OmStep 'DISABLED' -Force
    $null = Set-OmProgress -Stage 'disabled' -Percent 100 -Message '远程同步已关闭，数据保留。'
    return [pscustomobject]@{ ok = $true; code = 'DISABLED'; message = '远程同步已关闭，数据保留。'; publicSyncUrl = $null }
}

function Invoke-OmEnableRemote {
    param(
        $Ports = (Read-OmState).ports,
        [string]$ProjectName = 'personal-finance-system',
        [string]$RepoRoot = (Get-OmRepoRoot),
        [switch]$DryRun
    )
    $state = Read-OmState
    if ($state.step -ne 'DISABLED') {
        return [pscustomobject]@{ ok = $false; code = 'NOT_DISABLED'; message = '当前不是“已关闭”状态，请先完成一次部署。'; retryable = $false }
    }
    $null = Set-OmStep 'SWITCHING'
    $null = Set-OmProgress -Stage 'compose' -Percent 15 -Message '确认服务运行'
    if ($DryRun) {
        $null = Set-OmProgress -Stage 'dry-run' -Percent 100 -Message 'DRY RUN: 重新开启未执行。'
        $null = Set-OmStep 'READY' -Force
        return [pscustomobject]@{ ok = $true; dryRun = $true; code = 'DRY_RUN'; message = '演练完成，没有重新开启 Funnel。' }
    }
    $compose = Invoke-OmComposeUp -RepoRoot $RepoRoot -Ports $Ports -ProjectName $ProjectName -NoBuild
    if (-not $compose.ok) {
        return (New-OmDeployFailure -Step 'compose' -Code 'COMPOSE_FAILED' -Message '服务没有恢复运行。' -Detail $compose.output -Retryable)
    }
    $null = Set-OmProgress -Stage 'funnel' -Percent 50 -Message '重新开启 Funnel'
    $funnel = Enable-OmFunnel -GatewayPort $Ports.gateway -StateRoot (Get-OmStateRoot)
    if (-not $funnel.ok) {
        return (New-OmDeployFailure -Step 'funnel' -Code $funnel.code -Message $funnel.message -Detail $funnel.detail -Retryable:([bool]$funnel.retryable) -Action $funnel.action -Extra ([pscustomobject]@{ approvalUrl = $funnel.approvalUrl }))
    }
    Set-OmRunningSyncPublicUrl -Url $funnel.url -Ports $Ports -ProjectName $ProjectName -RepoRoot $RepoRoot
    $null = Set-OmProgress -Stage 'dns' -Percent 70 -Message '等待公网地址生效'
    $dns = Wait-OmPublicDns -Url ($funnel.url.TrimEnd('/') + '/health') -TimeoutSec 600
    if (-not $dns.ok) {
        return (New-OmDeployFailure -Step 'dns' -Code $dns.code -Message $dns.message -Detail $dns.detail -Retryable)
    }
    $token = Get-OmDeviceTokenFromFile
    $null = Set-OmProgress -Stage 'verify' -Percent 85 -Message '自测'
    $verify = Invoke-OmVerify -BaseUrl $funnel.url -Token $token -ProjectName $ProjectName -RepoRoot $RepoRoot
    if (-not $verify.ok) {
        return (New-OmDeployFailure -Step 'verify' -Code 'VERIFY_FAILED' -Message '重新开启后自测未通过。' -Retryable -Extra ([pscustomobject]@{ checks = $verify.checks }))
    }
    $state = Read-OmState
    $state.publicSyncUrl = $funnel.url
    $state.mode = 'remote_sync'
    Save-OmState $state
    $null = Set-OmWatchdogPaused -Paused $false | Out-Null
    $null = Set-OmStateValue -Name watchdogPaused -Value $false
    $null = Set-OmProgress -Stage 'complete' -Percent 100 -Message '远程同步已重新开启'
    $null = Set-OmStep 'COMPLETE'
    return [pscustomobject]@{
        ok = $true; code = 'ENABLED'; message = '远程同步已重新开启。';
        publicSyncUrl = $funnel.url; serverId = $verify.serverId; checks = $verify.checks
    }
}

function Invoke-OmRepairRemote {
    param(
        $Ports = (Read-OmState).ports,
        [string]$ProjectName = 'personal-finance-system',
        [string]$RepoRoot = (Get-OmRepoRoot),
        [switch]$DryRun
    )
    if ($DryRun) {
        return [pscustomobject]@{ ok = $true; dryRun = $true; code = 'DRY_RUN'; message = 'DRY RUN: repair skipped'; checks = @() }
    }
    $null = Set-OmProgress -Stage 'repair' -Percent 10 -Message '重新检查并启动服务'
    $compose = Invoke-OmComposeUp -RepoRoot $RepoRoot -Ports $Ports -ProjectName $ProjectName -NoBuild
    $state = Read-OmState
    $verify = $null
    if ($state.publicSyncUrl) {
        $token = Get-OmDeviceTokenFromFile
        if ($token) {
            $base = $(if ($state.mode -eq 'disabled') { 'http://127.0.0.1:' + $Ports.gateway + '/finance' } else { $state.publicSyncUrl })
            $verify = Invoke-OmVerify -BaseUrl $base -Token $token -ProjectName $ProjectName -RepoRoot $RepoRoot
        }
    }
    $ok = $compose.ok -and ($null -eq $verify -or $verify.ok)
    if ($ok) {
        if ($state.step -in @('FAILED', 'DEPLOYING', 'VERIFYING', 'WAITING_USER')) {
            $null = Set-OmStep 'COMPLETE' -Force
        }
        $null = Set-OmProgress -Stage 'complete' -Percent 100 -Message '修复完成'
    } else {
        $null = Set-OmStep 'FAILED' ([pscustomobject]@{ step = 'repair'; code = 'REPAIR_FAILED'; message = '修复后仍有问题。' }) -Force
    }
    return [pscustomobject]@{ ok = $ok; code = $(if ($ok) { 'REPAIRED' } else { 'REPAIR_FAILED' }); compose = $compose; checks = $(if ($verify) { $verify.checks } else { @() }) }
}
