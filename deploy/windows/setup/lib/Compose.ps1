# Open Margin setup engine: secrets, Docker Compose, health waits.

function Get-OmRepoRoot {
    if ($script:OmRepoRoot) { return $script:OmRepoRoot }
    $candidate = Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')
    $script:OmRepoRoot = $candidate.Path
    return $script:OmRepoRoot
}

function Get-OmSecretsPath {
    return (Join-Path (Get-OmRepoRoot) 'secrets')
}

function Get-OmBackendEnvPath {
    return (Join-Path (Get-OmSecretsPath) 'backend.env')
}

function Get-OmDeviceTokenFromFile {
    param([string]$Path = (Get-OmBackendEnvPath))
    if (-not (Test-Path $Path)) { return $null }
    $line = Select-String -Path $Path -Pattern '^FINANCE_DEVICE_TOKEN=' | Select-Object -First 1
    if (-not $line) { return $null }
    return $line.Line.Substring('FINANCE_DEVICE_TOKEN='.Length).Trim()
}

function Set-OmDeviceTokenInFile {
    param(
        [Parameter(Mandatory = $true)][string]$Token,
        [string]$Path = (Get-OmBackendEnvPath)
    )
    if (-not (Test-Path $Path)) { throw 'backend.env not found' }
    $lines = [System.IO.File]::ReadAllLines($Path)
    $found = $false
    for ($i = 0; $i -lt $lines.Length; $i++) {
        if ($lines[$i] -like 'FINANCE_DEVICE_TOKEN=*') {
            $lines[$i] = 'FINANCE_DEVICE_TOKEN=' + $Token
            $found = $true
        }
    }
    if (-not $found) { $lines += ('FINANCE_DEVICE_TOKEN=' + $Token) }
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, (($lines -join [Environment]::NewLine) + [Environment]::NewLine), $utf8)
}

function New-OmSecretValue {
    $bytes = New-Object byte[] 48
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function New-OmSecrets {
    param(
        [string]$RepoRoot = (Get-OmRepoRoot),
        [string]$FinancePublicUrl = 'https://local.openmargin.invalid/finance',
        [string]$SyncPublicUrl = '',
        $Ports = (Get-OmDefaultPorts),
        [string]$Timezone = 'Asia/Shanghai',
        [int]$OwnerId = 1
    )
    $secrets = Join-Path $RepoRoot 'secrets'
    if (-not (Test-Path $secrets)) { New-Item -ItemType Directory -Force -Path $secrets | Out-Null }
    $backend = Join-Path $secrets 'backend.env'
    $ledger = Join-Path $secrets 'wallos-ledger.env'
    if ((Test-Path $backend) -xor (Test-Path $ledger)) {
        throw 'Only one secret file exists. Stop to avoid overwriting existing data.'
    }
    $created = $false
    if (-not (Test-Path $backend)) {
        $device = New-OmSecretValue
        $admin = New-OmSecretValue
        $ledgerToken = New-OmSecretValue
        $nl = [Environment]::NewLine
        $be = @(
            'FINANCE_PUBLIC_URL=' + $FinancePublicUrl.TrimEnd('/')
            'FINANCE_SYNC_PUBLIC_URL=' + $SyncPublicUrl.TrimEnd('/')
            'FINANCE_DEVICE_TOKEN=' + $device
            'FINANCE_ADMIN_TOKEN=' + $admin
            'FINANCE_WALLOS_LEDGER_TOKEN=' + $ledgerToken
            'FINANCE_WALLOS_OWNER_ID=' + $OwnerId
            'FINANCE_TIMEZONE=' + $Timezone
            'FINANCE_OAUTH_CALLBACK_HOSTS=chatgpt.com,chat.openai.com'
        ) -join $nl
        $wl = @(
            'FINANCE_WALLOS_LEDGER_TOKEN=' + $ledgerToken
            'FINANCE_LEDGER_API_URL=http://finance-api:8767/api/v1/wallos-ledger'
            'FINANCE_WALLOS_OWNER_ID=' + $OwnerId
            'FINANCE_TIMEZONE=' + $Timezone
        ) -join $nl
        $utf8 = New-Object System.Text.UTF8Encoding($false)
        [System.IO.File]::WriteAllText($backend, $be + $nl, $utf8)
        [System.IO.File]::WriteAllText($ledger, $wl + $nl, $utf8)
        $created = $true
    }
    $envFile = Join-Path $RepoRoot '.env'
    $envLines = @(
        'FINANCE_API_PORT=' + $Ports.api
        'WALLOS_PORT=' + $Ports.wallos
        'GATEWAY_PORT=' + $Ports.gateway
        'FINANCE_TIMEZONE=' + $Timezone
    ) -join [Environment]::NewLine
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($envFile, $envLines + [Environment]::NewLine, $utf8)
    try {
        & icacls.exe $secrets /inheritance:r /grant:r ("$env:USERNAME:(OI)(CI)F") /grant:r 'SYSTEM:(OI)(CI)F' /grant:r 'Administrators:(OI)(CI)F' *> $null
    } catch { }
    return [pscustomobject]@{ created = $created; secretsPath = $secrets; backendEnv = $backend; ledgerEnv = $ledger }
}

function Invoke-OmDockerCompose {
    param(
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [string]$ProjectName = 'personal-finance-system',
        [int]$TimeoutSec = 0
    )
    $docker = Get-OmCommandPath 'docker'
    if (-not $docker) {
        return [pscustomobject]@{ exitCode = -1; output = 'docker not found' }
    }
    $allArgs = @('compose', '--project-name', $ProjectName) + $Arguments
    Write-OmLog -Level INFO -Message ('docker ' + ($allArgs -join ' '))
    try {
        $output = & $docker @allArgs 2>&1 | Out-String
        return [pscustomobject]@{ exitCode = $LASTEXITCODE; output = $output }
    } catch {
        return [pscustomobject]@{ exitCode = -1; output = $_.Exception.Message }
    }
}

function Wait-OmDockerReady {
    param([int]$TimeoutSec = 180)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    do {
        $info = Get-OmDockerInfo
        if ($info.running) { return $true }
        Start-Sleep -Seconds 3
    } while ((Get-Date) -lt $deadline)
    return $false
}

function Start-OmDockerDesktop {
    $candidates = @(
        (Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'),
        (Join-Path ${env:ProgramFiles(x86)} 'Docker\Docker\Docker Desktop.exe')
    )
    foreach ($candidate in $candidates) {
        if (Test-Path $candidate) {
            Write-OmLog -Level INFO -Message ('starting Docker Desktop: ' + $candidate)
            Start-Process -FilePath $candidate -WindowStyle Hidden | Out-Null
            return $true
        }
    }
    return $false
}

function Wait-OmHttpOk {
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [int]$TimeoutSec = 120,
        [int[]]$AcceptStatus = @(200)
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    do {
        try {
            $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5 -MaximumRedirection 2 -ErrorAction Stop
            if ($AcceptStatus -contains [int]$response.StatusCode) { return $true }
        } catch {
            if ($_.Exception.Response) {
                try {
                    if ($AcceptStatus -contains [int]$_.Exception.Response.StatusCode) { return $true }
                } catch { }
            }
        }
        Start-Sleep -Seconds 3
    } while ((Get-Date) -lt $deadline)
    return $false
}

function Invoke-OmComposeUp {
    param(
        [string]$RepoRoot = (Get-OmRepoRoot),
        $Ports = (Get-OmDefaultPorts),
        [string]$ProjectName = 'personal-finance-system',
        [switch]$SkipBuild
    )
    $null = New-OmSecrets -RepoRoot $RepoRoot -Ports $Ports
    $args = @('--profile', 'windows-public-sync', 'up', '-d')
    if (-not $SkipBuild) { $args += '--build' }
    $result = Invoke-OmDockerCompose -Arguments $args -ProjectName $ProjectName
    if ($result.exitCode -ne 0) {
        return [pscustomobject]@{ ok = $false; output = $result.output; step = 'compose' }
    }
    $healthUrl = 'http://127.0.0.1:' + $Ports.api + '/health'
    $healthy = Wait-OmHttpOk -Url $healthUrl -TimeoutSec 240
    return [pscustomobject]@{ ok = $healthy; output = $result.output; step = 'health'; healthUrl = $healthUrl }
}

function Invoke-OmComposeDown {
    param(
        [string]$ProjectName = 'personal-finance-system',
        [switch]$RemoveVolumes
    )
    $args = @('down')
    if ($RemoveVolumes) { $args += '-v' }
    return Invoke-OmDockerCompose -Arguments $args -ProjectName $ProjectName
}

function Get-OmComposePs {
    param([string]$ProjectName = 'personal-finance-system')
    return Invoke-OmDockerCompose -Arguments @('ps', '--format', 'json') -ProjectName $ProjectName
}
