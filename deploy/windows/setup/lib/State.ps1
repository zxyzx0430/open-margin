# Open Margin setup engine: state file and step machine.
# State lives under the current user's LocalAppData so an elevated helper
# never has to write into a user profile it does not own.
# Windows PowerShell 5.1 compatible.

$script:OmStateRoot = $null
$script:OmStatePath = $null

# Allowed step transitions. FAILED is reachable from anywhere and can return
# to any recovery step. Same-state writes are idempotent and always allowed.
$script:OmStepTransitions = @{
    'UNINITIALIZED'   = @('CHECKING', 'INSTALLING_DEPS', 'READY', 'FAILED')
    'CHECKING'        = @('NEEDS_USER', 'INSTALLING_DEPS', 'READY', 'FAILED')
    'NEEDS_USER'      = @('CHECKING', 'INSTALLING_DEPS', 'WAITING_REBOOT', 'READY', 'FAILED')
    'INSTALLING_DEPS' = @('WAITING_REBOOT', 'READY', 'CHECKING', 'FAILED')
    'WAITING_REBOOT'  = @('CHECKING', 'INSTALLING_DEPS', 'READY', 'FAILED')
    'READY'           = @('DEPLOYING', 'CHECKING', 'DISABLED', 'FAILED')
    'DEPLOYING'       = @('VERIFYING', 'WAITING_USER', 'READY', 'FAILED')
    'WAITING_USER'    = @('DEPLOYING', 'VERIFYING', 'READY', 'COMPLETE', 'FAILED')
    'VERIFYING'       = @('COMPLETE', 'PHONE_VERIFIED', 'READY', 'FAILED')
    'PHONE_VERIFIED'  = @('COMPLETE', 'DISABLING', 'FAILED')
    'COMPLETE'        = @('DISABLING', 'SWITCHING', 'DEPLOYING', 'VERIFYING', 'FAILED')
    'DISABLING'       = @('DISABLED', 'FAILED')
    'DISABLED'        = @('SWITCHING', 'READY', 'FAILED')
    'SWITCHING'       = @('READY', 'COMPLETE', 'VERIFYING', 'FAILED')
    'FAILED'          = @('CHECKING', 'INSTALLING_DEPS', 'WAITING_REBOOT', 'READY', 'DEPLOYING', 'DISABLING', 'SWITCHING', 'FAILED')
}

function Get-OmStepTransitions {
    return $script:OmStepTransitions
}

function Test-OmStepTransition {
    param(
        [Parameter(Mandatory = $true)][string]$From,
        [Parameter(Mandatory = $true)][string]$To
    )
    if ($From -eq $To) { return $true }
    if (-not $script:OmStepTransitions.ContainsKey($From)) { return $false }
    if (-not $script:OmStepTransitions.ContainsKey($To)) { return $false }
    return ($script:OmStepTransitions[$From] -contains $To)
}

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
        watchdogPausedUntil = $null
        watchdogInstalled  = $false
        lastCheck          = $null
        lastError          = $null
        jobId              = $null
        dryRun             = $false
        updatedAt          = (Get-Date).ToString('o')
    }
}

function Merge-OmStateDefaults {
    param([Parameter(Mandatory = $true)]$State)
    $default = New-OmDefaultState
    foreach ($property in $default.PSObject.Properties) {
        if (-not $State.PSObject.Properties[$property.Name]) {
            $State | Add-Member -NotePropertyName $property.Name -NotePropertyValue $property.Value
        }
    }
    if ($null -eq $State.ports -or -not ($State.ports -is [psobject])) {
        $State.ports = $default.ports
    } else {
        foreach ($portName in @('api', 'wallos', 'gateway', 'setup')) {
            if (-not $State.ports.PSObject.Properties[$portName]) {
                $State.ports | Add-Member -NotePropertyName $portName -NotePropertyValue $default.ports.$portName
            }
        }
    }
    return $State
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
        return (Merge-OmStateDefaults $state)
    } catch {
        $bad = $script:OmStatePath + '.corrupt-' + (Get-Date -Format 'yyyyMMddHHmmss')
        try { Move-Item -Force $script:OmStatePath $bad } catch { }
        Write-OmLog -Level WARN -Message ("state file corrupt, moved to " + $bad)
        return New-OmDefaultState
    }
}

function Save-OmState {
    param(
        [Parameter(Mandatory = $true)]$State,
        [switch]$SkipLock
    )
    if (-not $script:OmStatePath) { throw 'Initialize-OmPaths must be called first' }
    $State = Merge-OmStateDefaults $State
    $State.updatedAt = (Get-Date).ToString('o')
    $json = $State | ConvertTo-Json -Depth 12
    $mutex = $null
    if (-not $SkipLock) { $mutex = Enter-OmStateLock }
    try {
        $tmp = $script:OmStatePath + '.tmp'
        $utf8 = New-Object System.Text.UTF8Encoding($false)
        [System.IO.File]::WriteAllText($tmp, $json, $utf8)
        if (Test-Path $script:OmStatePath) {
            try {
                [System.IO.File]::Replace($tmp, $script:OmStatePath, $null)
            } catch {
                # File.Replace needs both files on the same volume and an
                # NTFS target; fall back to a best-effort move.
                Move-Item -Force $tmp $script:OmStatePath
            }
        } else {
            Move-Item -Force $tmp $script:OmStatePath
        }
    } finally {
        if ($mutex) { Exit-OmStateLock $mutex }
    }
}

function Update-OmState {
    param([Parameter(Mandatory = $true)][scriptblock]$Mutator)
    $mutex = Enter-OmStateLock
    try {
        $state = Read-OmState
        $result = & $Mutator $state
        if ($null -eq $result) { $result = $state }
        Save-OmState -State $result -SkipLock
        return $result
    } finally {
        Exit-OmStateLock $mutex
    }
}

function Set-OmStateValue {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [AllowNull()]$Value
    )
    return Update-OmState ({
        param($state)
        if ($state.PSObject.Properties[$Name]) {
            $state.$Name = $Value
        } else {
            $state | Add-Member -NotePropertyName $Name -NotePropertyValue $Value
        }
        $state
    }.GetNewClosure())
}

function Set-OmStep {
    param(
        [Parameter(Mandatory = $true)][string]$Step,
        [AllowNull()]$ErrorInfo = $null,
        [switch]$Force
    )
    $state = Read-OmState
    $from = [string]$state.step
    if (-not $Force -and -not (Test-OmStepTransition -From $from -To $Step)) {
        throw ("invalid state transition: " + $from + " -> " + $Step)
    }
    $state = Update-OmState ({
        param($s)
        $s.step = $Step
        if ($null -eq $ErrorInfo) { $s.lastError = $null } else { $s.lastError = $ErrorInfo }
        $s | Add-Member -NotePropertyName lastStepChangedAt -NotePropertyValue ((Get-Date).ToString('o')) -Force
        $s
    }.GetNewClosure())
    Write-OmLog -Level INFO -Message ("step -> " + $Step)
    return $state
}

function Set-OmProgress {
    param(
        [Parameter(Mandatory = $true)][string]$Stage,
        [int]$Percent = -1,
        [string]$Message = ''
    )
    $progress = [pscustomobject]@{
        stage     = $Stage
        percent   = $Percent
        message   = $Message
        updatedAt = (Get-Date).ToString('o')
    }
    $null = Set-OmStateValue -Name progress -Value $progress
    return $progress
}

function Test-OmStateDrift {
    param($State = (Read-OmState))
    $issues = @()
    if ($State.step -in @('COMPLETE', 'PHONE_VERIFIED', 'DISABLED', 'SWITCHING')) {
        if ($State.step -ne 'DISABLED' -and -not $State.publicSyncUrl) {
            $issues += 'state has no publicSyncUrl'
        }
        if (-not (Test-Path (Join-Path $script:OmRepoRoot 'secrets\backend.env'))) {
            $issues += 'state but secrets/backend.env is missing'
        }
    }
    return $issues
}

function Enter-OmStateLock {
    try {
        $mutex = New-Object System.Threading.Mutex($false, 'Local\OpenMarginSetupState')
        try {
            $null = $mutex.WaitOne(5000)
        } catch [System.Threading.AbandonedMutexException] {
            # Previous process died while holding the lock; continue.
        }
        return $mutex
    } catch {
        return $null
    }
}

function Exit-OmStateLock {
    param($Mutex)
    if ($null -ne $Mutex) {
        try { $Mutex.ReleaseMutex() } catch { }
        try { $Mutex.Dispose() } catch { }
    }
}
