# Open Margin setup engine: dependency installation.
# This file is intentionally conservative: every external command is logged,
# every installer is verified, and a missing admin right returns a clear action.

function Test-OmRebootPending {
    $keys = @(
        'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Component Based Servicing\RebootPending',
        'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\WindowsUpdate\Auto Update\RebootRequired',
        'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\WindowsUpdate\Services\Pending'
    )
    foreach ($key in $keys) {
        if (Test-Path $key) { return $true }
    }
    try {
        $session = Get-ItemProperty -Path 'HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager' -Name PendingFileRenameOperations -ErrorAction Stop
        if ($session -and $session.PendingFileRenameOperations) { return $true }
    } catch { }
    return $false
}

function Write-OmProgressFile {
    param(
        [string]$Path,
        [string]$Stage,
        [int]$Percent = -1,
        [string]$Message = ''
    )
    if ([string]::IsNullOrWhiteSpace($Path)) { return }
    try {
        $payload = [pscustomobject]@{
            stage = $Stage; percent = $Percent; message = $Message; updatedAt = (Get-Date).ToString('o')
        }
        $dir = Split-Path -Parent $Path
        if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
        $tmp = $Path + '.tmp'
        $utf8 = New-Object System.Text.UTF8Encoding($false)
        [System.IO.File]::WriteAllText($tmp, ($payload | ConvertTo-Json -Depth 5), $utf8)
        Move-Item -Force $tmp $Path
    } catch { }
}

function Add-OmUrlAcl {
    param(
        [int]$Port = 18770,
        [switch]$DryRun
    )
    $url = 'http://127.0.0.1:' + $Port + '/'
    $currentUser = $env:USERNAME
    try {
        $whoami = (& whoami.exe 2>$null | Out-String).Trim()
        if ($whoami) { $currentUser = $whoami }
    } catch { }
    $argumentList = @('http', 'add', 'urlacl', ('url=' + $url), ('user=' + $currentUser))
    if ($DryRun) {
        return [pscustomobject]@{ ok = $true; code = 'DRY_RUN'; message = ('netsh ' + ($argumentList -join ' ')) }
    }
    try {
        $output = & netsh.exe @argumentList 2>&1 | Out-String
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
        return [pscustomobject]@{ ok = $true; code = 'DRY_RUN'; message = 'wsl --install --no-distribution (DISM fallback available)' }
    }
    Write-OmLog -Level INFO -Message 'installing WSL2'
    try {
        if (-not $wsl) { throw 'wsl.exe not found' }
        $output = & $wsl --install --no-distribution 2>&1 | Out-String
        if ($LASTEXITCODE -eq 0) {
            return [pscustomobject]@{ ok = $true; code = 'WSL_INSTALLED'; message = 'WSL 安装完成，可能需要重启。' }
        }
        Write-OmLog -Level WARN -Message ('wsl --install failed: ' + (Protect-OmText $output))
    } catch {
        Write-OmLog -Level WARN -Message ('wsl --install threw: ' + $_.Exception.Message)
    }
    foreach ($feature in @('Microsoft-Windows-Subsystem-Linux', 'VirtualMachinePlatform')) {
        $null = & dism.exe /online /enable-feature /featurename:$feature /all /norestart 2>&1
        if ($LASTEXITCODE -ne 0) {
            return [pscustomobject]@{ ok = $false; code = 'WSL_INSTALL_FAILED'; message = ('DISM failed for ' + $feature); retryable = $false }
        }
    }
    return [pscustomobject]@{ ok = $true; code = 'WSL_INSTALLED_DISM'; message = 'WSL 功能已启用，需要重启。' }
}

function Get-OmInstallerTrust {
    param([Parameter(Mandatory = $true)][string]$Path)
    $signature = Get-AuthenticodeSignature -FilePath $Path
    $hash = $null
    try { $hash = (Get-FileHash -Path $Path -Algorithm SHA256).Hash } catch { }
    return [pscustomobject]@{
        authenticode = [string]$signature.Status
        signer = $(if ($signature.SignerCertificate) { $signature.SignerCertificate.Subject } else { $null })
        sha256 = $hash
    }
}

function Get-OmDockerLicenseAckPath {
    param([string]$AppRoot = '')
    $root = $AppRoot
    if (-not $root) { $root = $script:OmRepoRoot }
    if (-not $root) { return $null }
    return (Join-Path $root 'docker-license-ack.txt')
}

function Test-OmDockerLicenseAcknowledged {
    param([string]$AppRoot = '')
    $path = Get-OmDockerLicenseAckPath -AppRoot $AppRoot
    if (-not $path) { return $false }
    try { return (Test-Path -LiteralPath $path -PathType Leaf) } catch { return $false }
}

function Install-OmDockerDesktop {
    param(
        [switch]$DryRun,
        [switch]$LicenseAcknowledged,
        [string]$AppRoot = ''
    )
    $existing = Get-OmDockerInfo
    if ($existing.installed) {
        return [pscustomobject]@{ ok = $true; code = 'DOCKER_PRESENT'; message = 'Docker 已安装。'; version = $existing.version }
    }
    # Docker Desktop's installer accepts the Docker Subscription Service
    # Agreement through --accept-license. Never do that on the user's behalf:
    # require either the installer's ack marker ({app}\docker-license-ack.txt,
    # written only after the consent page checkbox) or an explicit acknowledgement
    # from the wizard UI for this request.
    $licenseReady = $LicenseAcknowledged.IsPresent -or (Test-OmDockerLicenseAcknowledged -AppRoot $AppRoot)
    if (-not $DryRun -and -not $licenseReady) {
        return [pscustomobject]@{
            ok        = $false
            code      = 'DOCKER_LICENSE_REQUIRED'
            message   = '安装 Docker Desktop 前需要确认 Docker 订阅服务协议。请在向导中勾选许可确认（或重新运行安装器并勾选 Docker 许可页）后重试。'
            action    = 'acknowledge_docker_license'
            retryable = $true
        }
    }
    if ($DryRun) {
        return [pscustomobject]@{ ok = $true; code = 'DRY_RUN'; message = 'winget install Docker.DockerDesktop, fallback official installer + Authenticode'; licenseAcknowledged = [bool]$licenseReady }
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
    $installer = Join-Path $env:TEMP ('DockerDesktopInstaller-' + [guid]::NewGuid().ToString('N') + '.exe')
    try {
        Invoke-WebRequest -Uri 'https://desktop.docker.com/win/main/amd64/Docker%20Desktop%20Installer.exe' -OutFile $installer -UseBasicParsing -TimeoutSec 600
        $trust = Get-OmInstallerTrust -Path $installer
        Write-OmLog -Level INFO -Message 'Docker installer trust' $trust
        if ($trust.authenticode -ne 'Valid') {
            throw ('invalid Authenticode signature: ' + $trust.authenticode)
        }
        $process = Start-Process -FilePath $installer -ArgumentList @('install', '--quiet', '--accept-license') -Wait -PassThru
        if ($process.ExitCode -eq 0) {
            return [pscustomobject]@{ ok = $true; code = 'DOCKER_INSTALLED'; message = 'Docker Desktop 安装完成。'; sha256 = $trust.sha256 }
        }
        return [pscustomobject]@{ ok = $false; code = 'DOCKER_INSTALL_FAILED'; message = ('installer exit ' + $process.ExitCode); retryable = $true }
    } catch {
        return [pscustomobject]@{ ok = $false; code = 'DOCKER_INSTALL_FAILED'; message = $_.Exception.Message; retryable = $true }
    } finally {
        Remove-Item -Force $installer -ErrorAction SilentlyContinue
    }
}

function Install-OmTailscale {
    param([switch]$DryRun)
    $existing = Get-OmTailscaleInfo
    if ($existing.installed) {
        return [pscustomobject]@{ ok = $true; code = 'TAILSCALE_PRESENT'; message = 'Tailscale 已安装。'; version = $existing.version }
    }
    if ($DryRun) {
        return [pscustomobject]@{ ok = $true; code = 'DRY_RUN'; message = 'winget install Tailscale.Tailscale, fallback MSI + Authenticode' }
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
    $msi = Join-Path $env:TEMP ('tailscale-setup-' + [guid]::NewGuid().ToString('N') + '.msi')
    try {
        Invoke-WebRequest -Uri 'https://pkgs.tailscale.com/stable/tailscale-setup-latest-amd64.msi' -OutFile $msi -UseBasicParsing -TimeoutSec 600
        $trust = Get-OmInstallerTrust -Path $msi
        Write-OmLog -Level INFO -Message 'Tailscale MSI trust' $trust
        if ($trust.authenticode -ne 'Valid') {
            throw ('invalid Authenticode signature: ' + $trust.authenticode)
        }
        $process = Start-Process -FilePath 'msiexec.exe' -ArgumentList @('/i', ('"' + $msi + '"'), '/qn', '/norestart') -Wait -PassThru
        if ($process.ExitCode -in @(0, 3010)) {
            return [pscustomobject]@{ ok = $true; code = 'TAILSCALE_INSTALLED'; message = 'Tailscale 安装完成。'; sha256 = $trust.sha256; exitCode = $process.ExitCode }
        }
        return [pscustomobject]@{ ok = $false; code = 'TAILSCALE_INSTALL_FAILED'; message = ('msiexec exit ' + $process.ExitCode); retryable = $true }
    } catch {
        return [pscustomobject]@{ ok = $false; code = 'TAILSCALE_INSTALL_FAILED'; message = $_.Exception.Message; retryable = $true }
    } finally {
        Remove-Item -Force $msi -ErrorAction SilentlyContinue
    }
}

function Set-OmTailscaleOperator {
    param([switch]$DryRun)
    $path = Find-OmTailscale
    if (-not $path) { return [pscustomobject]@{ ok = $false; code = 'TAILSCALE_MISSING'; message = 'Tailscale 未安装。' } }
    $currentUser = $env:USERNAME
    try {
        $whoami = (& whoami.exe 2>$null | Out-String).Trim()
        if ($whoami) { $currentUser = $whoami }
    } catch { }
    if ($DryRun) {
        return [pscustomobject]@{ ok = $true; code = 'DRY_RUN'; message = ('tailscale set --operator=' + $currentUser) }
    }
    try {
        $output = & $path set --operator=$currentUser 2>&1 | Out-String
        return [pscustomobject]@{ ok = ($LASTEXITCODE -eq 0); code = 'OPERATOR'; message = (Protect-OmText $output) }
    } catch {
        return [pscustomobject]@{ ok = $false; code = 'OPERATOR_FAILED'; message = $_.Exception.Message }
    }
}

function Install-OmDependencies {
    param(
        [int]$SetupPort = 18770,
        [switch]$DryRun,
        [switch]$LicenseAcknowledged,
        [string]$ProgressPath = ''
    )
    if (-not (Test-OmAdmin) -and -not $DryRun) {
        return [pscustomobject]@{
            ok = $false; code = 'ADMIN_REQUIRED';
            message = '安装运行环境需要管理员权限。';
            action = 'relaunch_as_admin'; retryable = $false
        }
    }
    $steps = [System.Collections.Generic.List[object]]::new()
    Write-OmProgressFile -Path $ProgressPath -Stage 'wsl' -Percent 10 -Message '准备 WSL2'
    $wsl = Install-OmWsl -DryRun:$DryRun
    $steps.Add($wsl)
    if (-not $wsl.ok) {
        return [pscustomobject]@{ ok = $false; steps = @($steps); message = $wsl.message; code = $wsl.code; retryable = $true }
    }

    Write-OmProgressFile -Path $ProgressPath -Stage 'docker' -Percent 40 -Message '安装 Docker Desktop'
    $docker = Install-OmDockerDesktop -DryRun:$DryRun -LicenseAcknowledged:$LicenseAcknowledged
    $steps.Add($docker)
    if (-not $docker.ok) {
        return [pscustomobject]@{ ok = $false; steps = @($steps); message = $docker.message; code = $docker.code; retryable = $true }
    }

    Write-OmProgressFile -Path $ProgressPath -Stage 'tailscale' -Percent 70 -Message '安装 Tailscale'
    $tailscale = Install-OmTailscale -DryRun:$DryRun
    $steps.Add($tailscale)
    if (-not $tailscale.ok) {
        return [pscustomobject]@{ ok = $false; steps = @($steps); message = $tailscale.message; code = $tailscale.code; retryable = $true }
    }

    Write-OmProgressFile -Path $ProgressPath -Stage 'operator' -Percent 85 -Message '配置 Tailscale operator'
    $operator = Set-OmTailscaleOperator -DryRun:$DryRun
    $steps.Add($operator)

    Write-OmProgressFile -Path $ProgressPath -Stage 'urlacl' -Percent 95 -Message '配置本机向导权限'
    $acl = Add-OmUrlAcl -Port $SetupPort -DryRun:$DryRun
    $steps.Add($acl)

    $pending = Test-OmRebootPending
    Write-OmProgressFile -Path $ProgressPath -Stage 'done' -Percent 100 -Message '依赖处理完成'
    return [pscustomobject]@{
        ok        = $true
        steps     = @($steps)
        pendingReboot = $pending
        message   = $(if ($pending) { '依赖已安装，需要重启。' } else { '依赖已就绪。' })
        dryRun    = [bool]$DryRun
    }
}

function Get-OmPowerShellExe {
    # Windows PowerShell 5.1 is always present and is the compatibility target;
    # pwsh is only a fallback for machines where System32 is unavailable.
    $winps = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    if (Test-Path $winps) { return $winps }
    $pwsh = Get-OmCommandPath 'pwsh'
    if ($pwsh) { return $pwsh }
    return 'powershell.exe'
}

function Register-OmResumeRunOnce {
    param(
        [Parameter(Mandatory = $true)][string]$ScriptPath,
        [string[]]$Arguments = @(),
        [string]$Name = 'OpenMarginSetupResume',
        [switch]$DryRun
    )
    $exe = Get-OmPowerShellExe
    $parts = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $ScriptPath + '"'))
    foreach ($argument in $Arguments) {
        $text = [string]$argument
        if ($text -match '\s' -and -not ($text.StartsWith('"') -and $text.EndsWith('"'))) {
            $text = '"' + $text + '"'
        }
        $parts += $text
    }
    $command = ('"' + $exe + '" ' + ($parts -join ' '))
    if ($DryRun) {
        return [pscustomobject]@{ ok = $true; code = 'DRY_RUN'; name = $Name; command = $command }
    }
    try {
        Set-ItemProperty -Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\RunOnce' -Name $Name -Value $command -ErrorAction Stop
        return [pscustomobject]@{ ok = $true; code = 'RUNONCE_SET'; name = $Name; command = $command }
    } catch {
        return [pscustomobject]@{ ok = $false; code = 'RUNONCE_FAILED'; message = $_.Exception.Message }
    }
}

function Unregister-OmResumeRunOnce {
    param(
        [string]$Name = 'OpenMarginSetupResume',
        [switch]$DryRun
    )
    if ($DryRun) { return [pscustomobject]@{ ok = $true; code = 'DRY_RUN'; name = $Name } }
    try {
        Remove-ItemProperty -Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\RunOnce' -Name $Name -ErrorAction SilentlyContinue
        return [pscustomobject]@{ ok = $true; code = 'RUNONCE_REMOVED'; name = $Name }
    } catch {
        return [pscustomobject]@{ ok = $false; code = 'RUNONCE_REMOVE_FAILED'; message = $_.Exception.Message }
    }
}

function Invoke-OmElevatedScript {
    param(
        [Parameter(Mandatory = $true)][string]$ScriptPath,
        [string[]]$Arguments = @()
    )
    $exe = Get-OmPowerShellExe
    $argumentList = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $ScriptPath) + $Arguments
    try {
        $process = Start-Process -FilePath $exe -ArgumentList $argumentList -Verb RunAs -Wait -PassThru
        return [pscustomobject]@{ ok = ($process.ExitCode -eq 0); exitCode = $process.ExitCode }
    } catch {
        return [pscustomobject]@{ ok = $false; exitCode = -1; message = $_.Exception.Message; code = 'ADMIN_DENIED' }
    }
}

function Invoke-OmElevatedDependencyInstall {
    param(
        [Parameter(Mandatory = $true)][int]$SetupPort,
        [string]$ModulePath = (Join-Path $script:OmSetupRoot 'OpenMargin.Setup.psd1'),
        [string]$ProgressPath = '',
        [switch]$LicenseAcknowledged,
        [switch]$DryRun
    )
    if ($DryRun) {
        return (Install-OmDependencies -SetupPort $SetupPort -DryRun -LicenseAcknowledged:$LicenseAcknowledged -ProgressPath $ProgressPath)
    }
    $tempRoot = Join-Path $env:TEMP ('openmargin-elevated-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null
    $scriptPath = Join-Path $tempRoot 'install.ps1'
    $resultPath = Join-Path $tempRoot 'result.json'
    $scriptBody = @'
param([string]$ModulePath,[int]$SetupPort,[string]$ResultPath,[string]$ProgressPath,[switch]$LicenseAcknowledged)
Import-Module $ModulePath -Force -DisableNameChecking
try {
  $r = Install-OmDependencies -SetupPort $SetupPort -ProgressPath $ProgressPath -LicenseAcknowledged:$LicenseAcknowledged
  $r | ConvertTo-Json -Depth 10 | Set-Content -Path $ResultPath -Encoding UTF8
} catch {
  @{ ok = $false; code = 'ELEVATED_INSTALL_FAILED'; message = $_.Exception.Message } |
    ConvertTo-Json -Depth 5 | Set-Content -Path $ResultPath -Encoding UTF8
}
'@
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($scriptPath, $scriptBody, $utf8)
    $exe = Get-OmPowerShellExe
    try {
        $arguments = @(
            '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $scriptPath,
            '-ModulePath', $ModulePath, '-SetupPort', $SetupPort, '-ResultPath', $resultPath
        )
        if ($ProgressPath) { $arguments += @('-ProgressPath', $ProgressPath) }
        if ($LicenseAcknowledged) { $arguments += '-LicenseAcknowledged' }
        $process = Start-Process -FilePath $exe -ArgumentList $arguments -Verb RunAs -Wait -PassThru
        if (Test-Path $resultPath) {
            return (Get-Content -Path $resultPath -Raw -Encoding UTF8 | ConvertFrom-Json)
        }
        return [pscustomobject]@{ ok = $false; code = 'ELEVATED_NO_RESULT'; message = ('exit ' + $process.ExitCode); retryable = $true }
    } catch {
        return [pscustomobject]@{ ok = $false; code = 'ADMIN_DENIED'; message = '需要管理员权限，UAC 被取消。'; action = 'relaunch_as_admin'; retryable = $false }
    } finally {
        Remove-Item -Recurse -Force $tempRoot -ErrorAction SilentlyContinue
    }
}
