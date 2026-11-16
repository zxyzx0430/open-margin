param(
    [string]$StateRoot = (Join-Path $env:LOCALAPPDATA 'OpenMargin'),
    [switch]$Once,
    [switch]$DryRun,
    [int]$IntervalSeconds = 60
)

# Open Margin watchdog. Runs hidden at logon (scheduled task -> wscript.exe ->
# this script), checks Docker, containers, health, Tailscale and Funnel, and
# repairs only what it is allowed to repair. It never logs in to Tailscale,
# never approves Funnel, never opens a window and never removes volumes.

$ErrorActionPreference = 'Continue'
$logDir = Join-Path $StateRoot 'logs'
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Force -Path $logDir | Out-Null }
$logPath = Join-Path $logDir 'watchdog.log'
$configPath = Join-Path (Join-Path $StateRoot 'watchdog') 'watchdog-config.json'
$statePath = Join-Path $StateRoot 'setup-state.json'
$watchdogStatePath = Join-Path $StateRoot 'watchdog-state.json'

function Write-WatchdogLog([string]$Message) {
    $safe = $Message
    try {
        $safe = $safe -replace '(?i)(token|password|secret)\s*[=:]\s*\S+', '$1=***'
        $safe = $safe -replace '\b[A-Za-z0-9_-]{40,}\b', '***'
    } catch { }
    $line = "{0} {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $safe
    try { Add-Content -Path $logPath -Value $line -Encoding UTF8 } catch { }
    if ((Test-Path $logPath) -and ((Get-Item $logPath).Length -gt 5MB)) {
        Move-Item -Force $logPath ($logPath + '.1')
    }
    try {
        Get-ChildItem -Path $logDir -Filter 'watchdog.log.*' -File -ErrorAction SilentlyContinue |
            Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-7) } |
            Remove-Item -Force -ErrorAction SilentlyContinue
    } catch { }
}

function Read-JsonFile([string]$Path) {
    if (-not (Test-Path $Path)) { return $null }
    try { return (Get-Content -Path $Path -Raw -Encoding UTF8 | ConvertFrom-Json) } catch { return $null }
}

function Save-JsonFile([string]$Path, $Value) {
    try {
        $tmp = $Path + '.tmp'
        $utf8 = New-Object System.Text.UTF8Encoding($false)
        [System.IO.File]::WriteAllText($tmp, ($Value | ConvertTo-Json -Depth 8), $utf8)
        Move-Item -Force $tmp $Path
    } catch { }
}

function Invoke-WatchdogAction([string]$Description, [scriptblock]$Action) {
    if ($DryRun) {
        Write-WatchdogLog ('DRY RUN: ' + $Description)
        return $true
    }
    Write-WatchdogLog ('RUN: ' + $Description)
    try {
        & $Action
        return $true
    } catch {
        Write-WatchdogLog ('FAILED: ' + $Description + ' :: ' + $_.Exception.Message)
        return $false
    }
}

function Get-ConfigPort($Config, [string]$Name, [int]$Default) {
    try {
        if ($Config -and $Config.ports -and $Config.ports.$Name) { return [int]$Config.ports.$Name }
    } catch { }
    return $Default
}

function Test-DockerEngine {
    $docker = Get-Command docker -ErrorAction SilentlyContinue
    if (-not $docker) { return $false }
    & docker info *> $null
    return ($LASTEXITCODE -eq 0)
}

function Start-DockerDesktopIfFound {
    $candidates = @((Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'))
    $programFilesX86 = [Environment]::GetEnvironmentVariable('ProgramFiles(x86)')
    if ($programFilesX86) { $candidates += (Join-Path $programFilesX86 'Docker\Docker\Docker Desktop.exe') }
    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        if (Test-Path $candidate) {
            Start-Process -FilePath $candidate -WindowStyle Hidden | Out-Null
            return $true
        }
    }
    return $false
}

function Get-DockerContainers {
    $output = & docker compose --project-name $projectName --profile windows-public-sync ps --format json 2>$null | Out-String
    if ($LASTEXITCODE -ne 0) { return @() }
    $items = [System.Collections.Generic.List[object]]::new()
    foreach ($line in (($output -split "`r?`n"))) {
        $trimmed = $line.Trim()
        if (-not $trimmed) { continue }
        try { $items.Add(($trimmed | ConvertFrom-Json)) } catch { }
    }
    return @($items.ToArray())
}

function Get-TailscalePath {
    $cmd = Get-Command tailscale -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $candidate = Join-Path $env:ProgramFiles 'Tailscale\tailscale.exe'
    if (Test-Path $candidate) { return $candidate }
    return $null
}

function Get-FunnelStatus([string]$TailscalePath) {
    try {
        $text = (& $TailscalePath funnel status --json 2>$null | Out-String)
        if (-not $text.Trim()) { return $null }
        return ($text | ConvertFrom-Json)
    } catch { return $null }
}

function Test-OurFunnelRoute($Status, [int]$Port) {
    if ($null -eq $Status) { return $false }
    $web = $Status.PSObject.Properties['Web']
    if ($null -eq $web -or $null -eq $web.Value) { return $false }
    foreach ($site in @($web.Value.PSObject.Properties)) {
        $handlers = $site.Value.PSObject.Properties['Handlers']
        if ($null -eq $handlers -or $null -eq $handlers.Value) { continue }
        foreach ($handler in @($handlers.Value.PSObject.Properties)) {
            if ([string]$handler.Value.Proxy -eq ('http://127.0.0.1:' + $Port)) { return $true }
        }
    }
    return $false
}

function Test-FunnelApproved($Status) {
    if ($null -eq $Status) { return $false }
    $allow = $Status.PSObject.Properties['AllowFunnel']
    if ($null -eq $allow -or $null -eq $allow.Value) { return $false }
    foreach ($item in @($allow.Value.PSObject.Properties)) {
        if ($item.Value -eq $true) { return $true }
    }
    return $false
}

$config = Read-JsonFile $configPath
$state = Read-JsonFile $statePath
if (-not $config) {
    Write-WatchdogLog 'watchdog config missing; exiting'
    if ($Once) { exit 1 }
    exit 1
}

$apiPort = Get-ConfigPort $config 'api' 8767
$gatewayPort = Get-ConfigPort $config 'gateway' 18768
$projectName = if ($config.projectName) { [string]$config.projectName } else { 'personal-finance-system' }
$workingDirectory = $null
if ($config.PSObject.Properties['workingDirectory'] -and $config.workingDirectory) {
    $workingDirectory = [string]$config.workingDirectory
} elseif ($config.PSObject.Properties['repoRoot'] -and $config.repoRoot) {
    $workingDirectory = [string]$config.repoRoot
}
if ($workingDirectory -and (Test-Path $workingDirectory)) {
    Set-Location $workingDirectory
} else {
    Write-WatchdogLog 'working directory missing; compose may not find compose.yaml'
}

$watchdogState = Read-JsonFile $watchdogStatePath
if (-not $watchdogState) {
    $watchdogState = [pscustomobject]@{ failures = 0; lastFailure = $null; lastRepair = $null; lastActionRequired = $null; lastCheck = $null }
}

function Record-Failure {
    $now = Get-Date
    $failures = 1
    $last = $null
    try { $last = [datetime]$watchdogState.lastFailure } catch { }
    if ($last -and ($now - $last).TotalMinutes -lt 10) { $failures = [int]$watchdogState.failures + 1 }
    $watchdogState.failures = $failures
    $watchdogState.lastFailure = $now.ToString('o')
    Save-JsonFile $watchdogStatePath $watchdogState
    return $failures
}

function Test-RepairAllowed {
    return ([int]$watchdogState.failures -lt 3)
}

function Reset-Failures {
    $watchdogState.failures = 0
    $watchdogState.lastFailure = $null
    $watchdogState.lastRepair = (Get-Date).ToString('o')
}

function Invoke-WatchdogPass {
    $now = Get-Date
    $passFailed = $false
    $state = Read-JsonFile $statePath
    $stateStep = if ($state) { [string]$state.step } else { '' }
    $stateMode = if ($state) { [string]$state.mode } else { '' }

    if ($state -and $state.watchdogPaused -eq $true) {
        $until = $null
        try { $until = [datetime]$state.watchdogPausedUntil } catch { }
        if (-not $until -or $now -lt $until) {
            Write-WatchdogLog 'watchdog paused; skipping'
            return
        }
        Write-WatchdogLog 'watchdog pause expired; resuming'
    }
    if ($stateStep -in @('INSTALLING_DEPS', 'DEPLOYING', 'VERIFYING', 'SWITCHING', 'DISABLING')) {
        Write-WatchdogLog ('setup is busy (' + $stateStep + '); skipping')
        return
    }

    if (-not (Test-DockerEngine)) {
        if (Test-RepairAllowed) {
            $null = Invoke-WatchdogAction 'start Docker Desktop' { if (-not (Start-DockerDesktopIfFound)) { throw 'Docker Desktop.exe not found' } }
            $deadline = (Get-Date).AddSeconds(180)
            do {
                if (Test-DockerEngine) { break }
                Start-Sleep -Seconds 5
            } while ((Get-Date) -lt $deadline)
        }
        if (-not (Test-DockerEngine)) {
            $null = Record-Failure
            Write-WatchdogLog 'docker engine unavailable; cannot repair'
            return
        }
    }

    # Containers: start the public profile if any required service is missing.
    $containers = @(Get-DockerContainers)
    $runningNames = @($containers | Where-Object { [string]$_.State -eq 'running' } | ForEach-Object { [string]$_.Service })
    $required = @('finance-api', 'wallos', 'public-sync-gateway')
    $missing = @($required | Where-Object { $runningNames -notcontains $_ })
    if ($missing.Count -gt 0) {
        if (Test-RepairAllowed) {
            $null = Invoke-WatchdogAction ('compose up (missing: ' + ($missing -join ', ') + ')') {
                & docker compose --project-name $projectName --profile windows-public-sync up -d *> $null
                if ($LASTEXITCODE -ne 0) { throw 'compose up failed' }
            }
            $null = Record-Failure
        } else {
            Write-WatchdogLog 'repair budget exhausted; not starting containers'
            $passFailed = $true
        }
    }

    # Health: restart finance-api, then gateway, if health still fails.
    $healthUrl = 'http://127.0.0.1:' + $apiPort + '/health'
    $healthy = $false
    try {
        $health = Invoke-WebRequest -Uri $healthUrl -UseBasicParsing -TimeoutSec 8 -ErrorAction Stop
        $healthy = ([int]$health.StatusCode -eq 200)
    } catch { $healthy = $false }
    if (-not $healthy -and (Test-RepairAllowed)) {
        $null = Invoke-WatchdogAction 'restart finance-api' {
            & docker compose --project-name $projectName restart finance-api *> $null
            if ($LASTEXITCODE -ne 0) { throw 'restart finance-api failed' }
        }
        Start-Sleep -Seconds 8
        try {
            $health = Invoke-WebRequest -Uri $healthUrl -UseBasicParsing -TimeoutSec 8 -ErrorAction Stop
            $healthy = ([int]$health.StatusCode -eq 200)
        } catch { }
        if (-not $healthy) {
            $null = Invoke-WatchdogAction 'restart public-sync-gateway' {
                & docker compose --project-name $projectName restart public-sync-gateway *> $null
                if ($LASTEXITCODE -ne 0) { throw 'restart gateway failed' }
            }
        }
        $null = Record-Failure
        $passFailed = $true
    } elseif (-not $healthy) {
        $passFailed = $true
    }

    # Tailscale: never signs in automatically.
    $tailscale = Get-TailscalePath
    if (-not $tailscale) {
        Write-WatchdogLog 'tailscale not installed; skipping network repair'
    } else {
        $status = $null
        try {
            $statusText = (& $tailscale status --json 2>$null | Out-String)
            if ($statusText.Trim()) { $status = $statusText | ConvertFrom-Json }
        } catch { }
        $backend = if ($status -and $status.PSObject.Properties['BackendState']) { [string]$status.BackendState } else { '' }
        if ($backend -eq 'NeedsLogin') {
            $watchdogState.lastActionRequired = '需要登录 Tailscale'
            Save-JsonFile $watchdogStatePath $watchdogState
            Write-WatchdogLog 'tailscale needs login; not logging in automatically'
        } elseif ($backend -and $backend -ne 'Running') {
            if (Test-RepairAllowed) {
                $null = Invoke-WatchdogAction 'tailscale up' {
                    Start-Process -FilePath $tailscale -ArgumentList @('up') -WindowStyle Hidden | Out-Null
                }
                $null = Record-Failure
                $passFailed = $true
            } else {
                $passFailed = $true
            }
        } elseif ($backend -eq 'Running') {
            $funnel = Get-FunnelStatus $tailscale
            $routeExists = Test-OurFunnelRoute $funnel $gatewayPort
            $remoteEnabled = ($stateMode -ne 'disabled') -and ($stateStep -in @('COMPLETE', 'PHONE_VERIFIED', 'SWITCHING'))
            if ($remoteEnabled -and -not $routeExists) {
                if (Test-FunnelApproved $funnel) {
                    if (Test-RepairAllowed) {
                        $null = Invoke-WatchdogAction 'restore funnel route' {
                            & $tailscale funnel --bg --https=443 ('http://127.0.0.1:' + $gatewayPort) *> $null
                            if ($LASTEXITCODE -ne 0) { throw 'funnel command failed' }
                        }
                        $null = Record-Failure
                        $passFailed = $true
                    } else {
                        $passFailed = $true
                    }
                } else {
                    $watchdogState.lastActionRequired = 'Funnel 需要用户重新授权'
                    Save-JsonFile $watchdogStatePath $watchdogState
                    Write-WatchdogLog 'funnel route missing and not approved; waiting for user'
                }
            } elseif ($routeExists) {
                $watchdogState.lastActionRequired = $null
            }
        }
    }

    if ($healthy -and -not $passFailed) {
        Reset-Failures
        $watchdogState.lastCheck = (Get-Date).ToString('o')
        Save-JsonFile $watchdogStatePath $watchdogState
    }
}

Invoke-WatchdogPass
if ($Once) { exit 0 }
while ($true) {
    Start-Sleep -Seconds $IntervalSeconds
    Invoke-WatchdogPass
}
