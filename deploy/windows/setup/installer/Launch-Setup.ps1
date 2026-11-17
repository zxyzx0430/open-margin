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

function Show-LaunchMessage {
    param([string]$Message, [string]$Title = '清衡设置')
    Write-LaunchLog $Message
    try {
        Add-Type -AssemblyName System.Windows.Forms -ErrorAction Stop
        [System.Windows.Forms.MessageBox]::Show(
            $Message,
            $Title,
            [System.Windows.Forms.MessageBoxButtons]::OK,
            [System.Windows.Forms.MessageBoxIcon]::Information
        ) | Out-Null
    } catch {
        Write-Warning $Message
    }
}

function Test-LaunchElevated {
    try {
        $principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
        return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    } catch {
        return $false
    }
}

function Get-LaunchWizardStatus {
    param([string]$StopScript, [string]$AppRoot, [string]$StateRoot)
    if (-not (Test-Path $StopScript)) { return $null }
    try {
        $raw = & $StopScript -Status -AppRoot $AppRoot -StateRoot $StateRoot -Json 2>$null
        if (-not $raw) { return $null }
        return ($raw | Out-String | ConvertFrom-Json)
    } catch {
        Write-LaunchLog ('wizard status check failed: ' + $_.Exception.Message)
        return $null
    }
}

function Open-SetupUrl {
    param([string]$Url, [bool]$Elevated)
    $errors = @()
    if ($Elevated) {
        # explorer.exe forwards the URL to the existing (non-elevated) shell so
        # the browser is not launched with the elevated token.
        try {
            Start-Process -FilePath (Join-Path $env:SystemRoot 'explorer.exe') -ArgumentList $Url -ErrorAction Stop | Out-Null
            return $true
        } catch {
            $errors += $_.Exception.Message
        }
    }
    try {
        Start-Process $Url -ErrorAction Stop | Out-Null
        return $true
    } catch {
        $errors += $_.Exception.Message
    }
    Write-LaunchLog ('browser open failed: ' + ($errors -join ' | '))
    return $false
}

function Show-SetupUrlFallback {
    param([string]$Url, [string]$UrlPath)
    $lines = @(
        '向导已启动，但浏览器没有自动打开。',
        '',
        '请手动打开下面的地址（含一次性登录 token）：',
        $Url,
        ''
    )
    if ($UrlPath) { $lines += ('地址也保存在：' + $UrlPath) }
    Show-LaunchMessage -Message ($lines -join [Environment]::NewLine)
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

$stopScript = Join-Path $PSScriptRoot 'Stop-OpenMargin.ps1'
$handoffPath = Join-Path $StateRoot 'setup-web-url.txt'
$isElevated = Test-LaunchElevated

try {
    if (-not (Test-Path $setupScript)) {
        throw ('找不到向导脚本：' + $setupScript)
    }

    # Single instance: reuse a live wizard and clear a stale setup-web.json
    # before choosing a new port. Stop-OpenMargin.ps1 is designed to be invoked
    # in-process (it returns instead of calling exit).
    $wizardStatus = Get-LaunchWizardStatus -StopScript $stopScript -AppRoot $AppRoot -StateRoot $StateRoot
    if ($wizardStatus -and $wizardStatus.wizard) {
        $wizard = $wizardStatus.wizard
        if ([bool]$wizard.running -and [string]$wizard.url) {
            $reuseUrl = $null
            if (Test-Path $handoffPath) {
                try { $reuseUrl = (Get-Content -Path $handoffPath -Raw -Encoding UTF8).Trim() } catch { }
            }
            if (-not $reuseUrl) { $reuseUrl = [string]$wizard.url }
            Write-LaunchLog ('reusing running setup wizard at ' + $reuseUrl)
            if ($NoBrowser) {
                Write-Output ('Open Margin setup wizard already running: ' + $reuseUrl)
            } else {
                $opened = Open-SetupUrl -Url $reuseUrl -Elevated $isElevated
                if ($opened) {
                    Remove-Item -Force $handoffPath -ErrorAction SilentlyContinue
                } else {
                    Show-SetupUrlFallback -Url $reuseUrl -UrlPath $handoffPath
                }
            }
            return
        }
        if ($wizard.statePid -and -not [bool]$wizard.stateMatches) {
            Write-LaunchLog ('clearing stale setup-web.json (recorded pid ' + $wizard.statePid + ')')
            try {
                & $stopScript -WizardOnly -AppRoot $AppRoot -StateRoot $StateRoot -Json | Out-Null
            } catch {
                Write-LaunchLog ('stale wizard state cleanup failed: ' + $_.Exception.Message)
            }
        }
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
    # The launcher owns the token-URL browser handoff, so the hidden server must
    # never open a second browser of its own.
    $arguments = '-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "{0}" -Port {1} -StateRoot "{2}" -NoBrowser' -f $setupScript, $port, $StateRoot

    Write-LaunchLog ('starting setup wizard on port ' + $port)
    $process = Start-Process -FilePath $powershell -ArgumentList $arguments -WorkingDirectory $AppRoot -WindowStyle Hidden -PassThru
    Start-Sleep -Milliseconds 1200

    if ($process.HasExited) {
        throw ('向导未能启动（退出码 ' + $process.ExitCode + '）。日志：' + $logPath)
    }

    if (-not $NoBrowser) {
        # The server writes the token URL for this one-time handoff. Reading it
        # here avoids guessing the port when the server auto-advances.
        $url = $null
        $deadline = (Get-Date).AddSeconds(15)
        do {
            if (Test-Path $handoffPath) {
                try { $url = (Get-Content -Path $handoffPath -Raw -Encoding UTF8).Trim() } catch { }
            }
            if (-not $url) { Start-Sleep -Milliseconds 200 }
        } while (-not $url -and (Get-Date) -lt $deadline)
        if (-not $url) { $url = 'http://127.0.0.1:' + $port + '/' }

        $opened = Open-SetupUrl -Url $url -Elevated $isElevated
        if ($opened) {
            # Remove the token handoff once the browser has it; the server also
            # removes it on exit.
            Remove-Item -Force $handoffPath -ErrorAction SilentlyContinue
        } else {
            Show-SetupUrlFallback -Url $url -UrlPath $handoffPath
        }
    }

    Write-LaunchLog ('setup wizard pid ' + $process.Id + ' port ' + $port)
} catch {
    Show-LaunchError ($_.Exception.Message + [Environment]::NewLine + [Environment]::NewLine + '日志：' + $logPath)
    exit 1
}
