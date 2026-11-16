# Open Margin setup engine: Tailscale Serve/Funnel route ownership and switching.
# Windows PowerShell 5.1 compatible.

function Get-OmTailscalePath {
    $path = Find-OmTailscale
    if (-not $path) { throw 'Tailscale is not installed' }
    return $path
}

function Invoke-OmTailscale {
    param(
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [switch]$DryRun,
        [int]$TimeoutSec = 30
    )
    $path = Find-OmTailscale
    if (-not $path) {
        return [pscustomobject]@{ exitCode = -1; output = 'tailscale not found'; dryRun = $false }
    }
    $display = 'tailscale ' + ($Arguments -join ' ')
    if ($DryRun) {
        return [pscustomobject]@{ exitCode = 0; output = 'DRY RUN: ' + $display; dryRun = $true }
    }
    Write-OmLog -Level INFO -Message $display
    try {
        $output = & $path @Arguments 2>&1 | Out-String
        return [pscustomobject]@{ exitCode = $LASTEXITCODE; output = $output; dryRun = $false }
    } catch {
        return [pscustomobject]@{ exitCode = -1; output = $_.Exception.Message; dryRun = $false }
    }
}

function Get-OmTailscaleStatusJson {
    param(
        [ValidateSet('serve', 'funnel')][string]$Kind = 'funnel',
        [switch]$DryRun,
        $OverrideStatus = $null
    )
    if ($null -ne $OverrideStatus) { return $OverrideStatus }
    $result = Invoke-OmTailscale -Arguments @($Kind, 'status', '--json') -DryRun:$DryRun
    if ($result.dryRun) { return $null }
    if ($result.exitCode -ne 0) { return $null }
    $text = ([string]$result.output).Trim()
    if (-not $text -or $text -eq 'null') { return $null }
    try { return $text | ConvertFrom-Json } catch { return $null }
}

function Get-OmRouteHandlers {
    param($Status)
    $routes = [System.Collections.Generic.List[object]]::new()
    if ($null -eq $Status) { return @() }
    $web = $Status.PSObject.Properties['Web']
    if ($null -eq $web -or $null -eq $web.Value) { return @() }
    foreach ($site in @($web.Value.PSObject.Properties)) {
        $handlers = $site.Value.PSObject.Properties['Handlers']
        if ($null -eq $handlers -or $null -eq $handlers.Value) { continue }
        foreach ($handler in @($handlers.Value.PSObject.Properties)) {
            $proxy = $null
            if ($handler.Value.PSObject.Properties['Proxy']) { $proxy = [string]$handler.Value.Proxy }
            $routes.Add([pscustomobject]@{
                site  = $site.Name
                path  = $handler.Name
                proxy = $proxy
            })
        }
    }
    return @($routes)
}

function Test-OmOurProxy {
    param([string]$Proxy, [int]$GatewayPort)
    if (-not $Proxy) { return $false }
    $normalized = $Proxy.Trim().TrimEnd('/')
    $ours = @(
        ('http://127.0.0.1:' + $GatewayPort),
        ('http://localhost:' + $GatewayPort)
    )
    return ($ours -contains $normalized)
}

function Get-OmForeignRoutes {
    param($Status, [int]$GatewayPort)
    $foreign = [System.Collections.Generic.List[object]]::new()
    foreach ($route in @(Get-OmRouteHandlers $Status)) {
        if (-not (Test-OmOurProxy -Proxy $route.proxy -GatewayPort $GatewayPort)) {
            $foreign.Add($route)
        }
    }
    if ($null -ne $Status) {
        $services = $Status.PSObject.Properties['Services']
        if ($null -ne $services -and $null -ne $services.Value) {
            $count = @($services.Value.PSObject.Properties).Count
            if ($count -gt 0) {
                $foreign.Add([pscustomobject]@{ site = 'Services'; path = ''; proxy = '' })
            }
        }
        $tcp = $Status.PSObject.Properties['TCP']
        if ($null -ne $tcp -and $null -ne $tcp.Value) {
            foreach ($listener in @($tcp.Value.PSObject.Properties)) {
                if ($listener.Name -ne '443') {
                    $foreign.Add([pscustomobject]@{ site = 'TCP'; path = $listener.Name; proxy = '' })
                }
            }
        }
    }
    return @($foreign)
}

function Test-OmForeignRoutes {
    param($Status, [int]$GatewayPort)
    $foreign = @(Get-OmForeignRoutes -Status $Status -GatewayPort $GatewayPort)
    if ($foreign.Count -gt 0) {
        return [pscustomobject]@{ foreign = $true; route = $foreign[0]; routes = $foreign }
    }
    return [pscustomobject]@{ foreign = $false; route = $null; routes = @() }
}

function Test-OmFunnelApproved {
    param($Status)
    if ($null -eq $Status) { return $false }
    $allow = $Status.PSObject.Properties['AllowFunnel']
    if ($null -eq $allow -or $null -eq $allow.Value) { return $false }
    foreach ($item in @($allow.Value.PSObject.Properties)) {
        if ($item.Value -eq $true) { return $true }
    }
    return $false
}

function Test-OmFunnelReady {
    param($Status, [int]$GatewayPort)
    if ($null -eq $Status) { return $false }
    if (-not (Test-OmFunnelApproved -Status $Status)) { return $false }
    foreach ($route in @(Get-OmRouteHandlers $Status)) {
        if ((Test-OmOurProxy -Proxy $route.proxy -GatewayPort $GatewayPort) -and $route.path -eq '/') {
            return $true
        }
    }
    return $false
}

function Test-OmServeOwnedByUs {
    param($Status, [int]$GatewayPort)
    if ($null -eq $Status) { return $true }
    $foreign = Test-OmForeignRoutes -Status $Status -GatewayPort $GatewayPort
    return (-not $foreign.foreign)
}

function Start-OmTailscaleProcess {
    param(
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$OutputDirectory
    )
    $path = Get-OmTailscalePath
    if (-not (Test-Path $OutputDirectory)) { New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null }
    $stamp = [guid]::NewGuid().ToString('N')
    $stdout = Join-Path $OutputDirectory ('tailscale-' + $stamp + '.out.log')
    $stderr = Join-Path $OutputDirectory ('tailscale-' + $stamp + '.err.log')
    Write-OmLog -Level INFO -Message ('tailscale ' + ($Arguments -join ' ') + ' (background)')
    $process = Start-Process -FilePath $path -ArgumentList $Arguments -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    return [pscustomobject]@{ process = $process; stdout = $stdout; stderr = $stderr }
}

function Get-OmTailscaleProcessOutput {
    param($Handle)
    if ($null -eq $Handle) { return '' }
    $text = ''
    foreach ($file in @($Handle.stdout, $Handle.stderr)) {
        if ($file -and (Test-Path $file)) {
            try { $text += (Get-Content -Path $file -Raw -Encoding UTF8) } catch { }
        }
    }
    return (Protect-OmText $text)
}

function Find-OmApprovalUrl {
    param([string]$Text)
    if ([string]::IsNullOrEmpty($Text)) { return $null }
    if ($Text -match '(https://login\.tailscale\.com/[^\s"'']+)') { return $matches[1] }
    if ($Text -match '(https://[^\s"'']*funnel[^\s"'']*)') { return $matches[1] }
    return $null
}

function Enable-OmFunnel {
    param(
        [int]$GatewayPort = 18768,
        [int]$TimeoutSec = 600,
        [int]$ApprovalWaitSec = 25,
        [string]$StateRoot = (Get-OmStateRoot),
        [switch]$DryRun,
        $ServeStatus = $null,
        $FunnelStatus = $null,
        [switch]$UseInjectedStatus
    )
    if ($DryRun) {
        return [pscustomobject]@{
            ok = $true; code = 'DRY_RUN'; dryRun = $true;
            message = ('tailscale funnel --bg --https=443 http://127.0.0.1:' + $GatewayPort);
            url = 'https://example.ts.net/finance'
        }
    }
    if (-not $UseInjectedStatus -and -not (Find-OmTailscale)) {
        return [pscustomobject]@{ ok = $false; code = 'TAILSCALE_MISSING'; message = 'Tailscale 未安装。'; action = 'install_deps' }
    }
    if (-not $UseInjectedStatus) {
        $ServeStatus = Get-OmTailscaleStatusJson -Kind serve
        $FunnelStatus = Get-OmTailscaleStatusJson -Kind funnel
    }
    if (-not (Test-OmServeOwnedByUs -Status $ServeStatus -GatewayPort $GatewayPort)) {
        return [pscustomobject]@{
            ok = $false; code = 'FOREIGN_SERVE_ROUTE';
            message = '443 端口已有其他 Tailscale 路由，向导不会覆盖。';
            action = 'abort'; retryable = $false
        }
    }
    $foreign = Test-OmForeignRoutes -Status $FunnelStatus -GatewayPort $GatewayPort
    if ($foreign.foreign) {
        return [pscustomobject]@{
            ok = $false; code = 'FOREIGN_FUNNEL_ROUTE';
            message = '已有其他 Funnel 路由，向导不会覆盖。';
            action = 'abort'; retryable = $false; route = $foreign.route
        }
    }
    if (Test-OmFunnelReady -Status $FunnelStatus -GatewayPort $GatewayPort) {
        return [pscustomobject]@{
            ok = $true; code = 'FUNNEL_READY'; message = 'Funnel 已经开启。';
            url = (Get-OmFunnelUrl -Status $FunnelStatus)
        }
    }

    $outputDir = Join-Path $StateRoot 'logs'
    $handle = Start-OmTailscaleProcess -Arguments @('funnel', '--bg', '--https=443', ('http://127.0.0.1:' + $GatewayPort)) -OutputDirectory $outputDir
    $deadline = (Get-Date).AddSeconds($ApprovalWaitSec)
    $approvalUrl = $null
    do {
        Start-Sleep -Seconds 2
        if ($UseInjectedStatus) {
            $status = Get-OmTailscaleStatusJson -Kind funnel -OverrideStatus $FunnelStatus
        } else {
            $status = Get-OmTailscaleStatusJson -Kind funnel
        }
        if (Test-OmFunnelReady -Status $status -GatewayPort $GatewayPort) {
            return [pscustomobject]@{
                ok = $true; code = 'FUNNEL_READY'; message = 'Funnel 已开启。';
                url = (Get-OmFunnelUrl -Status $status); pending = $false
            }
        }
        $text = Get-OmTailscaleProcessOutput -Handle $handle
        if (-not $approvalUrl) { $approvalUrl = Find-OmApprovalUrl -Text $text }
        if ($handle.process.HasExited -and -not $approvalUrl) { break }
    } while ((Get-Date) -lt $deadline)

    $text = Get-OmTailscaleProcessOutput -Handle $handle
    if (-not $approvalUrl) { $approvalUrl = Find-OmApprovalUrl -Text $text }
    if ($approvalUrl) {
        return [pscustomobject]@{
            ok = $false; code = 'FUNNEL_APPROVAL_REQUIRED';
            message = '需要在浏览器里允许 Tailscale Funnel，然后回来自动继续。';
            approvalUrl = $approvalUrl; action = 'open_url'; pending = $true; retryable = $true
        }
    }
    if (-not $handle.process.HasExited) {
        return [pscustomobject]@{
            ok = $false; code = 'FUNNEL_PENDING';
            message = 'Funnel 命令仍在等待 Tailscale 确认。';
            action = 'wait'; pending = $true; retryable = $true
        }
    }
    return [pscustomobject]@{
        ok = $false; code = 'FUNNEL_ENABLE_FAILED';
        message = '开启 Funnel 失败。'; detail = $text;
        action = 'retry'; retryable = $true
    }
}

function Wait-OmFunnelApproval {
    param(
        [int]$GatewayPort = 18768,
        [int]$TimeoutSec = 600,
        [int]$PollSeconds = 3,
        [switch]$DryRun
    )
    if ($DryRun) {
        return [pscustomobject]@{ ok = $true; code = 'DRY_RUN'; url = 'https://example.ts.net/finance' }
    }
    if (-not (Find-OmTailscale)) {
        return [pscustomobject]@{ ok = $false; code = 'TAILSCALE_MISSING'; message = 'Tailscale 未安装。'; retryable = $false }
    }
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    do {
        $status = Get-OmTailscaleStatusJson -Kind funnel
        if (Test-OmFunnelReady -Status $status -GatewayPort $GatewayPort) {
            return [pscustomobject]@{ ok = $true; code = 'FUNNEL_READY'; message = 'Funnel 已开启。'; url = (Get-OmFunnelUrl -Status $status) }
        }
        $foreign = Test-OmForeignRoutes -Status $status -GatewayPort $GatewayPort
        if ($foreign.foreign) {
            return [pscustomobject]@{ ok = $false; code = 'FOREIGN_FUNNEL_ROUTE'; message = '检测到其他 Funnel 路由。'; retryable = $false }
        }
        Start-Sleep -Seconds $PollSeconds
    } while ((Get-Date) -lt $deadline)
    return [pscustomobject]@{
        ok = $false; code = 'FUNNEL_APPROVAL_TIMEOUT';
        message = '还没有检测到 Funnel 授权。请打开授权页点“允许”，然后重试。';
        approvalUrl = 'https://login.tailscale.com/admin/machines'; action = 'open_url'; retryable = $true
    }
}

function Disable-OmFunnel {
    param(
        [int]$GatewayPort = 18768,
        [switch]$DryRun,
        $FunnelStatus = $null,
        [switch]$UseInjectedStatus
    )
    if ($DryRun) {
        return [pscustomobject]@{ ok = $true; code = 'DRY_RUN'; message = 'tailscale funnel --https=443 off' }
    }
    if (-not $UseInjectedStatus -and -not (Find-OmTailscale)) {
        return [pscustomobject]@{ ok = $true; code = 'TAILSCALE_MISSING'; message = 'Tailscale 未安装，无需关闭 Funnel。' }
    }
    if (-not $UseInjectedStatus) {
        $FunnelStatus = Get-OmTailscaleStatusJson -Kind funnel
    }
    if ($null -eq $FunnelStatus -and -not $UseInjectedStatus) {
        return [pscustomobject]@{
            ok = $false; code = 'FUNNEL_STATUS_UNAVAILABLE';
            message = '无法读取 Funnel 状态，为避免误关别人的路由，本次不执行关闭。'; retryable = $true
        }
    }
    $foreign = Test-OmForeignRoutes -Status $FunnelStatus -GatewayPort $GatewayPort
    $hasOurs = $false
    foreach ($route in @(Get-OmRouteHandlers $FunnelStatus)) {
        if (Test-OmOurProxy -Proxy $route.proxy -GatewayPort $GatewayPort) { $hasOurs = $true }
    }
    if ($foreign.foreign) {
        return [pscustomobject]@{
            ok = $false; code = 'FOREIGN_FUNNEL_ROUTE';
            message = '443 上还有别的 Funnel 路由，向导不会一起关掉。'; retryable = $false
        }
    }
    if (-not $hasOurs -and $null -ne $FunnelStatus) {
        return [pscustomobject]@{ ok = $true; code = 'FUNNEL_ALREADY_OFF'; message = 'Funnel 原本就没有开启。' }
    }
    $result = Invoke-OmTailscale -Arguments @('funnel', '--https=443', 'off')
    if ($result.exitCode -eq 0) {
        return [pscustomobject]@{ ok = $true; code = 'FUNNEL_OFF'; message = 'Funnel 已关闭。' }
    }
    return [pscustomobject]@{ ok = $false; code = 'FUNNEL_DISABLE_FAILED'; message = '关闭 Funnel 失败。'; detail = Protect-OmText $result.output; retryable = $true }
}

function Get-OmFunnelUrl {
    param($Status)
    if ($null -ne $Status) {
        $web = $Status.PSObject.Properties['Web']
        if ($null -ne $web -and $null -ne $web.Value) {
            foreach ($site in @($web.Value.PSObject.Properties)) {
                $hostName = ($site.Name -split ':')[0]
                if ($hostName -like '*.ts.net') { return ('https://' + $hostName + '/finance') }
            }
        }
    }
    $info = Get-OmTailscaleInfo
    if ($info.hostName) { return ('https://' + $info.hostName + '/finance') }
    return $null
}

function Wait-OmPublicDns {
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [int]$TimeoutSec = 600,
        [int]$PollSeconds = 10,
        [switch]$DryRun
    )
    if ($DryRun) {
        return [pscustomobject]@{ ok = $true; code = 'DRY_RUN'; status = 200 }
    }
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $lastError = $null
    do {
        $probe = Get-OmHttpStatus -Url $Url -TimeoutSec 10
        if ($probe.status -eq 200) {
            return [pscustomobject]@{ ok = $true; code = 'PUBLIC_DNS_READY'; status = 200 }
        }
        $lastError = $probe.error
        if ($probe.status -in @(401, 403, 404)) {
            # TLS/DNS already work; the path itself is answering.
            return [pscustomobject]@{ ok = $true; code = 'PUBLIC_DNS_READY'; status = $probe.status }
        }
        Start-Sleep -Seconds $PollSeconds
    } while ((Get-Date) -lt $deadline)
    return [pscustomobject]@{
        ok = $false; code = 'PUBLIC_DNS_TIMEOUT';
        message = '公网地址还没有生效，通常需要几分钟。'; detail = $lastError; retryable = $true
    }
}

function Start-OmTailscaleLogin {
    param([switch]$DryRun)
    if ($DryRun) {
        return [pscustomobject]@{ ok = $true; code = 'DRY_RUN'; message = 'tailscale up' }
    }
    $info = Get-OmTailscaleInfo
    if (-not $info.installed) {
        return [pscustomobject]@{ ok = $false; code = 'TAILSCALE_MISSING'; message = 'Tailscale 未安装。'; action = 'install_deps' }
    }
    if ($info.running) {
        return [pscustomobject]@{ ok = $true; code = 'TAILSCALE_RUNNING'; message = ('Tailscale 已连接：' + $info.hostName) }
    }
    try {
        $path = Get-OmTailscalePath
        Start-Process -FilePath $path -ArgumentList @('up') -WindowStyle Hidden | Out-Null
        return [pscustomobject]@{ ok = $true; code = 'LOGIN_STARTED'; message = '已打开 Tailscale 登录，请在浏览器完成。' }
    } catch {
        return [pscustomobject]@{ ok = $false; code = 'LOGIN_FAILED'; message = $_.Exception.Message; retryable = $true }
    }
}
