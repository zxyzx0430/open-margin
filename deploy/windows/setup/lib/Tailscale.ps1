# Open Margin setup engine: Tailscale Serve/Funnel route ownership and switching.

function Get-OmTailscalePath {
    $path = Find-OmTailscale
    if (-not $path) { throw 'Tailscale is not installed' }
    return $path
}

function Invoke-OmTailscale {
    param(
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [switch]$DryRun
    )
    $path = Get-OmTailscalePath
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
        [switch]$DryRun
    )
    $result = Invoke-OmTailscale -Arguments @($Kind, 'status', '--json') -DryRun:$DryRun
    if ($result.dryRun) { return $null }
    if ($result.exitCode -ne 0) { return $null }
    $text = ([string]$result.output).Trim()
    if (-not $text -or $text -eq 'null') { return $null }
    try { return $text | ConvertFrom-Json } catch { return $null }
}

function Get-OmRouteHandlers {
    param($Status)
    $routes = New-Object System.Collections.Generic.List[object]
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
                site = $site.Name
                path = $handler.Name
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

function Test-OmForeignRoutes {
    param($Status, [int]$GatewayPort)
    $routes = @(Get-OmRouteHandlers $Status)
    foreach ($route in $routes) {
        if (-not (Test-OmOurProxy -Proxy $route.proxy -GatewayPort $GatewayPort)) {
            return [pscustomobject]@{ foreign = $true; route = $route }
        }
    }
    if ($null -ne $Status) {
        $services = $Status.PSObject.Properties['Services']
        if ($null -ne $services -and $null -ne $services.Value) {
            $count = @($services.Value.PSObject.Properties).Count
            if ($count -gt 0) {
                return [pscustomobject]@{ foreign = $true; route = [pscustomobject]@{ site = 'Services'; path = ''; proxy = '' } }
            }
        }
        $tcp = $Status.PSObject.Properties['TCP']
        if ($null -ne $tcp -and $null -ne $tcp.Value) {
            foreach ($listener in @($tcp.Value.PSObject.Properties)) {
                if ($listener.Name -ne '443') {
                    return [pscustomobject]@{ foreign = $true; route = [pscustomobject]@{ site = 'TCP'; path = $listener.Name; proxy = '' } }
                }
            }
        }
    }
    return [pscustomobject]@{ foreign = $false; route = $null }
}

function Test-OmFunnelReady {
    param($Status, [int]$GatewayPort)
    if ($null -eq $Status) { return $false }
    $allow = $Status.PSObject.Properties['AllowFunnel']
    if ($null -eq $allow -or $null -eq $allow.Value) { return $false }
    $anyAllowed = $false
    foreach ($item in @($allow.Value.PSObject.Properties)) {
        if ($item.Value -eq $true) { $anyAllowed = $true }
    }
    if (-not $anyAllowed) { return $false }
    $routes = @(Get-OmRouteHandlers $Status)
    foreach ($route in $routes) {
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

function Enable-OmFunnel {
    param(
        [int]$GatewayPort = 18768,
        [int]$TimeoutSec = 600,
        [switch]$DryRun
    )
    $serve = Get-OmTailscaleStatusJson -Kind serve -DryRun:$DryRun
    $serveCheck = Test-OmServeOwnedByUs -Status $serve -GatewayPort $GatewayPort
    if (-not $serveCheck) {
        return [pscustomobject]@{
            ok = $false; code = 'FOREIGN_SERVE_ROUTE';
            message = '443 端口已有其他 Tailscale 路由，向导不会覆盖。';
            action = 'abort'
        }
    }
    $current = Get-OmTailscaleStatusJson -Kind funnel -DryRun:$DryRun
    $foreign = Test-OmForeignRoutes -Status $current -GatewayPort $GatewayPort
    if ($foreign.foreign) {
        return [pscustomobject]@{
            ok = $false; code = 'FOREIGN_FUNNEL_ROUTE';
            message = '已有其他 Funnel 路由，向导不会覆盖。';
            action = 'abort'
        }
    }
    $result = Invoke-OmTailscale -Arguments @('funnel', '--bg', '--https=443', ('http://127.0.0.1:' + $GatewayPort)) -DryRun:$DryRun
    if ($result.exitCode -ne 0 -and -not $result.dryRun) {
        return [pscustomobject]@{
            ok = $false; code = 'FUNNEL_ENABLE_FAILED';
            message = '开启 Funnel 失败。'; detail = Protect-OmText $result.output;
            action = 'retry'
        }
    }
    if ($DryRun) {
        return [pscustomobject]@{ ok = $true; code = 'DRY_RUN'; message = $result.output; url = ('https://example.ts.net:' + 443 + '/finance') }
    }
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    do {
        Start-Sleep -Seconds 3
        $status = Get-OmTailscaleStatusJson -Kind funnel
        if (Test-OmFunnelReady -Status $status -GatewayPort $GatewayPort) {
            $url = Get-OmFunnelUrl -Status $status
            return [pscustomobject]@{ ok = $true; code = 'FUNNEL_READY'; message = 'Funnel 已开启。'; url = $url }
        }
    } while ((Get-Date) -lt $deadline)
    $approval = $null
    if ([string]$result.output -match '(https://[^\s]+)') { $approval = $matches[1] }
    return [pscustomobject]@{
        ok = $false; code = 'FUNNEL_APPROVAL_REQUIRED';
        message = '需要在浏览器里允许 Tailscale Funnel。';
        approvalUrl = $approval; action = 'open_url'
    }
}

function Disable-OmFunnel {
    param([switch]$DryRun)
    $result = Invoke-OmTailscale -Arguments @('funnel', '--https=443', 'off') -DryRun:$DryRun
    if ($result.exitCode -eq 0 -or $result.dryRun) {
        return [pscustomobject]@{ ok = $true; code = 'FUNNEL_OFF'; message = $result.output }
    }
    $status = Get-OmTailscaleStatusJson -Kind funnel
    $foreign = Test-OmForeignRoutes -Status $status -GatewayPort 18768
    if (-not $foreign.foreign) {
        $reset = Invoke-OmTailscale -Arguments @('funnel', 'reset') -DryRun:$DryRun
        if ($reset.exitCode -eq 0 -or $reset.dryRun) {
            return [pscustomobject]@{ ok = $true; code = 'FUNNEL_RESET'; message = $reset.output }
        }
    }
    return [pscustomobject]@{ ok = $false; code = 'FUNNEL_DISABLE_FAILED'; message = '关闭 Funnel 失败。'; detail = Protect-OmText $result.output }
}

function Get-OmFunnelUrl {
    param($Status)
    if ($null -ne $Status) {
        $web = $Status.PSObject.Properties['Web']
        if ($null -ne $web -and $null -ne $web.Value) {
            foreach ($site in @($web.Value.PSObject.Properties)) {
                $host = ($site.Name -split ':')[0]
                if ($host -like '*.ts.net') { return ('https://' + $host + '/finance') }
            }
        }
    }
    $info = Get-OmTailscaleInfo
    if ($info.hostName) { return ('https://' + $info.hostName + '/finance') }
    return $null
}

function Start-OmTailscaleLogin {
    param([switch]$DryRun)
    if ($DryRun) {
        return [pscustomobject]@{ ok = $true; code = 'DRY_RUN'; message = 'tailscale up' }
    }
    $path = Get-OmTailscalePath
    try {
        Start-Process -FilePath $path -ArgumentList @('up') -WindowStyle Hidden | Out-Null
        return [pscustomobject]@{ ok = $true; code = 'LOGIN_STARTED'; message = '已打开 Tailscale 登录，请在浏览器完成。' }
    } catch {
        return [pscustomobject]@{ ok = $false; code = 'LOGIN_FAILED'; message = $_.Exception.Message }
    }
}
