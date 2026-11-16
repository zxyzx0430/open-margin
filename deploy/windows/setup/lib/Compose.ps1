# Open Margin setup engine: secrets, Docker Compose, health waits.
# Windows PowerShell 5.1 compatible.

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

function Get-OmEnvValueFromFile {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Path
    )
    if (-not (Test-Path $Path)) { return $null }
    $line = Select-String -Path $Path -Pattern ('^' + [regex]::Escape($Name) + '=') | Select-Object -First 1
    if (-not $line) { return $null }
    return $line.Line.Substring($Name.Length + 1).Trim()
}

function Set-OmEnvValueInFile {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Value,
        [Parameter(Mandatory = $true)][string]$Path
    )
    if (-not (Test-Path $Path)) { throw ('env file not found: ' + $Path) }
    $lines = [System.IO.File]::ReadAllLines($Path)
    $found = $false
    for ($i = 0; $i -lt $lines.Length; $i++) {
        if ($lines[$i] -like ($Name + '=*')) {
            $lines[$i] = $Name + '=' + $Value
            $found = $true
        }
    }
    if (-not $found) { $lines += ($Name + '=' + $Value) }
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, (($lines -join [Environment]::NewLine) + [Environment]::NewLine), $utf8)
}

function Get-OmDeviceTokenFromFile {
    param([string]$Path = (Get-OmBackendEnvPath))
    return (Get-OmEnvValueFromFile -Name 'FINANCE_DEVICE_TOKEN' -Path $Path)
}

function Set-OmDeviceTokenInFile {
    param(
        [Parameter(Mandatory = $true)][string]$Token,
        [string]$Path = (Get-OmBackendEnvPath)
    )
    Set-OmEnvValueInFile -Name 'FINANCE_DEVICE_TOKEN' -Value $Token -Path $Path
}

function New-OmSecretValue {
    $bytes = New-Object byte[] 48
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function Set-OmFileAcl {
    param([Parameter(Mandatory = $true)][string]$Path)
    try {
        & icacls.exe $Path /inheritance:r /grant:r ("$env:USERNAME:(OI)(CI)F") /grant:r 'SYSTEM:(OI)(CI)F' /grant:r 'Administrators:(OI)(CI)F' *> $null
    } catch { }
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
        Set-OmFileAcl -Path $secrets
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
    return [pscustomobject]@{ created = $created; secretsPath = $secrets; backendEnv = $backend; ledgerEnv = $ledger; envFile = $envFile }
}

function Invoke-OmDockerCompose {
    param(
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [string]$ProjectName = 'personal-finance-system',
        [string]$RepoRoot = (Get-OmRepoRoot),
        [int]$TimeoutSec = 0
    )
    $docker = Get-OmCommandPath 'docker'
    if (-not $docker) {
        return [pscustomobject]@{ exitCode = -1; output = 'docker not found' }
    }
    $composeFile = Join-Path $RepoRoot 'compose.yaml'
    if (-not (Test-Path $composeFile)) {
        return [pscustomobject]@{ exitCode = -1; output = ('compose file not found: ' + $composeFile) }
    }
    $allArgs = @('compose', '--project-name', $ProjectName, '--project-directory', $RepoRoot, '-f', $composeFile) + $Arguments
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
    $candidates = @((Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'))
    $programFilesX86 = [Environment]::GetEnvironmentVariable('ProgramFiles(x86)')
    if ($programFilesX86) {
        $candidates += (Join-Path $programFilesX86 'Docker\Docker\Docker Desktop.exe')
    }
    foreach ($candidate in ($candidates | Select-Object -Unique)) {
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
        [switch]$SkipBuild,
        [switch]$NoBuild,
        [switch]$SkipHealth
    )
    $null = New-OmSecrets -RepoRoot $RepoRoot -Ports $Ports
    $composeArgs = @('--profile', 'windows-public-sync', 'up', '-d')
    if ($NoBuild) {
        # Offline bundle path: images were loaded with `docker load` and must
        # not be rebuilt from a possibly incomplete source tree.
        $composeArgs += '--no-build'
    } elseif (-not $SkipBuild) {
        $composeArgs += '--build'
    }
    $result = Invoke-OmDockerCompose -Arguments $composeArgs -ProjectName $ProjectName -RepoRoot $RepoRoot
    if ($result.exitCode -ne 0) {
        return [pscustomobject]@{ ok = $false; output = $result.output; step = 'compose' }
    }
    $healthUrl = 'http://127.0.0.1:' + $Ports.api + '/health'
    if ($SkipHealth) {
        return [pscustomobject]@{ ok = $true; output = $result.output; step = 'compose'; healthUrl = $healthUrl }
    }
    $healthy = Wait-OmHttpOk -Url $healthUrl -TimeoutSec 240
    if (-not $healthy) {
        return [pscustomobject]@{ ok = $false; output = $result.output; step = 'health'; healthUrl = $healthUrl }
    }
    $gatewayUrl = 'http://127.0.0.1:' + $Ports.gateway + '/finance/health'
    $gatewayHealthy = Wait-OmHttpOk -Url $gatewayUrl -TimeoutSec 120
    if (-not $gatewayHealthy) {
        return [pscustomobject]@{ ok = $false; output = $result.output; step = 'gateway'; healthUrl = $gatewayUrl }
    }
    return [pscustomobject]@{ ok = $true; output = $result.output; step = 'compose'; healthUrl = $healthUrl; gatewayUrl = $gatewayUrl }
}

function Invoke-OmComposeDown {
    param(
        [string]$ProjectName = 'personal-finance-system',
        [string]$RepoRoot = (Get-OmRepoRoot),
        [switch]$RemoveVolumes
    )
    $composeArgs = @('--profile', 'windows-public-sync', 'down')
    if ($RemoveVolumes) { $composeArgs += '-v' }
    return Invoke-OmDockerCompose -Arguments $composeArgs -ProjectName $ProjectName -RepoRoot $RepoRoot
}

function Get-OmComposePs {
    param(
        [string]$ProjectName = 'personal-finance-system',
        [string]$RepoRoot = (Get-OmRepoRoot)
    )
    $result = Invoke-OmDockerCompose -Arguments @('--profile', 'windows-public-sync', 'ps', '--format', 'json') -ProjectName $ProjectName -RepoRoot $RepoRoot
    $items = [System.Collections.Generic.List[object]]::new()
    foreach ($line in (([string]$result.output) -split "`r?`n")) {
        $trimmed = $line.Trim()
        if (-not $trimmed) { continue }
        try { $items.Add(($trimmed | ConvertFrom-Json)) } catch { }
    }
    return [pscustomobject]@{ exitCode = $result.exitCode; items = $items.ToArray(); raw = $result.output }
}

function Restart-OmFinanceApi {
    param(
        [string]$ProjectName = 'personal-finance-system',
        [string]$RepoRoot = (Get-OmRepoRoot)
    )
    return Invoke-OmDockerCompose -Arguments @('--profile', 'windows-public-sync', 'up', '-d', '--force-recreate', 'finance-api') -ProjectName $ProjectName -RepoRoot $RepoRoot
}

function Invoke-OmComposeBackup {
    param(
        [string]$ProjectName = 'personal-finance-system',
        [string]$RepoRoot = (Get-OmRepoRoot)
    )
    return Invoke-OmDockerCompose -Arguments @('--profile', 'maintenance', 'run', '--rm', 'backup') -ProjectName $ProjectName -RepoRoot $RepoRoot
}
