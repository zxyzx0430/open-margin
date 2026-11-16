# Open Margin setup engine: deployment orchestration.

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
        [switch]$DryRun
    )
    $state = Read-OmState
    $state.mode = $(if ($SkipFunnel) { 'local_test' } else { 'remote_sync' })
    $state.ports = $Ports
    Save-OmState $state
    Set-OmStep 'DEPLOYING'
    Set-OmStateValue -Name progress -Value 'compose' | Out-Null

    try {
        $secrets = New-OmSecrets -RepoRoot $RepoRoot -Ports $Ports -FinancePublicUrl $state.financePublicUrl
        Write-OmLog -Level INFO -Message ('secrets ready: created=' + $secrets.created)

        $compose = Invoke-OmComposeUp -RepoRoot $RepoRoot -Ports $Ports -ProjectName $ProjectName
        if (-not $compose.ok) {
            Set-OmStep 'FAILED' ([pscustomobject]@{ step = 'compose'; code = 'COMPOSE_FAILED'; message = (Protect-OmText $compose.output) })
            return [pscustomobject]@{ ok = $false; step = 'compose'; code = 'COMPOSE_FAILED'; message = '启动清衡服务失败。'; detail = Protect-OmText $compose.output }
        }

        Set-OmStateValue -Name progress -Value 'owner' | Out-Null
        $owner = Test-OmWallosOwner -ProjectName $ProjectName
        if (-not $owner.ok) {
            $created = New-OmWallosOwner -WallosPort $Ports.wallos -Username $AdminUser -Password $AdminPassword -Email $AdminEmail
            if (-not $created.ok) {
                Set-OmStep 'WAITING_USER' ([pscustomobject]@{ step = 'owner'; code = $created.code; message = $created.message })
                return [pscustomobject]@{
                    ok = $false; step = 'owner'; code = $created.code;
                    message = $created.message;
                    manualUrl = ('http://127.0.0.1:' + $Ports.wallos + '/registration.php')
                }
            }
            $wait = Wait-OmWallosOwner -TimeoutSec 60 -ProjectName $ProjectName
            if (-not $wait.ok) {
                return [pscustomobject]@{ ok = $false; step = 'owner'; code = 'OWNER_NOT_VISIBLE'; message = 'Wallos 没有确认管理员创建成功。' }
            }
        }
        $state = Read-OmState
        $state.ownerCreated = $true
        Save-OmState $state

        $publicUrl = $null
        if (-not $SkipFunnel) {
            Set-OmStateValue -Name progress -Value 'funnel' | Out-Null
            $funnel = Enable-OmFunnel -GatewayPort $Ports.gateway -DryRun:$DryRun
            if (-not $funnel.ok) {
                Set-OmStep 'WAITING_USER' ([pscustomobject]@{ step = 'funnel'; code = $funnel.code; message = $funnel.message })
                return [pscustomobject]@{
                    ok = $false; step = 'funnel'; code = $funnel.code;
                    message = $funnel.message; approvalUrl = $funnel.approvalUrl;
                    action = $funnel.action
                }
            }
            $publicUrl = $funnel.url
            $state = Read-OmState
            $state.publicSyncUrl = $publicUrl
            Save-OmState $state
        }

        $verifyBase = $(if ($publicUrl) { $publicUrl } else { 'http://127.0.0.1:' + $Ports.gateway + '/finance' })
        Set-OmStateValue -Name progress -Value 'verify' | Out-Null
        $token = Get-OmDeviceTokenFromFile
        $verify = Invoke-OmVerify -BaseUrl $verifyBase -Token $token -ProjectName $ProjectName
        if (-not $verify.ok) {
            Set-OmStep 'FAILED' ([pscustomobject]@{ step = 'verify'; code = 'VERIFY_FAILED'; message = '自测未通过。' })
            return [pscustomobject]@{ ok = $false; step = 'verify'; code = 'VERIFY_FAILED'; message = '自测未通过。'; checks = $verify.checks }
        }

        $serverId = $verify.serverId
        $pairingPayload = $null
        if ($publicUrl) {
            $pairingPayload = New-OmPairingPayload -Url $publicUrl -Token $token -ServerId $serverId
        }

        if ($InstallWatchdog -and -not $DryRun) {
            $null = Install-OmWatchdog -StateRoot (Get-OmStateRoot) -RepoRoot $RepoRoot -Ports $Ports -ProjectName $ProjectName
        }

        $state = Read-OmState
        $state.publicSyncUrl = $publicUrl
        $state.phoneVerified = $false
        Save-OmState $state
        Set-OmStateValue -Name progress -Value 'complete' | Out-Null
        Set-OmStep 'COMPLETE'
        return [pscustomobject]@{
            ok = $true; step = 'complete';
            publicSyncUrl = $publicUrl;
            pairingPayload = $pairingPayload;
            serverId = $serverId;
            checks = $verify.checks
        }
    } catch {
        Set-OmStep 'FAILED' ([pscustomobject]@{ step = 'exception'; code = 'DEPLOY_EXCEPTION'; message = $_.Exception.Message })
        return [pscustomobject]@{ ok = $false; step = 'exception'; code = 'DEPLOY_EXCEPTION'; message = $_.Exception.Message }
    }
}
