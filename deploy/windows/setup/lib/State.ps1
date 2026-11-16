# Open Margin setup engine: state file and step machine.
# State lives under the current user's LocalAppData so an elevated helper
# never has to write into a user profile it does not own.

$script:OmStateRoot = $null
$script:OmStatePath = $null

function Initialize-OmPaths {
    param(
        [string]$StateRoot = (Join-Path $env:LOCALAPPDATA 'OpenMargin'),
        [string]$RepoRoot = $null
    )
    if (-not $StateRoot) { throw 'StateRoot is required' }
    if (-not (Test-Path $StateRoot)) { New-Item -ItemType Directory -Force -Path $StateRoot | Out-Null }
    $script:OmStateRoot = $StateRoot
    $script:OmStatePath = Join-Path $StateRoot 'setup-state.json'
    foreach ($sub in @('logs', 'jobs', 'watchdog', 'backups')) {
        $p = Join-Path $StateRoot $sub
        if (-not (Test-Path $p)) { New-Item -ItemType Directory -Force -Path $p | Out-Null }
    }
    Initialize-OmLog (Join-Path $StateRoot 'logs')
    if ($RepoRoot) { $script:OmRepoRoot = $RepoRoot }
}

function Get-OmStateRoot { return $script:OmStateRoot }
function Get-OmStatePath { return $script:OmStatePath }

function New-OmDefaultState {
    param([string]$RepoRoot = $script:OmRepoRoot)
    return [pscustomobject]@{
        schema             = 1
        engineVersion      = '1.2.5'
        appRoot            = $RepoRoot
        mode               = 'remote_sync'
        step               = 'UNINITIALIZED'
        progress           = $null
        ports              = [pscustomobject]@{
            api     = 8767
            wallos  = 18082
            gateway = 18768
            setup   = 18770
        }
        financePublicUrl   = 'https://local.openmargin.invalid/finance'
        publicSyncUrl      = $null
        gatewayProfile     = 'windows-public-sync'
        ownerCreated       = $false
        pendingReboot      = $false
        phoneVerified      = $false
        watchdogPaused     = $false
        lastCheck          = $null
        lastError          = $null
        jobId              = $null
        updatedAt          = (Get-Date).ToString('o')
    }
}

function Read-OmState {
    if (-not $script:OmStatePath) { throw 'Initialize-OmPaths must be called first' }
    if (-not (Test-Path $script:OmStatePath)) {
        return New-OmDefaultState
    }
    try {
        $raw = Get-Content -Path $script:OmStatePath -Raw -Encoding UTF8
        if ([string]::IsNullOrWhiteSpace($raw)) { return New-OmDefaultState }
        $state = $raw | ConvertFrom-Json
        if (-not $state.PSObject.Properties['schema']) { throw 'missing schema' }
        return $state
    } catch {
        $bad = $script:OmStatePath + '.corrupt-' + (Get-Date -Format 'yyyyMMddHHmmss')
        try { Move-Item -Force $script:OmStatePath $bad } catch { }
        Write-OmLog -Level WARN -Message ("state file corrupt, moved to " + $bad)
        return New-OmDefaultState
    }
}

function Save-OmState {
    param([Parameter(Mandatory = $true)]$State)
    if (-not $script:OmStatePath) { throw 'Initialize-OmPaths must be called first' }
    $State.updatedAt = (Get-Date).ToString('o')
    $json = $State | ConvertTo-Json -Depth 12
    $tmp = $script:OmStatePath + '.tmp'
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($tmp, $json, $utf8)
    Move-Item -Force $tmp $script:OmStatePath
}

function Set-OmStateValue {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [AllowNull()]$Value
    )
    $state = Read-OmState
    if ($state.PSObject.Properties[$Name]) {
        $state.$Name = $Value
    } else {
        $state | Add-Member -NotePropertyName $Name -NotePropertyValue $Value
    }
    Save-OmState $state
    return $state
}

function Set-OmStep {
    param(
        [Parameter(Mandatory = $true)][string]$Step,
        [AllowNull()]$ErrorInfo = $null
    )
    $state = Read-OmState
    $state.step = $Step
    if ($null -eq $ErrorInfo) {
        $state.lastError = $null
    } else {
        $state.lastError = $ErrorInfo
    }
    Save-OmState $state
    Write-OmLog -Level INFO -Message ("step -> " + $Step)
    return $state
}

function Test-OmStateDrift {
    param($State = (Read-OmState))
    $issues = @()
    if ($State.step -eq 'COMPLETE') {
        if (-not $State.publicSyncUrl) { $issues += 'complete state has no publicSyncUrl' }
        if (-not (Test-Path (Join-Path $script:OmRepoRoot 'secrets\backend.env'))) {
            $issues += 'complete state but secrets/backend.env is missing'
        }
    }
    return $issues
}

function Enter-OmStateLock {
    $mutex = New-Object System.Threading.Mutex($false, 'Global\OpenMarginSetupState')
    try {
        $null = $mutex.WaitOne(5000)
    } catch [System.Threading.AbandonedMutexException] {
        # Previous process died while holding the lock; continue.
    }
    return $mutex
}

function Exit-OmStateLock {
    param($Mutex)
    if ($null -ne $Mutex) {
        try { $Mutex.ReleaseMutex() } catch { }
        try { $Mutex.Dispose() } catch { }
    }
}
