# Open Margin setup engine: pairing payload and device-token rotation.
# The QR payload intentionally carries the device token directly (product
# decision); it is only produced locally, never logged and never written to
# the state file.

function Get-OmServerIdResult {
    param(
        [Parameter(Mandatory = $true)][string]$BaseUrl,
        [Parameter(Mandatory = $true)][string]$Token,
        [int]$TimeoutSec = 15,
        [int]$Attempts = 3
    )
    $url = $BaseUrl.TrimEnd('/') + '/api/v2/sync/changes?since=0&limit=1'
    $lastError = $null
    $probeMode = Get-OmPublicProbeMode
    for ($attempt = 1; $attempt -le [Math]::Max(1, $Attempts); $attempt++) {
        try {
            # Reuse the self-test HTTP helper so the public *.ts.net probe
            # follows the same direct/proxy choice and skips a system proxy
            # that would break its TLS handshake.
            $probe = Get-OmHttpStatus -Url $url -Headers @{ 'X-Device-Token' = $Token } -TimeoutSec $TimeoutSec -ProxyMode $probeMode
            if ($probe.status -eq 200 -and $probe.body) {
                $response = $probe.body | ConvertFrom-Json
                if ($response.PSObject.Properties['server_id'] -and $response.server_id) {
                    return [pscustomobject]@{ ok = $true; serverId = [string]$response.server_id; error = $null }
                }
                $lastError = 'server_id missing in sync response'
            } else {
                $lastError = $(if ($probe.error) { $probe.error } else { 'HTTP ' + $probe.status })
            }
        } catch {
            $lastError = $_.Exception.Message
        }
        if ($attempt -lt $Attempts) { Start-Sleep -Seconds 2 }
    }
    return [pscustomobject]@{ ok = $false; serverId = $null; error = (Protect-OmText $lastError) }
}

function Get-OmServerId {
    param(
        [Parameter(Mandatory = $true)][string]$BaseUrl,
        [Parameter(Mandatory = $true)][string]$Token,
        [int]$TimeoutSec = 15
    )
    $result = Get-OmServerIdResult -BaseUrl $BaseUrl -Token $Token -TimeoutSec $TimeoutSec
    if ($result.ok) { return $result.serverId }
    return $null
}

function Test-OmPairingUrl {
    param([Parameter(Mandatory = $true)][string]$Url)
    $parsed = $null
    try { $parsed = [Uri]$Url } catch { return $false }
    if ($parsed.Scheme -ne 'https') { return $false }
    if ($parsed.Host -notlike '*.ts.net') { return $false }
    $path = $parsed.AbsolutePath.TrimEnd('/')
    if ($path -notin @('', '/finance')) { return $false }
    if ($parsed.Query -or $parsed.Fragment) { return $false }
    return $true
}

function Test-OmPairingPayload {
    param([Parameter(Mandatory = $true)][string]$Payload)
    try {
        $data = $Payload | ConvertFrom-Json
    } catch {
        return [pscustomobject]@{ ok = $false; code = 'PAIRING_JSON'; message = '配对内容不是有效 JSON。' }
    }
    if ($data.v -ne 1 -or $data.kind -ne 'open-margin-pair') {
        return [pscustomobject]@{ ok = $false; code = 'PAIRING_KIND'; message = '配对内容版本或类型不正确。' }
    }
    if ([string]::IsNullOrWhiteSpace([string]$data.url) -or -not (Test-OmPairingUrl -Url ([string]$data.url))) {
        return [pscustomobject]@{ ok = $false; code = 'PAIRING_URL'; message = '配对地址必须是 https://*.ts.net/finance。' }
    }
    if ([string]::IsNullOrWhiteSpace([string]$data.token)) {
        return [pscustomobject]@{ ok = $false; code = 'PAIRING_TOKEN'; message = '配对内容缺少设备密钥。' }
    }
    if ([string]::IsNullOrWhiteSpace([string]$data.server_id)) {
        return [pscustomobject]@{ ok = $false; code = 'PAIRING_SERVER_ID'; message = '配对内容缺少账本身份。' }
    }
    return [pscustomobject]@{ ok = $true; code = 'PAIRING_OK'; message = '' }
}

function New-OmPairingPayload {
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [Parameter(Mandatory = $true)][string]$Token,
        [string]$ServerId = ''
    )
    if ([string]::IsNullOrWhiteSpace($Url)) { throw 'Url is required' }
    if ([string]::IsNullOrWhiteSpace($Token)) { throw 'Token is required' }
    if ([string]::IsNullOrWhiteSpace($ServerId)) { throw 'ServerId is required' }
    $payload = [ordered]@{
        v         = 1
        kind      = 'open-margin-pair'
        url       = $Url.TrimEnd('/')
        token     = $Token
        server_id = $ServerId
    }
    return ($payload | ConvertTo-Json -Compress)
}

function Rotate-OmDeviceToken {
    param(
        [string]$ProjectName = 'personal-finance-system',
        [string]$RepoRoot = (Get-OmRepoRoot),
        $Ports = (Read-OmState).ports,
        [switch]$DryRun
    )
    $newToken = New-OmSecretValue
    if ($DryRun) {
        return [pscustomobject]@{ ok = $true; code = 'DRY_RUN'; dryRun = $true; message = 'DRY RUN: token rotation skipped' }
    }
    Set-OmDeviceTokenInFile -Token $newToken
    $result = Restart-OmFinanceApi -ProjectName $ProjectName -RepoRoot $RepoRoot
    if ($result.exitCode -ne 0) {
        return [pscustomobject]@{ ok = $false; code = 'RESTART_FAILED'; message = '设备密钥已写入，但 finance-api 重启失败。'; detail = Protect-OmText $result.output; retryable = $true }
    }
    $healthUrl = 'http://127.0.0.1:' + $Ports.api + '/health'
    $healthy = Wait-OmHttpOk -Url $healthUrl -TimeoutSec 120
    if (-not $healthy) {
        return [pscustomobject]@{ ok = $false; code = 'HEALTH_FAILED'; message = '设备密钥已写入，但服务没有恢复健康。'; retryable = $true }
    }
    # The new token is returned to the local caller for the QR code, but is
    # never logged or persisted in the state file.
    return [pscustomobject]@{ ok = $true; code = 'TOKEN_ROTATED'; token = $newToken; message = '旧二维码已失效，请重新配对。' }
}
