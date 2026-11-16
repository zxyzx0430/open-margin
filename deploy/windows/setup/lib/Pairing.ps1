# Open Margin setup engine: pairing payload and device-token rotation.

function Get-OmServerId {
    param(
        [Parameter(Mandatory = $true)][string]$BaseUrl,
        [Parameter(Mandatory = $true)][string]$Token,
        [int]$TimeoutSec = 15
    )
    $url = $BaseUrl.TrimEnd('/') + '/api/v2/sync/changes?since=0&limit=1'
    try {
        $response = Invoke-RestMethod -Uri $url -Headers @{ 'X-Device-Token' = $Token } -TimeoutSec $TimeoutSec -ErrorAction Stop
        if ($response.PSObject.Properties['server_id']) { return [string]$response.server_id }
        return $null
    } catch {
        return $null
    }
}

function New-OmPairingPayload {
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [Parameter(Mandatory = $true)][string]$Token,
        [string]$ServerId = ''
    )
    $payload = [ordered]@{
        v = 1
        kind = 'open-margin-pair'
        url = $Url.TrimEnd('/')
        token = $Token
        server_id = $ServerId
    }
    return ($payload | ConvertTo-Json -Compress)
}

function Rotate-OmDeviceToken {
    param(
        [string]$ProjectName = 'personal-finance-system',
        $Ports = (Read-OmState).ports,
        [switch]$DryRun
    )
    $newToken = New-OmSecretValue
    if ($DryRun) {
        return [pscustomobject]@{ ok = $true; code = 'DRY_RUN'; token = $newToken }
    }
    Set-OmDeviceTokenInFile -Token $newToken
    $result = Invoke-OmDockerCompose -Arguments @('up', '-d', '--force-recreate', 'finance-api') -ProjectName $ProjectName
    if ($result.exitCode -ne 0) {
        return [pscustomobject]@{ ok = $false; code = 'RESTART_FAILED'; message = Protect-OmText $result.output }
    }
    $healthUrl = 'http://127.0.0.1:' + $Ports.api + '/health'
    $healthy = Wait-OmHttpOk -Url $healthUrl -TimeoutSec 120
    return [pscustomobject]@{ ok = $healthy; code = 'TOKEN_ROTATED'; token = $newToken; message = '旧二维码已失效，请重新配对。' }
}
