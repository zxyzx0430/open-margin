# Open Margin setup engine: watchdog registration.

function Get-OmWatchdogDirectory {
    param([string]$StateRoot = (Get-OmStateRoot))
    return (Join-Path $StateRoot 'watchdog')
}

function Install-OmWatchdog {
    param(
        [string]$StateRoot = (Get-OmStateRoot),
        [string]$RepoRoot = (Get-OmRepoRoot),
        $Ports = (Read-OmState).ports,
        [string]$ProjectName = 'personal-finance-system',
        [string]$TaskName = 'OpenMarginWatchdog',
        [switch]$DryRun
    )
    if (-not $StateRoot) { throw 'StateRoot is required' }
    $dir = Get-OmWatchdogDirectory -StateRoot $StateRoot
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    $source = Join-Path $script:OmSetupRoot 'Watchdog.ps1'
    if (-not (Test-Path $source)) { throw ('watchdog source missing: ' + $source) }
    $target = Join-Path $dir 'Watchdog.ps1'
    Copy-Item -Force $source $target
    $stateFile = Join-Path $dir 'watchdog-config.json'
    $config = [ordered]@{
        stateRoot = $StateRoot
        repoRoot = $RepoRoot
        workingDirectory = $RepoRoot
        ports = $Ports
        projectName = $ProjectName
    }
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($stateFile, ($config | ConvertTo-Json -Depth 6), $utf8)
    $vbs = Join-Path $dir 'Watchdog.vbs'
    $vbsRoot = ([string]$RepoRoot).Replace('"', '""')
    $vbsStateRoot = ([string]$StateRoot).Replace('"', '""')
    $vbsContent = @"
Set sh = CreateObject("WScript.Shell")
sh.CurrentDirectory = "$vbsRoot"
sh.Run "powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""$target"" -StateRoot ""$vbsStateRoot""", 0, False
"@
    [System.IO.File]::WriteAllText($vbs, $vbsContent, [System.Text.Encoding]::Unicode)
    if ($DryRun) {
        return [pscustomobject]@{ ok = $true; code = 'DRY_RUN'; task = $TaskName; script = $target }
    }
    try {
        $action = New-ScheduledTaskAction -Execute 'wscript.exe' -Argument ('"' + $vbs + '"') -WorkingDirectory $RepoRoot
        $trigger = New-ScheduledTaskTrigger -AtLogOn
        $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) -Hidden
        $taskUser = $env:OPENMARGIN_ORIGINAL_USER_SID
        if (-not $taskUser) { $taskUser = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value }
        $principal = New-ScheduledTaskPrincipal -UserId $taskUser -LogonType Interactive -RunLevel Limited
        Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Force | Out-Null
        return [pscustomobject]@{ ok = $true; code = 'WATCHDOG_INSTALLED'; task = $TaskName; script = $target }
    } catch {
        return [pscustomobject]@{ ok = $false; code = 'WATCHDOG_INSTALL_FAILED'; message = $_.Exception.Message }
    }
}

function Uninstall-OmWatchdog {
    param(
        [string]$TaskName = 'OpenMarginWatchdog',
        [string]$StateRoot = (Get-OmStateRoot),
        [switch]$DryRun
    )
    if ($DryRun) {
        return [pscustomobject]@{ ok = $true; code = 'DRY_RUN'; task = $TaskName }
    }
    try {
        $task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
        if ($task) { Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false }
        $dir = Get-OmWatchdogDirectory -StateRoot $StateRoot
        if (Test-Path $dir) { Remove-Item -Recurse -Force $dir }
        return [pscustomobject]@{ ok = $true; code = 'WATCHDOG_REMOVED' }
    } catch {
        return [pscustomobject]@{ ok = $false; code = 'WATCHDOG_REMOVE_FAILED'; message = $_.Exception.Message }
    }
}

function Set-OmWatchdogPaused {
    param(
        [Parameter(Mandatory = $true)][bool]$Paused,
        [int]$Minutes = 0
    )
    $state = Read-OmState
    $state.watchdogPaused = $Paused
    $state | Add-Member -NotePropertyName watchdogPausedUntil -NotePropertyValue (
        $(if ($Paused -and $Minutes -gt 0) { (Get-Date).AddMinutes($Minutes).ToString('o') } else { $null })
    ) -Force
    Save-OmState $state
    return [pscustomobject]@{ ok = $true; paused = $Paused; until = $state.watchdogPausedUntil }
}
