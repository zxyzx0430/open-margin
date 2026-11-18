# Open Margin setup engine: deployment verification.
# Windows PowerShell 5.1 compatible.

function Invoke-OmHttpProbe {
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [hashtable]$Headers = @{},
        [int]$TimeoutSec = 15,
        [switch]$UseSystemProxy
    )
    try {
        $protocol = [System.Net.ServicePointManager]::SecurityProtocol
        if (-not ($protocol -band [System.Net.SecurityProtocolType]::Tls12)) {
            [System.Net.ServicePointManager]::SecurityProtocol = $protocol -bor [System.Net.SecurityProtocolType]::Tls12
        }
    } catch { }
    try {
        $request = [System.Net.HttpWebRequest]::Create($Url)
        $request.Method = 'GET'
        $request.Timeout = $TimeoutSec * 1000
        $request.ReadWriteTimeout = $TimeoutSec * 1000
        $request.AllowAutoRedirect = $true
        $request.MaximumAutomaticRedirections = 2
        if ($UseSystemProxy) {
            $proxy = [System.Net.WebRequest]::DefaultWebProxy
            if ($null -eq $proxy) { $proxy = [System.Net.WebRequest]::GetSystemWebProxy() }
            $request.Proxy = $proxy
        } else {
            $request.Proxy = $null
        }
        foreach ($key in $Headers.Keys) { $request.Headers[[string]$key] = [string]$Headers[$key] }
        $response = $request.GetResponse()
        $status = [int]$response.StatusCode
        $reader = New-Object System.IO.StreamReader($response.GetResponseStream())
        try { $body = $reader.ReadToEnd() } finally { $reader.Dispose() }
        $response.Close()
        return [pscustomobject]@{ status = $status; body = $body; error = $null }
    } catch [System.Net.WebException] {
        if ($_.Exception.Response) {
            $status = 0
            try { $status = [int]$_.Exception.Response.StatusCode } catch { }
            return [pscustomobject]@{ status = $status; body = $null; error = $_.Exception.Message }
        }
        return [pscustomobject]@{ status = 0; body = $null; error = $_.Exception.Message }
    } catch {
        return [pscustomobject]@{ status = 0; body = $null; error = $_.Exception.Message }
    }
}

function Get-OmHttpStatus {
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [hashtable]$Headers = @{},
        [int]$TimeoutSec = 15,
        [ValidateSet('auto', 'direct', 'system')][string]$ProxyMode = 'auto'
    )
    # The public *.ts.net address is usually reachable directly even when the
    # user runs a commercial VPN, and routing it through the system proxy can
    # break its TLS handshake. auto tries direct first and falls back to the
    # system proxy only when the transport itself failed (status 0).
    $modes = @($ProxyMode)
    if ($ProxyMode -eq 'auto') { $modes = @('direct', 'system') }
    $result = $null
    foreach ($mode in $modes) {
        $result = Invoke-OmHttpProbe -Url $Url -Headers $Headers -TimeoutSec $TimeoutSec -UseSystemProxy:($mode -eq 'system')
        $via = $(if ($mode -eq 'system') { 'proxy' } else { 'direct' })
        $result | Add-Member -NotePropertyName via -NotePropertyValue $via -Force
        if ($result.status -ne 0) { return $result }
    }
    return $result
}

function Get-OmDeviceStatus {
    param(
        [string]$BaseUrl = 'http://127.0.0.1:8767',
        [string]$AdminToken = '',
        [int]$TimeoutSec = 5
    )
    if ([string]::IsNullOrWhiteSpace($AdminToken)) {
        $envPath = Get-OmBackendEnvPath
        if (Test-Path -LiteralPath $envPath) {
            $AdminToken = [string](Get-OmEnvValueFromFile -Name 'FINANCE_ADMIN_TOKEN' -Path $envPath)
        }
    }
    if ([string]::IsNullOrWhiteSpace($AdminToken)) {
        return [pscustomobject]@{ ok = $false; seen = $false; at = $null; code = 'NO_ADMIN_TOKEN' }
    }
    # The status endpoint lives on the loopback API only; never send the admin
    # token through a proxy.
    $probe = Get-OmHttpStatus -Url ($BaseUrl.TrimEnd('/') + '/api/v1/device-status') -Headers @{ 'X-Admin-Token' = $AdminToken } -TimeoutSec $TimeoutSec -ProxyMode 'direct'
    if ($probe.status -ne 200 -or [string]::IsNullOrWhiteSpace($probe.body)) {
        return [pscustomobject]@{ ok = $false; seen = $false; at = $null; code = ('HTTP_' + $probe.status) }
    }
    try {
        $data = $probe.body | ConvertFrom-Json
    } catch {
        return [pscustomobject]@{ ok = $false; seen = $false; at = $null; code = 'BAD_RESPONSE' }
    }
    return [pscustomobject]@{ ok = $true; seen = [bool]$data.seen; at = $data.at; code = 'OK' }
}

function Update-OmPhoneVerified {
    param(
        [string]$BaseUrl = 'http://127.0.0.1:8767',
        [switch]$DryRun
    )
    if ($DryRun) {
        return [pscustomobject]@{ ok = $true; dryRun = $true; changed = $false; seen = $false; code = 'DRY_RUN' }
    }
    $state = Read-OmState
    if ([string]$state.step -eq 'DISABLED') {
        return [pscustomobject]@{ ok = $true; changed = $false; seen = [bool]$state.phoneVerified; code = 'DISABLED' }
    }
    $status = Get-OmDeviceStatus -BaseUrl $BaseUrl
    if (-not $status.ok -or -not $status.seen) {
        return [pscustomobject]@{ ok = $false; changed = $false; seen = $false; at = $null; code = $status.code }
    }
    $changed = $false
    if (-not [bool]$state.phoneVerified) {
        $null = Set-OmStateValue -Name phoneVerified -Value $true
        $changed = $true
    }
    if ([string]$state.step -eq 'COMPLETE') {
        try {
            $null = Set-OmStep 'PHONE_VERIFIED'
            $changed = $true
        } catch {
            Write-OmLog -Level WARN -Message ('phone verification step transition failed: ' + $_.Exception.Message)
        }
    }
    return [pscustomobject]@{ ok = $true; changed = $changed; seen = $true; at = $status.at; code = 'PHONE_SEEN' }
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

    $probeMode = Get-OmPublicProbeMode
    $health = Get-OmHttpStatus -Url ($base + '/health') -ProxyMode $probeMode
    $checks.Add((New-OmVerifyCheck 'health' '服务健康' ($health.status -eq 200) ('HTTP ' + $health.status) 'HEALTH_FAILED' '检查 Docker 容器和网关是否在运行。'))

    $noToken = Get-OmHttpStatus -Url ($base + '/api/v2/sync/changes?since=0&limit=1') -ProxyMode $probeMode
    $checks.Add((New-OmVerifyCheck 'sync-auth' '同步接口需要密钥' ($noToken.status -eq 401) ('HTTP ' + $noToken.status) 'SYNC_AUTH_FAILED' '确认公网入口指向同步网关，而不是完整网页。'))

    $withToken = Get-OmHttpStatus -Url ($base + '/api/v2/sync/changes?since=0&limit=1') -Headers @{ 'X-Device-Token' = $Token } -ProxyMode $probeMode
    $serverId = $null
    if ($withToken.status -eq 200 -and $withToken.body) {
        try { $parsed = $withToken.body | ConvertFrom-Json; $serverId = [string]$parsed.server_id } catch { }
    }
    $checks.Add((New-OmVerifyCheck 'sync-read' '同步接口可读' ($withToken.status -eq 200 -and -not [string]::IsNullOrWhiteSpace($serverId)) ('HTTP ' + $withToken.status) 'SYNC_READ_FAILED' '设备密钥可能不一致；重新生成配对码。'))

    $wallos = Get-OmHttpStatus -Url ($base + '/wallos/') -ProxyMode $probeMode
    $checks.Add((New-OmVerifyCheck 'wallos-blocked' 'Wallos 不对公网开放' ($wallos.status -eq 404) ('HTTP ' + $wallos.status) 'WALLOS_EXPOSED' '公网网关不应代理 Wallos 页面。'))

    $mcp = Get-OmHttpStatus -Url ($base + '/mcp/') -ProxyMode $probeMode
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
