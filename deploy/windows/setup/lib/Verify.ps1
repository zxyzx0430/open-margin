# Open Margin setup engine: deployment verification.

function Get-OmHttpStatus {
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [hashtable]$Headers = @{},
        [int]$TimeoutSec = 15
    )
    try {
        $response = Invoke-WebRequest -Uri $Url -Headers $Headers -UseBasicParsing -TimeoutSec $TimeoutSec -MaximumRedirection 2 -ErrorAction Stop
        return [pscustomobject]@{ status = [int]$response.StatusCode; body = $response.Content; error = $null }
    } catch {
        if ($_.Exception.Response) {
            $status = 0
            try { $status = [int]$_.Exception.Response.StatusCode } catch { }
            return [pscustomobject]@{ status = $status; body = $null; error = $_.Exception.Message }
        }
        return [pscustomobject]@{ status = 0; body = $null; error = $_.Exception.Message }
    }
}

function New-OmVerifyCheck {
    param([string]$Id, [string]$Title, [bool]$Ok, [string]$Detail)
    return [pscustomobject]@{
        id = $Id; title = $Title; status = $(if ($Ok) { 'ok' } else { 'fail' }); detail = $Detail
    }
}

function Invoke-OmVerify {
    param(
        [Parameter(Mandatory = $true)][string]$BaseUrl,
        [Parameter(Mandatory = $true)][string]$Token,
        [string]$ProjectName = 'personal-finance-system',
        [switch]$SkipWallosOwner
    )
    $base = $BaseUrl.TrimEnd('/')
    $checks = New-Object System.Collections.Generic.List[object]

    $health = Get-OmHttpStatus -Url ($base + '/health')
    $checks.Add((New-OmVerifyCheck 'health' '服务健康' ($health.status -eq 200) ('HTTP ' + $health.status)))

    $noToken = Get-OmHttpStatus -Url ($base + '/api/v2/sync/changes?since=0&limit=1')
    $checks.Add((New-OmVerifyCheck 'sync-auth' '同步接口需要密钥' ($noToken.status -eq 401) ('HTTP ' + $noToken.status)))

    $withToken = Get-OmHttpStatus -Url ($base + '/api/v2/sync/changes?since=0&limit=1') -Headers @{ 'X-Device-Token' = $Token }
    $serverId = $null
    if ($withToken.status -eq 200 -and $withToken.body) {
        try { $parsed = $withToken.body | ConvertFrom-Json; $serverId = [string]$parsed.server_id } catch { }
    }
    $checks.Add((New-OmVerifyCheck 'sync-read' '同步接口可读' ($withToken.status -eq 200 -and $serverId) ('HTTP ' + $withToken.status)))

    $wallos = Get-OmHttpStatus -Url ($base + '/wallos/')
    $checks.Add((New-OmVerifyCheck 'wallos-blocked' 'Wallos 不对公网开放' ($wallos.status -eq 404) ('HTTP ' + $wallos.status)))

    $mcp = Get-OmHttpStatus -Url ($base + '/mcp/')
    $checks.Add((New-OmVerifyCheck 'mcp-blocked' 'MCP 不对公网开放' ($mcp.status -eq 404) ('HTTP ' + $mcp.status)))

    if (-not $SkipWallosOwner) {
        $owner = Test-OmWallosOwner -ProjectName $ProjectName
        $checks.Add((New-OmVerifyCheck 'owner' '管理员已创建' ([bool]$owner.ok) ('user count=' + $owner.count)))
    }

    $ok = (@($checks | Where-Object { $_.status -ne 'ok' }).Count -eq 0)
    return [pscustomobject]@{
        ok = $ok
        checks = $checks.ToArray()
        serverId = $serverId
    }
}
