# Open Margin setup engine: actions callable from the local web wizard.
# Every action is fixed; no arbitrary command is accepted.

function Invoke-OmElevatedDependencyInstall {
    param(
        [Parameter(Mandatory = $true)][int]$SetupPort,
        [string]$ModulePath = (Join-Path $script:OmSetupRoot 'OpenMargin.Setup.psd1')
    )
    $tempRoot = Join-Path $env:TEMP ('openmargin-elevated-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null
    $scriptPath = Join-Path $tempRoot 'install.ps1'
    $resultPath = Join-Path $tempRoot 'result.json'
    $scriptBody = @"
param([string]`$ModulePath,[int]`$SetupPort,[string]`$ResultPath)
Import-Module `$ModulePath -Force
try {
  `$r = Install-OmDependencies -SetupPort `$SetupPort
  `$r | ConvertTo-Json -Depth 10 | Set-Content -Path `$ResultPath -Encoding UTF8
} catch {
  @{ ok = `$false; code = 'ELEVATED_INSTALL_FAILED'; message = `$_.Exception.Message } |
    ConvertTo-Json -Depth 5 | Set-Content -Path `$ResultPath -Encoding UTF8
}
"@
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($scriptPath, $scriptBody, $utf8)
    try {
        $process = Start-Process -FilePath 'pwsh.exe' -ArgumentList @(
            '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $scriptPath,
            '-ModulePath', $ModulePath, '-SetupPort', $SetupPort, '-ResultPath', $resultPath
        ) -Verb RunAs -Wait -PassThru
        if (Test-Path $resultPath) {
            return (Get-Content -Path $resultPath -Raw -Encoding UTF8 | ConvertFrom-Json)
        }
        return [pscustomobject]@{ ok = $false; code = 'ELEVATED_NO_RESULT'; message = ('exit ' + $process.ExitCode) }
    } catch {
        return [pscustomobject]@{ ok = $false; code = 'ADMIN_DENIED'; message = '需要管理员权限，UAC 被取消。' }
    } finally {
        Remove-Item -Recurse -Force $tempRoot -ErrorAction SilentlyContinue
    }
}

function Invoke-OmWebAction {
    param(
        [Parameter(Mandatory = $true)][string]$Action,
        $Params = $null,
        $Ports = (Read-OmState).ports,
        [string]$ProjectName = 'personal-finance-system',
        [string]$StateRoot = (Get-OmStateRoot),
        [string]$RepoRoot = (Get-OmRepoRoot)
    )
    $state = Read-OmState
    switch ($Action) {
        'check' {
            $checks = @(Get-OmEnvironmentChecks -Ports $Ports -RepoRoot $RepoRoot)
            $summary = Get-OmCheckSummary $checks
            $state.lastCheck = [pscustomobject]@{ at = (Get-Date).ToString('o'); summary = $summary }
            Save-OmState $state
            return [pscustomobject]@{ ok = $summary.ok; checks = $checks; summary = $summary }
        }
        'install-deps' {
            if (-not (Test-OmAdmin)) {
                return (Invoke-OmElevatedDependencyInstall -SetupPort $Ports.setup)
            }
            return (Install-OmDependencies -SetupPort $Ports.setup)
        }
        'deploy' {
            $adminUser = [string]$Params.adminUser
            $adminPassword = [string]$Params.adminPassword
            $adminEmail = [string]$Params.adminEmail
            $ackPublic = [bool]$Params.ackPublic
            $skipFunnel = [bool]$Params.skipFunnel
            if (-not $adminUser -or -not $adminPassword) {
                return [pscustomobject]@{ ok = $false; code = 'MISSING_ADMIN'; message = '请填写管理员用户名和密码。' }
            }
            if (-not $skipFunnel -and -not $ackPublic) {
                return [pscustomobject]@{ ok = $false; code = 'ACK_REQUIRED'; message = '请先确认公网暴露说明。' }
            }
            return (Invoke-OmRemoteDeploy -RepoRoot $RepoRoot -Ports $Ports -AdminUser $adminUser -AdminPassword $adminPassword -AdminEmail $adminEmail -ProjectName $ProjectName -SkipFunnel:$skipFunnel -InstallWatchdog)
        }
        'login-tailscale' {
            return (Start-OmTailscaleLogin)
        }
        'backup' {
            $result = Invoke-OmDockerCompose -Arguments @('--profile', 'maintenance', 'run', '--rm', 'backup') -ProjectName $ProjectName
            return [pscustomobject]@{ ok = ($result.exitCode -eq 0); code = 'BACKUP'; message = (Protect-OmText $result.output) }
        }
        'verify' {
            $base = $(if ($state.publicSyncUrl) { $state.publicSyncUrl } else { 'http://127.0.0.1:' + $Ports.gateway + '/finance' })
            $token = Get-OmDeviceTokenFromFile
            if (-not $token) { return [pscustomobject]@{ ok = $false; code = 'NO_TOKEN'; message = '还没有设备密钥，请先部署。' } }
            return (Invoke-OmVerify -BaseUrl $base -Token $token -ProjectName $ProjectName)
        }
        'disable' {
            $result = Disable-OmFunnel
            if ($result.ok) {
                Set-OmStep 'DISABLED' | Out-Null
                Set-OmStateValue -Name mode -Value 'disabled' | Out-Null
            }
            return $result
        }
        'enable' {
            $result = Enable-OmFunnel -GatewayPort $Ports.gateway
            if ($result.ok) {
                Set-OmStateValue -Name publicSyncUrl -Value $result.url | Out-Null
                Set-OmStateValue -Name mode -Value 'remote_sync' | Out-Null
                Set-OmStep 'COMPLETE' | Out-Null
            }
            return $result
        }
        'repair' {
            $checks = @(Get-OmEnvironmentChecks -Ports $Ports -RepoRoot $RepoRoot)
            $compose = Invoke-OmComposeUp -RepoRoot $RepoRoot -Ports $Ports -ProjectName $ProjectName
            return [pscustomobject]@{ ok = ($compose.ok -and (Get-OmCheckSummary $checks).ok); checks = $checks; compose = $compose }
        }
        'pair-qr' {
            if (-not $state.publicSyncUrl) {
                return [pscustomobject]@{ ok = $false; code = 'NO_PUBLIC_URL'; message = '还没有公网地址，请先部署。' }
            }
            $token = Get-OmDeviceTokenFromFile
            if (-not $token) { return [pscustomobject]@{ ok = $false; code = 'NO_TOKEN'; message = '设备密钥缺失。' } }
            $serverId = Get-OmServerId -BaseUrl $state.publicSyncUrl -Token $token
            $payload = New-OmPairingPayload -Url $state.publicSyncUrl -Token $token -ServerId $serverId
            return [pscustomobject]@{ ok = $true; payload = $payload; serverId = $serverId }
        }
        'rotate-token' {
            return (Rotate-OmDeviceToken -ProjectName $ProjectName -Ports $Ports)
        }
        'pause-watchdog' {
            return (Set-OmWatchdogPaused -Paused $true -Minutes 60)
        }
        'resume-watchdog' {
            return (Set-OmWatchdogPaused -Paused $false)
        }
        'install-watchdog' {
            return (Install-OmWatchdog -StateRoot $StateRoot -RepoRoot $RepoRoot -Ports $Ports -ProjectName $ProjectName)
        }
        default {
            return [pscustomobject]@{ ok = $false; code = 'UNKNOWN_ACTION'; message = ('unknown action: ' + $Action) }
        }
    }
}
