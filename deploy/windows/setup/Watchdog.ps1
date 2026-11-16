param(
    [string]$StateRoot = (Join-Path $env:LOCALAPPDATA 'OpenMargin'),
    [switch]$Once,
    [switch]$DryRun
)

$ErrorActionPreference = 'Continue'
$logDir = Join-Path $StateRoot 'logs'
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Force -Path $logDir | Out-Null }
$logPath = Join-Path $logDir 'watchdog.log'
$configPath = Join-Path (Join-Path $StateRoot 'watchdog') 'watchdog-config.json'
$statePath = Join-Path $StateRoot 'setup-state.json'
$watchdogStatePath = Join-Path $StateRoot 'watchdog-state.json'

function Log([string]$Message) {
    $line = "{0} {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Message
    Add-Content -Path $logPath -Value $line -Encoding UTF8
    if ((Test-Path $logPath) -and ((Get-Item $logPath).Length -gt 5MB)) {
        Move-Item -Force $logPath ($logPath + '.1')
    }
}

function Read-JsonFile([string]$Path) {
    if (-not (Test-Path $Path)) { return $null }
    try { return (Get-Content -Path $Path -Raw -Encoding UTF8 | ConvertFrom-Json) } catch { return $null }
}

function Save-JsonFile([string]$Path, $Value) {
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, ($Value | ConvertTo-Json -Depth 8), $utf8)
}

function Run([string]$Description, [scriptblock]$Action) {
    if ($DryRun) { Log ('DRY RUN: ' + $Description); return $true }
    Log ('RUN: ' + $Description)
    try {
        $result = & $Action
        return $true
    } catch {
        Log ('FAILED: ' + $Description + ' :: ' + $_.Exception.Message)
        return $false
    }
}

function Get-PortValue($Config, [string]$Name, [int]$Default) {
    try {
        if ($Config -and $Config.ports -and $Config.ports.$Name) { return [int]$Config.ports.$Name }
    } catch { }
    return $Default
}

$config = Read-JsonFile $configPath
$state = Read-JsonFile $statePath
if (-not $config) {
    Log 'watchdog config missing; exiting'
    if ($Once) { exit 1 }
    exit 1
}

$apiPort = Get-PortValue $config 'api' 8767
$gatewayPort = Get-PortValue $config 'gateway' 18768
$projectName = if ($config.projectName) { [string]$config.projectName } else { 'personal-finance-system' }

if ($state -and $state.watchdogPaused -eq $true) {
    $until = $null
    try { $until = [datetime]$state.watchdogPausedUntil } catch { }
    if (-not $until -or (Get-Date) -lt $until) {
        Log 'watchdog paused; skipping'
        if ($Once) { exit 0 }
    } else {
        Log 'watchdog pause expired; resuming'
    }
}

if ($state -and ($state.step -in @('INSTALLING_DEPS', 'DEPLOYING'))) {
    Log ('setup is busy (' + $state.step + '); skipping')
    if ($Once) { exit 0 }
}

$watchdogState = Read-JsonFile $watchdogStatePath
if (-not $watchdogState) {
    $watchdogState = [pscustomobject]@{ failures = 0; lastFailure = $null; lastRepair = $null }
}

function Record-Failure {
    $now = Get-Date
    $failures = 1
    $last = $null
    try { $last = [datetime]$script:watchdogState.lastFailure } catch { }
    if ($last -and ($now - $last).TotalMinutes -lt 10) { $failures = [int]$script:watchdogState.failures + 1 }
    $script:watchdogState.failures = $failures
    $script:watchdogState.lastFailure = $now.ToString('o')
    Save-JsonFile $watchdogStatePath $script:watchdogState
    return $failures
}

function Should-Repair {
    return ([int]$script:watchdogState.failures -lt 3)
}

# 1. Docker engine
$docker = Get-Command docker -ErrorAction SilentlyContinue
if (-not $docker) {
    Log 'docker is not installed; cannot repair'
    if ($Once) { exit 1 }
} else {
    & docker info *> $null
    if ($LASTEXITCODE -ne 0) {
        if (Should-Repair) {
            $null = Run 'start Docker Desktop' {
                $candidates = @(
                    (Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'),
                    (Join-Path ${env:ProgramFiles(x86)} 'Docker\Docker\Docker Desktop.exe')
                )
                foreach ($c in $candidates) {
                    if (Test-Path $c) { Start-Process -FilePath $c -WindowStyle Hidden | Out-Null; return }
                }
                throw 'Docker Desktop.exe not found'
            }
            $deadline = (Get-Date).AddSeconds(180)
            do {
                Start-Sleep -Seconds 5
                & docker info *> $null
                if ($LASTEXITCODE -eq 0) { break }
            } while ((Get-Date) -lt $deadline)
        }
    }
}

# 2. Containers
& docker info *> $null
if ($LASTEXITCODE -eq 0) {
    $psOutput = & docker compose --project-name $projectName --profile windows-public-sync ps --format json 2>$null | Out-String
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($psOutput)) {
        if (Should-Repair) {
            $null = Run 'compose up' {
                & docker compose --project-name $projectName --profile windows-public-sync up -d *> $null
                if ($LASTEXITCODE -ne 0) { throw 'compose up failed' }
            }
        }
    }

    # 3. Health
    try {
        $health = Invoke-WebRequest -Uri ('http://127.0.0.1:' + $apiPort + '/health') -UseBasicParsing -TimeoutSec 8 -ErrorAction Stop
        if ([int]$health.StatusCode -ne 200) { throw 'health status' }
        $script:watchdogState.failures = 0
        $script:watchdogState.lastRepair = $null
        Save-JsonFile $watchdogStatePath $script:watchdogState
    } catch {
        if (Should-Repair) {
            $null = Run 'restart finance-api' {
                & docker compose --project-name $projectName restart finance-api *> $null
                if ($LASTEXITCODE -ne 0) { throw 'restart failed' }
            }
            $null = Record-Failure
        }
    }
}

# 4. Tailscale and Funnel
$ts = Get-Command tailscale -ErrorAction SilentlyContinue
if (-not $ts) {
    $candidate = Join-Path $env:ProgramFiles 'Tailscale\tailscale.exe'
    if (Test-Path $candidate) { $ts = $candidate }
}
if (-not $ts) {
    Log 'tailscale not installed; skipping network repair'
} else {
    $statusText = (& $ts status --json 2>$null | Out-String)
    $status = $null
    try { $status = $statusText | ConvertFrom-Json } catch { }
    if (-not $status -or $status.BackendState -ne 'Running') {
        if (Should-Repair) {
            $null = Run 'tailscale up' {
                Start-Process -FilePath $ts -ArgumentList @('up') -WindowStyle Hidden | Out-Null
            }
            $null = Record-Failure
        }
    } else {
        $funnelText = (& $ts funnel status --json 2>$null | Out-String)
        $funnel = $null
        try { $funnel = $funnelText | ConvertFrom-Json } catch { }
        $ourRoute = $false
        if ($funnel -and $funnel.Web) {
            foreach ($site in @($funnel.Web.PSObject.Properties)) {
                foreach ($h in @($site.Value.Handlers.PSObject.Properties)) {
                    if ([string]$h.Value.Proxy -eq ('http://127.0.0.1:' + $gatewayPort)) { $ourRoute = $true }
                }
            }
        }
        if (-not $ourRoute) {
            if (Should-Repair) {
                $null = Run 'restore funnel' {
                    & $ts funnel --bg --https=443 ('http://127.0.0.1:' + $gatewayPort) *> $null
                }
            }
        }
    }
}

if ($Once) { exit 0 }
while ($true) {
    Start-Sleep -Seconds 60
    # Re-execute this script once per minute. Keeping the loop in a fresh
    # process avoids a long-lived PowerShell holding stale state.
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File $PSCommandPath -StateRoot $StateRoot *> $null
    exit 0
}
