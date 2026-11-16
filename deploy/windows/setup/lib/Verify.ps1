# Open Margin setup engine: deployment verification.
# Windows PowerShell 5.1 compatible.

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
    param(
        [string]$Id, [string]$Title, [bool]$Ok, [string]$Detail,
        [string]$Code = '', [string]$Remediation = ''
    )
    return [pscustomobject]@{
        id = $Id; title = $Title; status = $(if ($Ok) { 'ok' } else { 'fail' });
        detail = $Detail; code = $Code; remediation = $Remediation
    }
}

function Invoke-OmVerify {
    param(
        [Parameter(Mandatory = $true)][string]$BaseUrl,
        [Parameter(Mandatory = $true)][string]$Token,
        [string]$ProjectName = 'personal-finance-system',
        [string]$RepoRoot = (Get-OmRepoRoot),
        [switch]$SkipWallosOwner,
        [switch]$DryRun
    )
    $base = $BaseUrl.TrimEnd('/')
    $checks = [System.Collections.Generic.List[object]]::new()
    if ($DryRun) {
        foreach ($item in @(
            @('health', '服务健康'), @('sync-auth', '同步接口需要密钥'),
            @('sync-read', '同步接口可读'), @('wallos-blocked', 'Wallos 不对公网开放'),
            @('mcp-blocked', 'MCP 不对公网开放'), @('owner', '管理员已创建')
        )) {
            $checks.Add((New-OmVerifyCheck $item[0] $item[1] $true 'DRY RUN'))
        }
        return [pscustomobject]@{
            ok = $true; dryRun = $true; checks = $checks.ToArray(); serverId = 'dryrun00000000000000000000000000'
        }
    }

    $health = Get-OmHttpStatus -Url ($base + '/health')
    $checks.Add((New-OmVerifyCheck 'health' '服务健康' ($health.status -eq 200) ('HTTP ' + $health.status) 'HEALTH_FAILED' '检查 Docker 容器和网关是否在运行。'))

    $noToken = Get-OmHttpStatus -Url ($base + '/api/v2/sync/changes?since=0&limit=1')
    $checks.Add((New-OmVerifyCheck 'sync-auth' '同步接口需要密钥' ($noToken.status -eq 401) ('HTTP ' + $noToken.status) 'SYNC_AUTH_FAILED' '确认公网入口指向同步网关，而不是完整网页。'))

    $withToken = Get-OmHttpStatus -Url ($base + '/api/v2/sync/changes?since=0&limit=1') -Headers @{ 'X-Device-Token' = $Token }
    $serverId = $null
    if ($withToken.status -eq 200 -and $withToken.body) {
        try { $parsed = $withToken.body | ConvertFrom-Json; $serverId = [string]$parsed.server_id } catch { }
    }
    $checks.Add((New-OmVerifyCheck 'sync-read' '同步接口可读' ($withToken.status -eq 200 -and -not [string]::IsNullOrWhiteSpace($serverId)) ('HTTP ' + $withToken.status) 'SYNC_READ_FAILED' '设备密钥可能不一致；重新生成配对码。'))

    $wallos = Get-OmHttpStatus -Url ($base + '/wallos/')
    $checks.Add((New-OmVerifyCheck 'wallos-blocked' 'Wallos 不对公网开放' ($wallos.status -eq 404) ('HTTP ' + $wallos.status) 'WALLOS_EXPOSED' '公网网关不应代理 Wallos 页面。'))

    $mcp = Get-OmHttpStatus -Url ($base + '/mcp/')
    $checks.Add((New-OmVerifyCheck 'mcp-blocked' 'MCP 不对公网开放' ($mcp.status -eq 404) ('HTTP ' + $mcp.status) 'MCP_EXPOSED' '公网网关不应代理 MCP。'))

    if (-not $SkipWallosOwner) {
        $owner = Test-OmWallosOwner -ProjectName $ProjectName -RepoRoot $RepoRoot
        $checks.Add((New-OmVerifyCheck 'owner' '管理员已创建' ([bool]$owner.ok) ('user count=' + $owner.count) 'OWNER_MISSING' '打开 Wallos 注册页完成管理员创建，然后重试。'))
    }

    $ok = (@($checks | Where-Object { $_.status -ne 'ok' }).Count -eq 0)
    return [pscustomobject]@{
        ok = $ok
        checks = $checks.ToArray()
        serverId = $serverId
    }
}
