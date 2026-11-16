# Open Margin setup engine: environment probes.
# Read-only. Never invokes an installer. Windows PowerShell 5.1 compatible.

try {
    if ([Net.ServicePointManager]::SecurityProtocol -notmatch 'Tls12') {
        [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    }
} catch { }

function Test-OmAdmin {
    try {
        $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
        $principal = New-Object Security.Principal.WindowsPrincipal($identity)
        return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    } catch {
        return $false
    }
}

function Get-OmWindowsInfo {
    $os = $null
    try { $os = Get-CimInstance Win32_OperatingSystem -ErrorAction Stop } catch { }
    $build = [Environment]::OSVersion.Version.Build
    return [pscustomobject]@{
        caption     = if ($os) { $os.Caption } else { 'Windows' }
        version     = [Environment]::OSVersion.Version.ToString()
        build       = $build
        is64Bit     = [Environment]::Is64BitOperatingSystem
        isWin10     = ($build -ge 10240 -and $build -lt 22000)
        isWin11     = ($build -ge 22000)
        isAdmin     = (Test-OmAdmin)
        systemDrive = $env:SystemDrive
    }
}

function Test-OmVirtualization {
    $value = $null
    try {
        $cpu = Get-CimInstance Win32_Processor -ErrorAction Stop | Select-Object -First 1
        if ($null -ne $cpu -and $null -ne $cpu.VirtualizationFirmwareEnabled) {
            $value = [bool]$cpu.VirtualizationFirmwareEnabled
        }
    } catch { }
    if ($null -eq $value) {
        try {
            $info = & systeminfo.exe 2>$null | Out-String
            if ($info -match '(?im)Virtualization Enabled In Firmware\s*:\s*(Yes|No)') {
                $value = ($matches[1] -eq 'Yes')
            } elseif ($info -match '(?m)固件中已启用虚拟化\s*:\s*(是|否)') {
                $value = ($matches[1] -eq '是')
            }
        } catch { }
    }
    return $value
}

function Get-OmDiskInfo {
    $driveLetter = $env:SystemDrive.TrimEnd(':')
    try {
        $drive = Get-PSDrive -Name $driveLetter -ErrorAction Stop
        $freeGb = [math]::Round($drive.Free / 1GB, 1)
        return [pscustomobject]@{ drive = $env:SystemDrive; freeGB = $freeGb; ok = ($freeGb -ge 10) }
    } catch {
        return [pscustomobject]@{ drive = $env:SystemDrive; freeGB = $null; ok = $false }
    }
}

function Get-OmCommandPath {
    param([Parameter(Mandatory = $true)][string]$Name)
    $cmd = Get-Command $Name -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}

function Get-OmDockerInfo {
    $docker = Get-OmCommandPath 'docker'
    if (-not $docker) {
        return [pscustomobject]@{
            installed = $false; running = $false; version = $null;
            compose = $false; path = $null; error = 'docker not found'
        }
    }
    $version = $null
    $running = $false
    $errorText = $null
    try {
        $version = (& $docker version --format '{{.Server.Version}}' 2>$null | Out-String).Trim()
        if ($LASTEXITCODE -eq 0 -and $version) { $running = $true }
    } catch {
        $errorText = $_.Exception.Message
    }
    $compose = $false
    try {
        & $docker compose version *> $null
        $compose = ($LASTEXITCODE -eq 0)
    } catch { }
    return [pscustomobject]@{
        installed = $true; running = $running; version = $version;
        compose = $compose; path = $docker; error = $errorText
    }
}

function Get-OmWslInfo {
    $wsl = Get-OmCommandPath 'wsl'
    if (-not $wsl) {
        return [pscustomobject]@{ installed = $false; ready = $false; path = $null }
    }
    $ready = $false
    try {
        & $wsl --status *> $null
        $ready = ($LASTEXITCODE -eq 0)
    } catch { }
    return [pscustomobject]@{ installed = $true; ready = $ready; path = $wsl }
}

function Find-OmTailscale {
    $cmd = Get-OmCommandPath 'tailscale'
    if ($cmd) { return $cmd }
    $candidate = Join-Path $env:ProgramFiles 'Tailscale\tailscale.exe'
    if (Test-Path $candidate) { return $candidate }
    $programFilesX86 = [Environment]::GetEnvironmentVariable('ProgramFiles(x86)')
    if ($programFilesX86) {
        $candidate86 = Join-Path $programFilesX86 'Tailscale\tailscale.exe'
        if (Test-Path $candidate86) { return $candidate86 }
    }
    return $null
}

function Get-OmTailscaleInfo {
    $path = Find-OmTailscale
    if (-not $path) {
        return [pscustomobject]@{
            installed = $false; running = $false; loggedIn = $false;
            hostName = $null; backendState = $null; version = $null;
            path = $null; error = 'tailscale not found'
        }
    }
    $version = $null
    try { $version = (& $path version 2>$null | Select-Object -First 1 | Out-String).Trim() } catch { }
    $status = $null
    try {
        $raw = (& $path status --json 2>$null | Out-String)
        if ($LASTEXITCODE -eq 0 -and $raw.Trim()) { $status = $raw | ConvertFrom-Json }
    } catch { }
    $backend = if ($status -and $status.PSObject.Properties['BackendState']) { $status.BackendState } else { $null }
    $self = if ($status -and $status.PSObject.Properties['Self']) { $status.Self } else { $null }
    $dnsName = if ($self -and $self.PSObject.Properties['DNSName']) { ([string]$self.DNSName).TrimEnd('.') } else { $null }
    return [pscustomobject]@{
        installed   = $true
        running     = ($backend -eq 'Running')
        loggedIn    = ($backend -eq 'Running' -and $dnsName -like '*.ts.net')
        hostName    = $dnsName
        backendState = $backend
        version     = $version
        path        = $path
        error       = $null
    }
}

function Test-OmHttpReachable {
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [int]$TimeoutSec = 5
    )
    try {
        $response = Invoke-WebRequest -Uri $Url -Method Head -UseBasicParsing -TimeoutSec $TimeoutSec -MaximumRedirection 3 -ErrorAction Stop
        return [pscustomobject]@{ ok = $true; status = [int]$response.StatusCode; error = $null }
    } catch {
        $status = $null
        if ($_.Exception.Response) {
            try { $status = [int]$_.Exception.Response.StatusCode } catch { }
        }
        if ($null -ne $status) { return [pscustomobject]@{ ok = $true; status = $status; error = $null } }
        return [pscustomobject]@{ ok = $false; status = $null; error = $_.Exception.Message }
    }
}

function Get-OmNetworkChecks {
    param([switch]$SkipNetwork)
    if ($SkipNetwork) {
        return [pscustomobject]@{
            dockerRegistry = $null; tailscaleControl = $null; github = $null; skipped = $true
        }
    }
    return [pscustomobject]@{
        dockerRegistry   = Test-OmHttpReachable 'https://registry-1.docker.io/v2/'
        tailscaleControl = Test-OmHttpReachable 'https://controlplane.tailscale.com/'
        github           = Test-OmHttpReachable 'https://github.com/'
        skipped          = $false
    }
}

function New-OmCheck {
    param(
        [string]$Id, [string]$Title, [ValidateSet('ok', 'warn', 'fail', 'unknown')][string]$Status,
        [string]$Detail, [string]$Remediation = '', [string]$Code = ''
    )
    return [pscustomobject]@{
        id = $Id; title = $Title; status = $Status; detail = $Detail;
        remediation = $Remediation; code = $Code
    }
}

function Get-OmEnvironmentChecks {
    param(
        [switch]$SkipNetwork,
        [switch]$SkipPorts,
        $Ports = $null,
        [string]$RepoRoot = $script:OmRepoRoot,
        [int[]]$IgnorePorts = @()
    )
    $checks = [System.Collections.Generic.List[object]]::new()
    $win = Get-OmWindowsInfo
    if (-not $win.is64Bit) {
        $checks.Add((New-OmCheck 'windows' '操作系统' 'fail' ($win.caption + ' 不是 64 位。') '需要 64 位 Windows 10 22H2 或 Windows 11。' 'WINDOWS_NOT_64BIT'))
    } elseif ($win.build -lt 19044) {
        $checks.Add((New-OmCheck 'windows' '操作系统' 'fail' ($win.caption + ' 版本过旧（build ' + $win.build + '）。') '升级到 Windows 10 22H2 或 Windows 11。' 'WINDOWS_TOO_OLD'))
    } elseif ($win.isWin10) {
        $checks.Add((New-OmCheck 'windows' '操作系统' 'warn' ($win.caption + ' build ' + $win.build + '，Windows 10 已停止支持。') '仍可尝试，建议升级 Windows 11。' 'WINDOWS_10_EOL'))
    } else {
        $checks.Add((New-OmCheck 'windows' '操作系统' 'ok' ($win.caption + ' build ' + $win.build)))
    }

    if (-not $win.isAdmin) {
        $checks.Add((New-OmCheck 'admin' '管理员权限' 'fail' '当前不是管理员。' '安装运行环境需要管理员账户；请让管理员运行一次。' 'ADMIN_REQUIRED'))
    } else {
        $checks.Add((New-OmCheck 'admin' '管理员权限' 'ok' '当前用户是管理员。'))
    }

    $virt = Test-OmVirtualization
    if ($null -eq $virt) {
        $checks.Add((New-OmCheck 'virtualization' '硬件虚拟化' 'unknown' '无法确定虚拟化是否开启。' '如果 Docker 启动失败，请进 BIOS 开启 Virtualization。' 'VIRTUALIZATION_UNKNOWN'))
    } elseif (-not $virt) {
        $checks.Add((New-OmCheck 'virtualization' '硬件虚拟化' 'fail' 'BIOS 中未开启虚拟化。' '重启进 BIOS 开启 Virtualization / VT-x / AMD-V。' 'VIRTUALIZATION_DISABLED'))
    } else {
        $checks.Add((New-OmCheck 'virtualization' '硬件虚拟化' 'ok' '已开启。'))
    }

    $disk = Get-OmDiskInfo
    if ($null -eq $disk.freeGB) {
        $checks.Add((New-OmCheck 'disk' '磁盘空间' 'unknown' '无法读取系统盘空间。' '确保系统盘至少 10GB 可用。' 'DISK_UNKNOWN'))
    } elseif ($disk.freeGB -lt 10) {
        $checks.Add((New-OmCheck 'disk' '磁盘空间' 'fail' ('系统盘只剩 ' + $disk.freeGB + 'GB。') '至少清理到 15GB 可用。' 'DISK_LOW'))
    } elseif ($disk.freeGB -lt 15) {
        $checks.Add((New-OmCheck 'disk' '磁盘空间' 'warn' ('系统盘只剩 ' + $disk.freeGB + 'GB。') '建议清理到 15GB 以上。' 'DISK_TIGHT'))
    } else {
        $checks.Add((New-OmCheck 'disk' '磁盘空间' 'ok' ('系统盘可用 ' + $disk.freeGB + 'GB。')))
    }

    $wsl = Get-OmWslInfo
    if (-not $wsl.installed) {
        $checks.Add((New-OmCheck 'wsl' 'WSL2' 'fail' '未找到 wsl.exe。' '点“一键安装依赖”自动启用 WSL2。' 'WSL_MISSING'))
    } elseif (-not $wsl.ready) {
        $checks.Add((New-OmCheck 'wsl' 'WSL2' 'warn' 'WSL2 已安装但尚未就绪，可能需要重启。' '安装依赖后重启电脑。' 'WSL_NOT_READY'))
    } else {
        $checks.Add((New-OmCheck 'wsl' 'WSL2' 'ok' '已就绪。'))
    }

    $docker = Get-OmDockerInfo
    if (-not $docker.installed) {
        $checks.Add((New-OmCheck 'docker' 'Docker Desktop' 'fail' '未安装。' '点“一键安装依赖”自动安装。' 'DOCKER_MISSING'))
    } elseif (-not $docker.running) {
        $checks.Add((New-OmCheck 'docker' 'Docker Desktop' 'fail' '已安装但未运行。' '启动 Docker Desktop，等待图标变绿后重试。' 'DOCKER_NOT_RUNNING'))
    } elseif (-not $docker.compose) {
        $checks.Add((New-OmCheck 'docker' 'Docker Desktop' 'fail' 'Docker Compose v2 不可用。' '更新 Docker Desktop。' 'COMPOSE_MISSING'))
    } else {
        $checks.Add((New-OmCheck 'docker' 'Docker Desktop' 'ok' ('运行中，版本 ' + $docker.version + '。')))
    }

    $ts = Get-OmTailscaleInfo
    if (-not $ts.installed) {
        $checks.Add((New-OmCheck 'tailscale' 'Tailscale' 'fail' '未安装。' '点“一键安装依赖”自动安装。' 'TAILSCALE_MISSING'))
    } elseif (-not $ts.running) {
        $checks.Add((New-OmCheck 'tailscale' 'Tailscale' 'fail' '已安装但未登录/未连接。' '打开 Tailscale 登录并连接。' 'TAILSCALE_NOT_RUNNING'))
    } elseif (-not $ts.loggedIn) {
        $checks.Add((New-OmCheck 'tailscale' 'Tailscale' 'warn' '已连接，但没有 .ts.net 主机名。' '在 Tailscale 管理台开启 MagicDNS 和 HTTPS。' 'MAGICDNS_DISABLED'))
    } else {
        $checks.Add((New-OmCheck 'tailscale' 'Tailscale' 'ok' ('已连接：' + $ts.hostName)))
    }

    if (-not $SkipPorts) {
        if ($null -eq $Ports) { $Ports = (Read-OmState).ports }
        $resolved = Resolve-OmPorts -Ports $Ports -IgnorePorts $IgnorePorts
        if ($resolved.conflicts.Count -eq 0) {
            $checks.Add((New-OmCheck 'ports' '端口' 'ok' ('API ' + $Ports.api + ' / Wallos ' + $Ports.wallos + ' / 网关 ' + $Ports.gateway + ' / 向导 ' + $Ports.setup + ' 可用。')))
        } else {
            $names = ($resolved.conflicts | ForEach-Object {
                $text = $_.name + ':' + $_.port
                if ($_.process) { $text += ' (' + $_.process + ')' }
                $text
            }) -join ', '
            $checks.Add((New-OmCheck 'ports' '端口' 'fail' ('被占用：' + $names) '关闭占用程序，或让向导自动改用其他端口。' 'PORT_CONFLICT'))
        }
    }

    if ($RepoRoot) {
        $backend = Join-Path $RepoRoot 'secrets\backend.env'
        if (Test-Path $backend) {
            $checks.Add((New-OmCheck 'existing' '已有安装' 'warn' '检测到已有 secrets/backend.env。' '向导会进入修复/迁移，不会覆盖现有数据。' 'EXISTING_INSTALL'))
        }
    }

    $net = Get-OmNetworkChecks -SkipNetwork:$SkipNetwork
    if (-not $net.skipped) {
        $bad = @()
        if (-not $net.dockerRegistry.ok) { $bad += 'Docker Registry' }
        if (-not $net.tailscaleControl.ok) { $bad += 'Tailscale' }
        if (-not $net.github.ok) { $bad += 'GitHub' }
        if ($bad.Count -eq 0) {
            $checks.Add((New-OmCheck 'network' '网络' 'ok' 'Docker、Tailscale、GitHub 均可访问。'))
        } else {
            $checks.Add((New-OmCheck 'network' '网络' 'warn' ('无法访问：' + ($bad -join ', ')) '可使用离线镜像包，或配置代理后重试。' 'NETWORK_PARTIAL'))
        }
    } else {
        $checks.Add((New-OmCheck 'network' '网络' 'unknown' '已跳过网络检查。' '' 'NETWORK_SKIPPED'))
    }

    return $checks.ToArray()
}

function Get-OmCheckSummary {
    param($Checks)
    $items = @($Checks)
    $fail = @($items | Where-Object { $_.status -eq 'fail' }).Count
    $warn = @($items | Where-Object { $_.status -eq 'warn' }).Count
    $unknown = @($items | Where-Object { $_.status -eq 'unknown' }).Count
    return [pscustomobject]@{
        total = $items.Count; fail = $fail; warn = $warn; unknown = $unknown;
        ok = ($fail -eq 0)
    }
}
