#Requires -Version 5.1
<#
.SYNOPSIS
    Detect, back up and safely stop a previous Open Margin / 清衡 install.

.DESCRIPTION
    Read-only by default. When you install Open Margin to a new directory while
    an older copy still exists (for example D:\OpenMargin\app -> the default
    C:\ProgramData\OpenMargin\app), two app roots, two secrets directories and
    an old watchdog can coexist. This helper reports the situation and can:

      -Backup       copy secrets/.env/compose.yaml to a timestamped backup folder
      -StopOld      stop the old compose project and end the old watchdog task
      -CopySecrets  copy old secrets/ into the new app root (skips existing files)

    Every action requires -Yes. The script never deletes files or Docker
    volumes, never runs the compose down command, and never uninstalls anything.

.PARAMETER OldAppRoot
    Previous install root. Auto-detected from the Inno Setup uninstall entry and
    common locations when omitted.

.PARAMETER NewAppRoot
    New install root. Defaults to C:\ProgramData\OpenMargin\app.

.PARAMETER BackupRoot
    Parent folder for timestamped backups. Defaults to
    %LOCALAPPDATA%\OpenMargin\migration-backups.

.PARAMETER Backup
    Copy secrets, .env and compose.yaml to a new timestamped backup folder.

.PARAMETER StopOld
    Stop the personal-finance-system compose project from the old app root and
    end the OpenMarginWatchdog scheduled task. Containers, images and volumes
    are kept.

.PARAMETER CopySecrets
    Copy files from <OldAppRoot>\secrets to <NewAppRoot>\secrets without
    overwriting existing files. Install the new version first.

.PARAMETER Yes
    Required for -Backup, -StopOld and -CopySecrets.

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File .\Migrate-OpenMargin.ps1

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File .\Migrate-OpenMargin.ps1 -Backup -StopOld -Yes
#>
[CmdletBinding()]
param(
    [string]$OldAppRoot = '',
    [string]$NewAppRoot = '',
    [string]$BackupRoot = '',
    [switch]$Backup,
    [switch]$StopOld,
    [switch]$CopySecrets,
    [switch]$Yes
)

$ErrorActionPreference = 'Stop'
$AppId = '{9D2D6B5A-7C4E-4F9A-9B3D-2E6F8A1C0D77}'
$ProjectName = 'personal-finance-system'
$WatchdogTask = 'OpenMarginWatchdog'
$defaultProgramData = $env:ProgramData
if (-not $defaultProgramData) { $defaultProgramData = 'C:\ProgramData' }

if (-not $NewAppRoot) {
    $NewAppRoot = Join-Path (Join-Path $defaultProgramData 'OpenMargin') 'app'
}
if (-not $BackupRoot) {
    $localAppData = $env:LOCALAPPDATA
    if (-not $localAppData) { $localAppData = $env:TEMP }
    $BackupRoot = Join-Path (Join-Path $localAppData 'OpenMargin') 'migration-backups'
}

function Write-Section {
    param([string]$Text)
    Write-Host ''
    Write-Host ('== ' + $Text) -ForegroundColor Cyan
}

function Write-InfoLine {
    param([string]$Text)
    Write-Host ('   ' + $Text)
}

function Write-WarnLine {
    param([string]$Text)
    Write-Host ('   [警告] ' + $Text) -ForegroundColor Yellow
}

function Normalize-Dir {
    param([string]$Path)
    if (-not $Path) { return '' }
    try { $full = [IO.Path]::GetFullPath($Path) } catch { return $Path.Trim() }
    return $full.TrimEnd('\')
}

function Get-DetectedAppRoots {
    $results = New-Object System.Collections.Generic.List[object]
    $seen = @{}

    function Add-Candidate {
        param([string]$Path, [string]$Source)
        if (-not $Path) { return }
        $normalized = Normalize-Dir $Path
        if (-not $normalized) { return }
        $key = $normalized.ToLowerInvariant()
        if ($seen.ContainsKey($key)) { return }
        $seen[$key] = $true
        $results.Add([pscustomobject]@{
            Path = $normalized
            Source = $Source
            Exists = (Test-Path -LiteralPath $normalized)
            HasCompose = (Test-Path -LiteralPath (Join-Path $normalized 'compose.yaml'))
            HasSecrets = (Test-Path -LiteralPath (Join-Path $normalized 'secrets'))
        })
    }

    $keyPaths = @(
        ('Registry::HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\' + $AppId + '_is1'),
        ('Registry::HKEY_LOCAL_MACHINE\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\' + $AppId + '_is1'),
        ('Registry::HKEY_CURRENT_USER\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\' + $AppId + '_is1')
    )
    foreach ($key in $keyPaths) {
        $props = $null
        try { $props = Get-ItemProperty -LiteralPath $key -ErrorAction Stop } catch { continue }
        if ($props.InstallLocation) { Add-Candidate $props.InstallLocation 'Inno Setup uninstall entry' }
        if ($props.'Inno Setup: App Path') { Add-Candidate $props.'Inno Setup: App Path' 'Inno Setup uninstall entry' }
    }

    # Also scan DisplayName in case a future build changes AppId.
    $uninstallRoots = @(
        'Registry::HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall',
        'Registry::HKEY_LOCAL_MACHINE\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall',
        'Registry::HKEY_CURRENT_USER\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall'
    )
    foreach ($root in $uninstallRoots) {
        $children = @()
        try { $children = @(Get-ChildItem -LiteralPath $root -ErrorAction Stop) } catch { continue }
        foreach ($child in $children) {
            $props = $null
            try { $props = Get-ItemProperty -LiteralPath $child.PSPath -ErrorAction Stop } catch { continue }
            if ($props.DisplayName -and ($props.DisplayName -match 'Open Margin|清衡')) {
                if ($props.InstallLocation) { Add-Candidate $props.InstallLocation 'Uninstall entry (DisplayName)' }
                if ($props.'Inno Setup: App Path') { Add-Candidate $props.'Inno Setup: App Path' 'Uninstall entry (DisplayName)' }
            }
        }
    }

    Add-Candidate (Join-Path (Join-Path $defaultProgramData 'OpenMargin') 'app') 'Default install path'
    foreach ($drive in @('D:', 'E:')) {
        if (Test-Path -LiteralPath ($drive + '\')) {
            Add-Candidate (Join-Path $drive '\OpenMargin\app') 'Common custom path'
        }
    }

    return $results.ToArray()
}

function Get-DockerPath {
    $command = Get-Command docker.exe -ErrorAction SilentlyContinue
    if (-not $command) { $command = Get-Command docker -ErrorAction SilentlyContinue }
    if ($command) { return $command.Source }
    return $null
}

function Get-DockerLines {
    param(
        [string]$DockerPath,
        [string[]]$Arguments
    )
    if (-not $DockerPath) { return @() }
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & $DockerPath @Arguments 2>&1
        if ($LASTEXITCODE -ne 0) { return @() }
        return @($output | Where-Object { $_ -ne $null -and ([string]$_).Trim() -ne '' })
    } catch {
        return @()
    } finally {
        $ErrorActionPreference = $previous
    }
}

function Invoke-Schtasks {
    param([string[]]$Arguments)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & schtasks.exe @Arguments 2>&1
        return [pscustomobject]@{
            ExitCode = $LASTEXITCODE
            Output = @($output)
        }
    } catch {
        return [pscustomobject]@{
            ExitCode = 1
            Output = @([string]$_.Exception.Message)
        }
    } finally {
        $ErrorActionPreference = $previous
    }
}

function Show-MigrationReport {
    param(
        [object[]]$Roots,
        [string]$SelectedOld,
        [string]$DockerPath
    )

    Write-Section '检测结果'
    Write-InfoLine ('新安装目录：' + $NewAppRoot)
    if (-not $SelectedOld) {
        Write-InfoLine '没有检测到旧的 Open Margin 安装目录。'
    } else {
        Write-InfoLine ('旧安装目录：' + $SelectedOld)
        Write-InfoLine ('  包含 compose.yaml：' + (Test-Path -LiteralPath (Join-Path $SelectedOld 'compose.yaml')))
        Write-InfoLine ('  包含 secrets：' + (Test-Path -LiteralPath (Join-Path $SelectedOld 'secrets')))
        $watchdog = Invoke-Schtasks -Arguments @('/Query', '/TN', $WatchdogTask)
        Write-InfoLine ('  看门狗计划任务存在：' + ($watchdog.ExitCode -eq 0))
    }

    $existingRoots = @($Roots | Where-Object { $_.Exists })
    if ($existingRoots.Count -gt 1) {
        Write-WarnLine '检测到多个候选安装目录：'
        foreach ($root in $existingRoots) {
            Write-InfoLine ('  - ' + $root.Path + ' (' + $root.Source + ')')
        }
    }

    if (-not $DockerPath) {
        Write-WarnLine '未找到 docker 命令。无法查询旧容器；请先安装/启动 Docker Desktop。'
        return
    }

    $containers = Get-DockerLines -DockerPath $DockerPath -Arguments @(
        'ps', '-a',
        '--filter', ('label=com.docker.compose.project=' + $ProjectName),
        '--format', '{{.Names}}  {{.Status}}'
    )
    $volumes = Get-DockerLines -DockerPath $DockerPath -Arguments @(
        'volume', 'ls',
        '--filter', ('label=com.docker.compose.project=' + $ProjectName),
        '--format', '{{.Name}}'
    )

    Write-Section 'Docker 状态'
    if ($containers.Count -eq 0) {
        Write-InfoLine ('没有找到 project=' + $ProjectName + ' 的容器（Docker 可能未运行，或旧安装从未部署）。')
    } else {
        Write-InfoLine ('project=' + $ProjectName + ' 的容器：')
        foreach ($line in $containers) { Write-InfoLine ('  - ' + $line) }
        Write-WarnLine '停止这些容器会影响手机同步；请先确认新安装已就绪。'
    }
    if ($volumes.Count -eq 0) {
        Write-InfoLine ('没有找到 project=' + $ProjectName + ' 的命名卷（或 Docker 未运行）。')
    } else {
        Write-InfoLine ('project=' + $ProjectName + ' 的命名卷（本脚本不会删除）：')
        foreach ($line in $volumes) { Write-InfoLine ('  - ' + $line) }
        Write-WarnLine '账本数据通常在这些卷中；不要执行 docker compose down -v，也不要删除卷。'
    }
}

function New-MigrationBackup {
    param([string]$SourceRoot, [string]$DockerPath)

    if (-not (Test-Path -LiteralPath $SourceRoot)) {
        throw ('旧安装目录不存在，无法备份：' + $SourceRoot)
    }
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $dest = Join-Path $BackupRoot $stamp
    if (Test-Path -LiteralPath $dest) {
        throw ('备份目录已存在，请稍后重试：' + $dest)
    }
    New-Item -ItemType Directory -Force -Path $dest | Out-Null

    Write-Section '备份旧安装的配置与 secrets'
    $copied = New-Object System.Collections.Generic.List[string]
    $secrets = Join-Path $SourceRoot 'secrets'
    if (Test-Path -LiteralPath $secrets) {
        $secretsDest = Join-Path $dest 'secrets'
        Copy-Item -LiteralPath $secrets -Destination $secretsDest -Recurse -Force
        $copied.Add('secrets\')
        Write-InfoLine ('已复制 secrets -> ' + $secretsDest)
    } else {
        Write-WarnLine '旧目录没有 secrets，跳过。'
    }

    foreach ($name in @('.env', 'compose.yaml')) {
        $source = Join-Path $SourceRoot $name
        if (Test-Path -LiteralPath $source) {
            Copy-Item -LiteralPath $source -Destination (Join-Path $dest $name) -Force
            $copied.Add($name)
            Write-InfoLine ('已复制 ' + $name)
        }
    }

    $containers = Get-DockerLines -DockerPath $DockerPath -Arguments @(
        'ps', '-a',
        '--filter', ('label=com.docker.compose.project=' + $ProjectName),
        '--format', '{{.Names}}  {{.Status}}'
    )
    $volumes = Get-DockerLines -DockerPath $DockerPath -Arguments @(
        'volume', 'ls',
        '--filter', ('label=com.docker.compose.project=' + $ProjectName),
        '--format', '{{.Name}}'
    )

    $fileHashes = New-Object System.Collections.Generic.List[object]
    foreach ($file in @(Get-ChildItem -LiteralPath $dest -Recurse -File -Force -ErrorAction SilentlyContinue)) {
        $relative = $file.FullName.Substring($dest.Length).TrimStart('\')
        $hash = ''
        try { $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant() } catch { }
        $fileHashes.Add([pscustomobject]@{ Path = $relative; SHA256 = $hash; Bytes = $file.Length })
    }

    $manifest = [pscustomobject]@{
        schema = 1
        createdAt = (Get-Date).ToString('o')
        computer = $env:COMPUTERNAME
        user = $env:USERNAME
        oldAppRoot = $SourceRoot
        newAppRoot = $NewAppRoot
        copied = $copied.ToArray()
        files = $fileHashes.ToArray()
        containers = @($containers)
        volumes = @($volumes)
        notes = 'Secrets are stored only in this backup folder. Docker volumes are not included. Never run the compose down command with -v.'
    }
    $manifestPath = Join-Path $dest 'MIGRATION.json'
    $manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $manifestPath -Encoding UTF8

    $restore = @(
        'Open Margin migration backup',
        ('Created: ' + $manifest.createdAt),
        ('Old app root: ' + $SourceRoot),
        ('New app root: ' + $NewAppRoot),
        '',
        'This folder contains a copy of the old secrets/ directory and, when present, .env and compose.yaml.',
        'Docker named volumes are NOT included in this backup.',
        '',
        'To restore the old secrets to the new install, copy the files from secrets\\ into',
        ('  ' + (Join-Path $NewAppRoot 'secrets')),
        'after the new version is installed. Do not overwrite newer files unless you intend to.',
        '',
        'Never run the compose down command with -v and never delete Docker volumes.'
    )
    Set-Content -LiteralPath (Join-Path $dest 'RESTORE.txt') -Value $restore -Encoding UTF8
    Write-InfoLine ('备份完成：' + $dest)
    return $dest
}

function Stop-OldInstall {
    param([string]$SourceRoot, [string]$DockerPath)

    if (-not (Test-Path -LiteralPath $SourceRoot)) {
        throw ('旧安装目录不存在：' + $SourceRoot)
    }
    $compose = Join-Path $SourceRoot 'compose.yaml'
    if (-not (Test-Path -LiteralPath $compose)) {
        throw ('旧目录没有 compose.yaml，无法安全停止容器：' + $compose)
    }
    if (-not $DockerPath) {
        throw '未找到 docker 命令。请先安装/启动 Docker Desktop。'
    }

    Write-Section '停止旧 compose 项目（保留容器、镜像和数据卷）'
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $DockerPath compose --project-directory $SourceRoot --project-name $ProjectName -f $compose stop 2>&1 | Out-Null
        $stopExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($stopExitCode -ne 0) {
        Write-WarnLine ('docker compose stop 返回退出码 ' + $stopExitCode + '；请手动检查 docker compose ps。')
    } else {
        Write-InfoLine '旧容器已停止；数据卷和 secrets 均未删除。'
    }

    $watchdog = Invoke-Schtasks -Arguments @('/Query', '/TN', $WatchdogTask)
    if ($watchdog.ExitCode -eq 0) {
        $end = Invoke-Schtasks -Arguments @('/End', '/TN', $WatchdogTask)
        if ($end.ExitCode -eq 0) {
            Write-InfoLine '已结束旧的看门狗任务（计划任务保留；新安装会重新注册）。'
        } else {
            Write-WarnLine '无法结束看门狗任务，请在任务计划程序中手动结束。'
        }
    } else {
        Write-InfoLine '没有找到看门狗计划任务，跳过。'
    }
}

function Copy-OldSecrets {
    param([string]$SourceRoot)

    if (-not (Test-Path -LiteralPath $SourceRoot)) {
        throw ('旧安装目录不存在：' + $SourceRoot)
    }
    if (-not (Test-Path -LiteralPath $NewAppRoot)) {
        throw ('新安装目录不存在，请先安装新版本：' + $NewAppRoot)
    }
    $sourceSecrets = Join-Path $SourceRoot 'secrets'
    if (-not (Test-Path -LiteralPath $sourceSecrets)) {
        throw ('旧目录没有 secrets：' + $sourceSecrets)
    }

    Write-Section '把旧 secrets 复制到新安装目录（不覆盖已有文件）'
    $destination = Join-Path $NewAppRoot 'secrets'
    if (-not (Test-Path -LiteralPath $destination)) {
        New-Item -ItemType Directory -Force -Path $destination | Out-Null
    }

    $copiedCount = 0
    $skippedCount = 0
    $sourceFiles = @(Get-ChildItem -LiteralPath $sourceSecrets -Recurse -File -Force -ErrorAction SilentlyContinue)
    foreach ($file in $sourceFiles) {
        $relative = $file.FullName.Substring($sourceSecrets.Length).TrimStart('\')
        $target = Join-Path $destination $relative
        $targetParent = Split-Path -Parent $target
        if ($targetParent -and -not (Test-Path -LiteralPath $targetParent)) {
            New-Item -ItemType Directory -Force -Path $targetParent | Out-Null
        }
        if (Test-Path -LiteralPath $target) {
            $skippedCount++
            Write-WarnLine ('已存在，跳过：' + $relative)
            continue
        }
        Copy-Item -LiteralPath $file.FullName -Destination $target -Force
        $copiedCount++
    }
    Write-InfoLine ('复制完成：' + $copiedCount + ' 个文件，跳过 ' + $skippedCount + ' 个已存在文件。')
    Write-WarnLine '复制后请重新打开“清衡设置”检查状态；旧二维码可能仍然有效，确认无误后再决定是否轮换设备密钥。'
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

Write-Host 'Open Margin / 清衡 安装目录迁移助手' -ForegroundColor Cyan
Write-Host '只做检测、备份和停止；不会删除文件或 Docker 数据卷。' -ForegroundColor DarkGray

$detected = @(Get-DetectedAppRoots)
$selectedOld = ''
if ($OldAppRoot) {
    $selectedOld = Normalize-Dir $OldAppRoot
} else {
    $existing = @($detected | Where-Object { $_.Exists -and $_.HasCompose })
    if ($existing.Count -eq 0) {
        $existing = @($detected | Where-Object { $_.Exists })
    }
    if ($existing.Count -gt 0) {
        $selectedOld = $existing[0].Path
    }
}

$dockerPath = Get-DockerPath
Show-MigrationReport -Roots $detected -SelectedOld $selectedOld -DockerPath $dockerPath

if (-not $selectedOld) {
    Write-Section '结论'
    Write-InfoLine '没有发现旧安装，无需迁移。'
    exit 0
}
if (-not (Test-Path -LiteralPath $selectedOld)) {
    Write-Section '结论'
    Write-WarnLine ('指定的旧安装目录不存在：' + $selectedOld)
    exit 2
}
if ((Normalize-Dir $selectedOld).ToLowerInvariant() -eq (Normalize-Dir $NewAppRoot).ToLowerInvariant()) {
    Write-Section '结论'
    Write-InfoLine '旧安装目录与新安装目录相同，这是升级/修复场景，无需迁移。'
    exit 0
}

$actions = @()
if ($Backup) { $actions += 'Backup' }
if ($StopOld) { $actions += 'StopOld' }
if ($CopySecrets) { $actions += 'CopySecrets' }

if ($actions.Count -eq 0) {
    Write-Section '下一步'
    Write-InfoLine '当前是只读检测。要执行迁移，请按需加参数并加 -Yes：'
    Write-InfoLine '  -Backup -Yes                 备份 secrets/.env/compose.yaml'
    Write-InfoLine '  -StopOld -Yes                停止旧 compose 项目并结束旧看门狗'
    Write-InfoLine '  -CopySecrets -Yes            把旧 secrets 复制到新安装目录'
    Write-InfoLine '示例：'
    Write-InfoLine ('  powershell -NoProfile -ExecutionPolicy Bypass -File "' + $PSCommandPath + '" -Backup -StopOld -Yes')
    exit 0
}

if (-not $Yes) {
    Write-Section '需要确认'
    Write-WarnLine ('将要执行：' + ($actions -join ', '))
    Write-WarnLine '这些操作会读取/复制 secrets 或停止旧容器。确认后请在命令末尾加 -Yes。'
    exit 2
}

if ($Backup) {
    $null = New-MigrationBackup -SourceRoot $selectedOld -DockerPath $dockerPath
}
if ($StopOld) {
    Stop-OldInstall -SourceRoot $selectedOld -DockerPath $dockerPath
}
if ($CopySecrets) {
    Copy-OldSecrets -SourceRoot $selectedOld
}

Write-Section '完成'
Write-InfoLine '旧目录和旧 Docker 数据卷均未删除。'
Write-InfoLine '请从开始菜单打开“清衡设置”验证新安装；确认无误后再决定是否保留旧目录。'
Write-InfoLine '不要执行带 -v 的 compose 停止/删除命令，也不要删除 Docker 命名卷。'
exit 0
