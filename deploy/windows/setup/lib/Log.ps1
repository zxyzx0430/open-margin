# Open Margin setup engine: logging and redaction.
# Windows PowerShell 5.1 compatible. Never writes device tokens, pairing
# payloads or administrator passwords to disk.

$script:OmLogPath = $null
$script:OmLogMaxBytes = 5MB

function Initialize-OmLog {
    param([Parameter(Mandatory = $true)][string]$Directory)
    if (-not (Test-Path $Directory)) {
        New-Item -ItemType Directory -Force -Path $Directory | Out-Null
    }
    $script:OmLogPath = Join-Path $Directory ("setup-{0}.log" -f (Get-Date -Format 'yyyyMMdd'))
    if ((Test-Path $script:OmLogPath) -and ((Get-Item $script:OmLogPath).Length -gt $script:OmLogMaxBytes)) {
        Move-Item -Force $script:OmLogPath ($script:OmLogPath + '.1')
    }
}

function Protect-OmText {
    param([AllowNull()][string]$Text)
    if ([string]::IsNullOrEmpty($Text)) { return $Text }
    $protected = $Text
    # Mask header and bearer forms before the generic key/value rule.
    $protected = [regex]::Replace($protected, '(?i)(Authorization\s*:\s*Bearer\s+)\S+', '$1***')
    $protected = [regex]::Replace($protected, '(?i)(Authorization\s*:\s*)(?!Bearer\b)\S+', '$1***')
    $protected = [regex]::Replace($protected, '(?i)(X-(?:Device|Admin|Wallos-Ledger)-Token\s*:\s*)\S+', '$1***')
    $protected = [regex]::Replace($protected, '(?i)Bearer\s+[A-Za-z0-9._~+/=-]+', 'Bearer ***')
    # Key/value forms, including JSON: "token": "...", password=..., secret: ...
    # The key may be quoted and the value may be quoted.
    $protected = [regex]::Replace(
        $protected,
        '(?i)((?:device[_-]?token|admin[_-]?token|ledger[_-]?token|pairing[_-]?token|access[_-]?token|refresh[_-]?token|client[_-]?secret|password|passwd|secret|token)\s*"?\s*[:=]\s*"?)([^"\s,;&}\]]+)',
        '$1***')
    # Device tokens and pairing secrets are long base64url strings. Keep URLs
    # and normal log text intact; only mask long unbroken token-like words.
    $protected = [regex]::Replace($protected, '\b[A-Za-z0-9_-]{40,}\b', '***')
    return $protected
}

function Write-OmLog {
    param(
        [ValidateSet('DEBUG', 'INFO', 'WARN', 'ERROR')][string]$Level = 'INFO',
        [Parameter(Mandatory = $true)][string]$Message,
        [AllowNull()]$Data = $null
    )
    $line = "{0} [{1}] {2}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Level, (Protect-OmText $Message)
    if ($null -ne $Data) {
        try {
            $json = $Data | ConvertTo-Json -Depth 8 -Compress
        } catch {
            $json = [string]$Data
        }
        $line += " " + (Protect-OmText $json)
    }
    Write-Verbose $line
    if ($script:OmLogPath) {
        try {
            Add-Content -Path $script:OmLogPath -Value $line -Encoding UTF8
        } catch {
            # Logging must never break a deployment step.
        }
    }
}

function Get-OmLogTail {
    param([int]$Lines = 200)
    if (-not $script:OmLogPath -or -not (Test-Path $script:OmLogPath)) { return @() }
    if ($Lines -lt 1) { return @() }
    if ($Lines -gt 1000) { $Lines = 1000 }
    $all = @(Get-Content -Path $script:OmLogPath -Tail $Lines -ErrorAction SilentlyContinue)
    return @($all | ForEach-Object { Protect-OmText $_ })
}

function Invoke-OmCommandWithTimeout {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [string[]]$Arguments = @(),
        [int]$TimeoutMs = 5000
    )
    try {
        $psi = New-Object System.Diagnostics.ProcessStartInfo
        $psi.FileName = $FilePath
        $psi.Arguments = ($Arguments -join ' ')
        $psi.RedirectStandardOutput = $true
        $psi.RedirectStandardError = $true
        $psi.UseShellExecute = $false
        $psi.CreateNoWindow = $true
        $psi.StandardOutputEncoding = [System.Text.Encoding]::UTF8
        $psi.StandardErrorEncoding = [System.Text.Encoding]::UTF8
        $proc = [System.Diagnostics.Process]::Start($psi)
        if (-not $proc.WaitForExit($TimeoutMs)) {
            try { $proc.Kill() } catch { }
            return [pscustomobject]@{ ok = $false; timedOut = $true; output = "(command timed out after ${TimeoutMs}ms: $FilePath)" }
        }
        $stdout = $proc.StandardOutput.ReadToEnd()
        $stderr = $proc.StandardError.ReadToEnd()
        $combined = (@($stdout, $stderr) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join "`n"
        return [pscustomobject]@{ ok = ($proc.ExitCode -eq 0); timedOut = $false; output = $combined.Trim() }
    } catch {
        return [pscustomobject]@{ ok = $false; timedOut = $false; output = "(unavailable: $($_.Exception.Message))" }
    }
}

function Export-OmDiagnosticsBundle {
    param(
        [string]$StateRoot = '',
        [string]$RepoRoot = '',
        [int]$SetupPort = 18770,
        [string]$WizardVersion = '1.3.1',
        [int]$MaxLogLinesPerFile = 800,
        [switch]$SkipExternalCommands
    )
    if (-not $StateRoot) {
        if ($script:OmLogPath) {
            $StateRoot = Split-Path -Parent (Split-Path -Parent $script:OmLogPath)
        } else {
            $StateRoot = Join-Path $env:LOCALAPPDATA 'OpenMargin'
        }
    }
    if ($MaxLogLinesPerFile -lt 50) { $MaxLogLinesPerFile = 50 }
    if ($MaxLogLinesPerFile -gt 2000) { $MaxLogLinesPerFile = 2000 }

    $sb = New-Object System.Text.StringBuilder
    $null = $sb.AppendLine('Open Margin (清衡) Windows 安装器排障日志')
    $null = $sb.AppendLine('==================================================')
    $null = $sb.AppendLine(('导出时间: ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')))
    $null = $sb.AppendLine(('安装器版本: ' + $WizardVersion))
    $null = $sb.AppendLine(('状态目录: ' + $StateRoot))
    if ($RepoRoot) { $null = $sb.AppendLine(('仓库目录: ' + $RepoRoot)) }
    $null = $sb.AppendLine(('向导端口: ' + $SetupPort))
    $null = $sb.AppendLine('')

    # 1. 环境与状态快照
    $null = $sb.AppendLine('=== 1. 环境与安装器状态快照 ===')
    try {
        $os = [System.Environment]::OSVersion.VersionString
        $isAdmin = $false
        try {
            $principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
            $isAdmin = $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
        } catch { }
        $null = $sb.AppendLine("OS: $os | PS: $($PSVersionTable.PSVersion) | Admin: $isAdmin | Arch: $env:PROCESSOR_ARCHITECTURE")
    } catch {
        $null = $sb.AppendLine('OS: (读取失败)')
    }

    $statePath = Join-Path $StateRoot 'state.json'
    if (Test-Path -LiteralPath $statePath) {
        try {
            $st = Get-Content -LiteralPath $statePath -Raw -Encoding UTF8 | ConvertFrom-Json
            $safeState = [ordered]@{
                step            = $st.step
                publicUrl       = $st.publicUrl
                serverId        = $st.serverId
                publicProbeMode = $st.publicProbeMode
                pendingReboot   = $st.pendingReboot
                watchdogPaused  = $st.watchdogPaused
                ports           = $st.ports
                lastError       = $st.lastError
                lastCheck       = $st.lastCheck
            }
            $null = $sb.AppendLine('state.json (脱敏摘要):')
            $null = $sb.AppendLine((Protect-OmText ($safeState | ConvertTo-Json -Depth 8)))
        } catch {
            $null = $sb.AppendLine('state.json: (解析失败: ' + $_.Exception.Message + ')')
        }
    } else {
        $null = $sb.AppendLine('state.json: (不存在)')
    }

    $watchdogPath = Join-Path $StateRoot 'watchdog-state.json'
    if (Test-Path -LiteralPath $watchdogPath) {
        try {
            $wdRaw = Get-Content -LiteralPath $watchdogPath -Raw -Encoding UTF8
            $null = $sb.AppendLine('watchdog-state.json:')
            $null = $sb.AppendLine((Protect-OmText $wdRaw.Trim()))
        } catch { }
    }
    $null = $sb.AppendLine('')

    # 2. 最近后台任务进度
    $null = $sb.AppendLine('=== 2. 最近后台任务记录 ===')
    $jobDir = Join-Path $StateRoot 'jobs'
    if (Test-Path -LiteralPath $jobDir) {
        $jobFiles = @(Get-ChildItem -LiteralPath $jobDir -Filter '*.json' -File -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending | Select-Object -First 6)
        if ($jobFiles.Count -eq 0) {
            $null = $sb.AppendLine('(无任务记录)')
        } else {
            foreach ($jf in $jobFiles) {
                $null = $sb.AppendLine("--- $($jf.Name) ($($jf.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'))) ---")
                try {
                    $jRaw = Get-Content -LiteralPath $jf.FullName -Raw -Encoding UTF8
                    $null = $sb.AppendLine((Protect-OmText $jRaw.Trim()))
                } catch {
                    $null = $sb.AppendLine('(读取失败)')
                }
            }
        }
    } else {
        $null = $sb.AppendLine('(jobs 目录不存在)')
    }
    $null = $sb.AppendLine('')

    # 3. 本地日志文件（setup / launch / watchdog / uninstall / migrate）
    $null = $sb.AppendLine('=== 3. 本地日志文件 (已脱敏) ===')
    $logDir = Join-Path $StateRoot 'logs'
    if (Test-Path -LiteralPath $logDir) {
        $logFiles = @(Get-ChildItem -LiteralPath $logDir -Filter '*.log' -File -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending | Select-Object -First 8)
        if ($logFiles.Count -eq 0) {
            $null = $sb.AppendLine('(无日志文件)')
        } else {
            foreach ($lf in $logFiles) {
                $null = $sb.AppendLine("--- $($lf.Name) ($($lf.Length) bytes, updated $($lf.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'))) ---")
                $tail = @(Get-Content -LiteralPath $lf.FullName -Tail $MaxLogLinesPerFile -ErrorAction SilentlyContinue)
                foreach ($line in $tail) {
                    $null = $sb.AppendLine((Protect-OmText $line))
                }
            }
        }
    } else {
        $null = $sb.AppendLine('(logs 目录不存在)')
    }
    $null = $sb.AppendLine('')

    # 4. Docker / Tailscale 运行快照
    $null = $sb.AppendLine('=== 4. Docker 与网络入口快照 ===')
    if ($SkipExternalCommands) {
        $null = $sb.AppendLine('(已跳过外部命令探测)')
    } else {
        $tsRes = Invoke-OmCommandWithTimeout -FilePath 'tailscale' -Arguments @('funnel', 'status') -TimeoutMs 4000
        $null = $sb.AppendLine('[tailscale funnel status]')
        $null = $sb.AppendLine((Protect-OmText $tsRes.output))

        $dockerInfo = Invoke-OmCommandWithTimeout -FilePath 'docker' -Arguments @('ps', '-a', '--format', '"table {{.Names}}\t{{.Status}}\t{{.Ports}}"') -TimeoutMs 5000
        $null = $sb.AppendLine('[docker ps -a]')
        $null = $sb.AppendLine((Protect-OmText $dockerInfo.output))

        if ($RepoRoot) {
            $composeFile = Join-Path $RepoRoot 'docker-compose.yml'
            if (Test-Path -LiteralPath $composeFile) {
                $composeLogs = Invoke-OmCommandWithTimeout -FilePath 'docker' -Arguments @('compose', '-f', "`"$composeFile`"", '-p', 'personal-finance-system', 'logs', '--tail=60', '--no-color') -TimeoutMs 6000
                $null = $sb.AppendLine('[docker compose logs --tail=60]')
                $null = $sb.AppendLine((Protect-OmText $composeLogs.output))
            }
        }
    }

    return (Protect-OmText $sb.ToString())
}
