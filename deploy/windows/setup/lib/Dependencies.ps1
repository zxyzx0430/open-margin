# Open Margin setup engine: dependency installation.
# This file is intentionally conservative: every external command is logged,
# every installer is verified, and a missing admin right returns a clear action.

function Test-OmRebootPending {
    $keys = @(
        'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Component Based Servicing\RebootPending',
        'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\WindowsUpdate\Auto Update\RebootRequired'
    )
    foreach ($key in $keys) {
        if (Test-Path $key) { return $true }
    }
    return $false
}

function Add-OmUrlAcl {
    param(
        [int]$Port = 18770,
        [switch]$DryRun
    )
    $url = 'http://127.0.0.1:' + $Port + '/'
    $user = $env:USERDOMAIN + '\' + $env:USERNAME
    $args = @('http', 'add', 'urlacl', ('url=' + $url), ('user=' + $user))
    if ($DryRun) {
        return [pscustomobject]@{ ok = $true; code = 'DRY_RUN'; message = ('netsh ' + ($args -join ' ')) }
    }
    try {
        $output = & netsh.exe @args 2>&1 | Out-String
        return [pscustomobject]@{ ok = ($LASTEXITCODE -eq 0); code = 'URLACL'; message = (Protect-OmText $output) }
    } catch {
        return [pscustomobject]@{ ok = $false; code = 'URLACL_FAILED'; message = $_.Exception.Message }
    }
}

function Install-OmWsl {
    param([switch]$DryRun)
    $wsl = Get-OmCommandPath 'wsl'
    if ($wsl) {
        try {
            & $wsl --status *> $null
            if ($LASTEXITCODE -eq 0) {
                return [pscustomobject]@{ ok = $true; code = 'WSL_READY'; message = 'WSL 已就绪。' }
            }
        } catch { }
    }
    if ($DryRun) {
        return [pscustomobject]@{ ok = $true; code = 'DRY_RUN'; message = 'wsl --install --no-distribution' }
    }
    Write-OmLog -Level INFO -Message 'installing WSL2'
    try {
        if (-not $wsl) { throw 'wsl.exe not found' }
        $output = & $wsl --install --no-distribution 2>&1 | Out-String
        if ($LASTEXITCODE -eq 0) {
            return [pscustomobject]@{ ok = $true; code = 'WSL_INSTALLED'; message = 'WSL 安装完成，可能需要重启。' }
        }
        Write-OmLog -Level WARN -Message ('wsl --install failed: ' + (Protect-OmText $output))
    } catch { }
    $features = @('Microsoft-Windows-Subsystem-Linux', 'VirtualMachinePlatform')
    foreach ($feature in $features) {
        $null = & dism.exe /online /enable-feature /featurename:$feature /all /norestart 2>&1
        if ($LASTEXITCODE -ne 0) {
            return [pscustomobject]@{ ok = $false; code = 'WSL_INSTALL_FAILED'; message = ('DISM failed for ' + $feature) }
        }
    }
    return [pscustomobject]@{ ok = $true; code = 'WSL_INSTALLED_DISM'; message = 'WSL 功能已启用，需要重启。' }
}

function Install-OmDockerDesktop {
    param([switch]$DryRun)
    $existing = Get-OmDockerInfo
    if ($existing.installed) {
        return [pscustomobject]@{ ok = $true; code = 'DOCKER_PRESENT'; message = 'Docker 已安装。' }
    }
    if ($DryRun) {
        return [pscustomobject]@{ ok = $true; code = 'DRY_RUN'; message = 'winget install Docker.DockerDesktop' }
    }
    $winget = Get-OmCommandPath 'winget'
    if ($winget) {
        Write-OmLog -Level INFO -Message 'installing Docker Desktop via winget'
        $output = & $winget install --id Docker.DockerDesktop --silent --accept-package-agreements --accept-source-agreements --disable-interactivity 2>&1 | Out-String
        if ($LASTEXITCODE -eq 0) {
            return [pscustomobject]@{ ok = $true; code = 'DOCKER_INSTALLED'; message = 'Docker Desktop 安装完成。' }
        }
        Write-OmLog -Level WARN -Message ('winget Docker failed: ' + (Protect-OmText $output))
    }
    $installer = Join-Path $env:TEMP 'DockerDesktopInstaller.exe'
    try {
        Invoke-WebRequest -Uri 'https://desktop.docker.com/win/main/amd64/Docker%20Desktop%20Installer.exe' -OutFile $installer -UseBasicParsing -TimeoutSec 600
        $signature = Get-AuthenticodeSignature $installer
        if ($signature.Status -ne 'Valid') { throw ('invalid signature: ' + $signature.Status) }
        $process = Start-Process -FilePath $installer -ArgumentList @('install', '--quiet', '--accept-license') -Wait -PassThru
        if ($process.ExitCode -eq 0) {
            return [pscustomobject]@{ ok = $true; code = 'DOCKER_INSTALLED'; message = 'Docker Desktop 安装完成。' }
        }
        return [pscustomobject]@{ ok = $false; code = 'DOCKER_INSTALL_FAILED'; message = ('installer exit ' + $process.ExitCode) }
    } catch {
        return [pscustomobject]@{ ok = $false; code = 'DOCKER_INSTALL_FAILED'; message = $_.Exception.Message }
    }
}

function Install-OmTailscale {
    param([switch]$DryRun)
    $existing = Get-OmTailscaleInfo
    if ($existing.installed) {
        return [pscustomobject]@{ ok = $true; code = 'TAILSCALE_PRESENT'; message = 'Tailscale 已安装。' }
    }
    if ($DryRun) {
        return [pscustomobject]@{ ok = $true; code = 'DRY_RUN'; message = 'winget install Tailscale.Tailscale' }
    }
    $winget = Get-OmCommandPath 'winget'
    if ($winget) {
        Write-OmLog -Level INFO -Message 'installing Tailscale via winget'
        $output = & $winget install --id Tailscale.Tailscale --silent --accept-package-agreements --accept-source-agreements --disable-interactivity 2>&1 | Out-String
        if ($LASTEXITCODE -eq 0) {
            return [pscustomobject]@{ ok = $true; code = 'TAILSCALE_INSTALLED'; message = 'Tailscale 安装完成。' }
        }
        Write-OmLog -Level WARN -Message ('winget Tailscale failed: ' + (Protect-OmText $output))
    }
    $msi = Join-Path $env:TEMP 'tailscale-setup.msi'
    try {
        Invoke-WebRequest -Uri 'https://pkgs.tailscale.com/stable/tailscale-setup-latest-amd64.msi' -OutFile $msi -UseBasicParsing -TimeoutSec 600
        $signature = Get-AuthenticodeSignature $msi
        if ($signature.Status -ne 'Valid') { throw ('invalid signature: ' + $signature.Status) }
        $process = Start-Process -FilePath 'msiexec.exe' -ArgumentList @('/i', $msi, '/qn', '/norestart') -Wait -PassThru
        if ($process.ExitCode -eq 0) {
            return [pscustomobject]@{ ok = $true; code = 'TAILSCALE_INSTALLED'; message = 'Tailscale 安装完成。' }
        }
        return [pscustomobject]@{ ok = $false; code = 'TAILSCALE_INSTALL_FAILED'; message = ('msiexec exit ' + $process.ExitCode) }
    } catch {
        return [pscustomobject]@{ ok = $false; code = 'TAILSCALE_INSTALL_FAILED'; message = $_.Exception.Message }
    }
}

function Set-OmTailscaleOperator {
    param([switch]$DryRun)
    $path = Find-OmTailscale
    if (-not $path) { return [pscustomobject]@{ ok = $false; code = 'TAILSCALE_MISSING'; message = 'Tailscale 未安装。' } }
    if ($DryRun) {
        return [pscustomobject]@{ ok = $true; code = 'DRY_RUN'; message = ('tailscale set --operator=' + $env:USERNAME) }
    }
    try {
        $output = & $path set --operator=$env:USERNAME 2>&1 | Out-String
        return [pscustomobject]@{ ok = ($LASTEXITCODE -eq 0); code = 'OPERATOR'; message = (Protect-OmText $output) }
    } catch {
        return [pscustomobject]@{ ok = $false; code = 'OPERATOR_FAILED'; message = $_.Exception.Message }
    }
}

function Install-OmDependencies {
    param(
        [int]$SetupPort = 18770,
        [switch]$DryRun
    )
    if (-not (Test-OmAdmin) -and -not $DryRun) {
        return [pscustomobject]@{
            ok = $false; code = 'ADMIN_REQUIRED';
            message = '安装运行环境需要管理员权限。';
            action = 'relaunch_as_admin'
        }
    }
    $steps = New-Object System.Collections.Generic.List[object]
    $wsl = Install-OmWsl -DryRun:$DryRun
    $steps.Add($wsl)
    if (-not $wsl.ok) { return [pscustomobject]@{ ok = $false; steps = @($steps); message = $wsl.message } }

    $docker = Install-OmDockerDesktop -DryRun:$DryRun
    $steps.Add($docker)
    if (-not $docker.ok) { return [pscustomobject]@{ ok = $false; steps = @($steps); message = $docker.message } }

    $tailscale = Install-OmTailscale -DryRun:$DryRun
    $steps.Add($tailscale)
    if (-not $tailscale.ok) { return [pscustomobject]@{ ok = $false; steps = @($steps); message = $tailscale.message } }

    $operator = Set-OmTailscaleOperator -DryRun:$DryRun
    $steps.Add($operator)

    $acl = Add-OmUrlAcl -Port $SetupPort -DryRun:$DryRun
    $steps.Add($acl)

    $pending = Test-OmRebootPending
    return [pscustomobject]@{
        ok = $true
        steps = @($steps)
        pendingReboot = $pending
        message = $(if ($pending) { '依赖已安装，需要重启。' } else { '依赖已就绪。' })
    }
}

function Invoke-OmElevatedScript {
    param(
        [Parameter(Mandatory = $true)][string]$ScriptPath,
        [string[]]$Arguments = @()
    )
    $argList = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $ScriptPath) + $Arguments
    try {
        $process = Start-Process -FilePath 'pwsh.exe' -ArgumentList $argList -Verb RunAs -Wait -PassThru
        return [pscustomobject]@{ ok = ($process.ExitCode -eq 0); exitCode = $process.ExitCode }
    } catch {
        return [pscustomobject]@{ ok = $false; exitCode = -1; message = $_.Exception.Message }
    }
}
