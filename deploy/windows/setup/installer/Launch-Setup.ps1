#Requires -Version 5.1
<#
.SYNOPSIS
    Start the local Open Margin setup wizard on the first free loopback port.

.DESCRIPTION
    The Start Menu shortcut points here instead of directly at
    Start-OpenMarginSetupWeb.ps1. A stale hidden wizard may still own 18770;
    starting a fresh instance on 18771-18779 keeps the shortcut working without
    killing a background deployment job owned by the old process.

    The wizard itself opens the browser with a per-process setup token. This
    launcher only chooses a port, starts the hidden PowerShell host, and reports
    a visible error if the host exits immediately.
#>
param(
    [int[]]$Ports = @(18770, 18771, 18772, 18773, 18774, 18775, 18776, 18777, 18778, 18779),
    [string]$StateRoot = (Join-Path $env:LOCALAPPDATA 'OpenMargin'),
    [string]$AppRoot = '',
    [switch]$NoBrowser,
    [switch]$NoElevate
)

$ErrorActionPreference = 'Stop'

$setupRoot = Split-Path -Parent $PSScriptRoot
$setupScript = Join-Path $setupRoot 'Start-OpenMarginSetupWeb.ps1'
if (-not $AppRoot) {
    $AppRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
}
$logDir = Join-Path $StateRoot 'logs'
$logPath = Join-Path $logDir ('launch-' + (Get-Date -Format 'yyyyMMdd') + '.log')

function Write-LaunchLog {
    param([string]$Message)
    try {
        if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Force -Path $logDir | Out-Null }
        Add-Content -Path $logPath -Value ('{0} {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Message) -Encoding UTF8
    } catch { }
}

function Show-LaunchError {
    param([string]$Message)
    Write-LaunchLog ('ERROR: ' + $Message)
    try {
        Add-Type -AssemblyName System.Windows.Forms -ErrorAction Stop
        [System.Windows.Forms.MessageBox]::Show(
            $Message,
            '清衡设置',
            [System.Windows.Forms.MessageBoxButtons]::OK,
            [System.Windows.Forms.MessageBoxIcon]::Error
        ) | Out-Null
    } catch {
        Write-Error $Message
    }
}

function Test-LoopbackPortFree {
    param([int]$Port)
    $listener = $null
    try {
        $listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, $Port)
        $listener.Start()
        return $true
    } catch {
        return $false
    } finally {
        if ($listener) {
            try { $listener.Stop() } catch { }
        }
    }
}

try {
    if (-not (Test-Path $setupScript)) {
        throw ('找不到向导脚本：' + $setupScript)
    }

    $port = 0
    foreach ($candidate in $Ports) {
        if (Test-LoopbackPortFree -Port $candidate) {
            $port = $candidate
            break
        }
    }
    if ($port -eq 0) {
        throw '本机向导端口 18770-18779 都被占用。请关闭旧的“清衡设置”窗口后重试，或重启电脑。'
    }

    $powershell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    $arguments = '-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "{0}" -Port {1} -StateRoot "{2}"' -f $setupScript, $port, $StateRoot
    if ($NoBrowser) { $arguments += ' -NoBrowser' }

    Write-LaunchLog ('starting setup wizard on port ' + $port)
    $process = Start-Process -FilePath $powershell -ArgumentList $arguments -WorkingDirectory $AppRoot -WindowStyle Hidden -PassThru
    Start-Sleep -Milliseconds 1200

    if ($process.HasExited) {
        throw ('向导未能启动（退出码 ' + $process.ExitCode + '）。日志：' + $logPath)
    }

    if (-not $NoBrowser) {
        # The server writes the token URL for this one-time handoff. Reading it
        # here avoids guessing the port when the server auto-advances.
        $launchUrlPath = Join-Path $StateRoot 'setup-web-url.txt'
        $url = $null
        $deadline = (Get-Date).AddSeconds(15)
        do {
            if (Test-Path $launchUrlPath) {
                try { $url = (Get-Content -Path $launchUrlPath -Raw -Encoding UTF8).Trim() } catch { }
            }
            if (-not $url) { Start-Sleep -Milliseconds 200 }
        } while (-not $url -and (Get-Date) -lt $deadline)
        if (-not $url) { $url = 'http://127.0.0.1:' + $port + '/' }
        Remove-Item -Force $launchUrlPath -ErrorAction SilentlyContinue

        $isElevated = $false
        try {
            $principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
            $isElevated = $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
        } catch { }
        if ($isElevated -and -not $NoElevate) {
            # explorer.exe forwards the URL to the existing (non-elevated)
            # shell so the browser is not launched elevated.
            try {
                Start-Process -FilePath (Join-Path $env:SystemRoot 'explorer.exe') -ArgumentList $url | Out-Null
            } catch {
                Start-Process $url | Out-Null
            }
        } else {
            Start-Process $url | Out-Null
        }
    }

    Write-LaunchLog ('setup wizard pid ' + $process.Id + ' port ' + $port)
} catch {
    Show-LaunchError ($_.Exception.Message + [Environment]::NewLine + [Environment]::NewLine + '日志：' + $logPath)
    exit 1
}
