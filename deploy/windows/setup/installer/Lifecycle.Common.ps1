# Open Margin installer lifecycle helpers shared by Stop-OpenMargin.ps1 and
# Uninstall-OpenMargin.ps1.
#
# ASCII-only on purpose: Stop-OpenMargin.ps1 and this file are extracted to
# {tmp} by the Inno Setup PrepareToInstall hook before the installed copies have
# been normalized to UTF-8 with BOM. Windows PowerShell 5.1 reads a non-BOM file
# with the ANSI code page, so non-ASCII literals here could be garbled.
#
# All process matching is path-based. Nothing is stopped unless the process
# command line points at a script inside the Open Margin app root or at the
# per-user watchdog directory, so unrelated PowerShell windows are left alone.

function Get-OmLcFullPath {
    param([string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path)) { return '' }
    try {
        return ([System.IO.Path]::GetFullPath($Path)).TrimEnd('\')
    } catch {
        return $Path.TrimEnd('\')
    }
}

function Test-OmLcPathEqual {
    param([string]$Left, [string]$Right)
    if ([string]::IsNullOrEmpty($Left) -or [string]::IsNullOrEmpty($Right)) { return $false }
    return [string]::Equals(
        (Get-OmLcFullPath $Left),
        (Get-OmLcFullPath $Right),
        [System.StringComparison]::OrdinalIgnoreCase
    )
}

function Get-OmLcCommandPath {
    param([Parameter(Mandatory = $true)][string]$Name)
    $cmd = Get-Command $Name -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}

function Protect-OmLcText {
    param([AllowNull()][string]$Text, [int]$MaxLength = 600)
    if ([string]::IsNullOrEmpty($Text)) { return $Text }
    $safe = $Text
    $safe = [regex]::Replace($safe, '(?i)(token|password|secret)\s*[=:]\s*\S+', '$1=***')
    # Mask long unbroken token-like words, but not inside a Windows path or a
    # command line that contains one: path segments can exceed 40 characters
    # and must stay readable in the uninstall summary.
    if ($safe -notmatch '[A-Za-z]:\\' -and $safe -notmatch '\\\\') {
        $safe = [regex]::Replace($safe, '\b[A-Za-z0-9_-]{40,}\b', '***')
    }
    if ($MaxLength -gt 0 -and $safe.Length -gt $MaxLength) {
        $safe = $safe.Substring(0, $MaxLength) + '...'
    }
    return $safe
}

function New-OmLcAction {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Status,
        [string]$Detail = ''
    )
    return [pscustomobject]@{
        name = $Name
        status = $Status
        detail = (Protect-OmLcText -Text $Detail)
    }
}

function Get-OmLcProcesses {
    try {
        return @(Get-CimInstance -ClassName Win32_Process -ErrorAction Stop)
    } catch {
        return @()
    }
}

function Get-OmLcWizardScriptPath {
    param([Parameter(Mandatory = $true)][string]$AppRoot)
    return (Get-OmLcFullPath (Join-Path $AppRoot 'deploy\windows\setup\Start-OpenMarginSetupWeb.ps1'))
}

function Get-OmLcWizardProcesses {
    param(
        [Parameter(Mandatory = $true)][string]$AppRoot,
        $Processes = $null
    )
    if ($null -eq $Processes) { $Processes = Get-OmLcProcesses }
    $expected = (Get-OmLcWizardScriptPath -AppRoot $AppRoot).ToLowerInvariant()
    $result = [System.Collections.Generic.List[object]]::new()
    foreach ($process in @($Processes)) {
        if ($null -eq $process) { continue }
        $name = ([string]$process.Name).ToLowerInvariant()
        if ($name -ne 'powershell.exe' -and $name -ne 'pwsh.exe') { continue }
        $commandLine = [string]$process.CommandLine
        if ([string]::IsNullOrWhiteSpace($commandLine)) { continue }
        if ($commandLine.ToLowerInvariant().Contains($expected)) {
            $result.Add($process)
        }
    }
    return @($result)
}

function Get-OmLcWizardState {
    param([Parameter(Mandatory = $true)][string]$StateRoot)
    $path = Join-Path $StateRoot 'setup-web.json'
    if (-not (Test-Path $path)) { return $null }
    try {
        $value = Get-Content -Path $path -Raw -Encoding UTF8 -ErrorAction Stop | ConvertFrom-Json
        return [pscustomobject]@{
            path      = $path
            pid       = $(if ($value.PSObject.Properties['pid']) { [int]$value.pid } else { $null })
            port      = $(if ($value.PSObject.Properties['port']) { [int]$value.port } else { $null })
            url       = $(if ($value.PSObject.Properties['url']) { [string]$value.url } else { $null })
            startedAt = $(if ($value.PSObject.Properties['startedAt']) { [string]$value.startedAt } else { $null })
            corrupt   = $false
        }
    } catch {
        return [pscustomobject]@{
            path      = $path
            pid       = $null
            port      = $null
            url       = $null
            startedAt = $null
            corrupt   = $true
        }
    }
}

function Get-OmLcWizardStatus {
    param(
        [Parameter(Mandatory = $true)][string]$AppRoot,
        [Parameter(Mandatory = $true)][string]$StateRoot,
        $Processes = $null
    )
    if ($null -eq $Processes) { $Processes = Get-OmLcProcesses }
    $state = Get-OmLcWizardState -StateRoot $StateRoot
    $wizard = @(Get-OmLcWizardProcesses -AppRoot $AppRoot -Processes $Processes)
    $statePidAlive = $false
    $stateMatches = $false
    if ($null -ne $state -and $state.pid) {
        try {
            $statePidAlive = ($null -ne (Get-Process -Id ([int]$state.pid) -ErrorAction SilentlyContinue))
        } catch {
            $statePidAlive = $false
        }
        foreach ($process in $wizard) {
            if ([int]$process.ProcessId -eq [int]$state.pid) { $stateMatches = $true }
        }
    }
    $pids = [System.Collections.Generic.List[int]]::new()
    foreach ($process in $wizard) { $pids.Add([int]$process.ProcessId) }
    return [pscustomobject]@{
        running       = ($wizard.Count -gt 0)
        pids          = @($pids.ToArray())
        state         = $state
        statePidAlive = $statePidAlive
        stateMatches  = $stateMatches
        url           = $(if ($null -ne $state) { $state.url } else { $null })
        port          = $(if ($null -ne $state) { $state.port } else { $null })
    }
}

function Get-OmLcWatchdogProcesses {
    param(
        [Parameter(Mandatory = $true)][string]$StateRoot,
        [Parameter(Mandatory = $true)][string]$AppRoot,
        $Processes = $null
    )
    if ($null -eq $Processes) { $Processes = Get-OmLcProcesses }
    $oursDir = Get-OmLcFullPath (Join-Path $StateRoot 'watchdog')
    $result = [System.Collections.Generic.List[object]]::new()
    foreach ($process in @($Processes)) {
        if ($null -eq $process) { continue }
        $name = ([string]$process.Name).ToLowerInvariant()
        if ($name -ne 'powershell.exe' -and $name -ne 'pwsh.exe' -and $name -ne 'wscript.exe') { continue }
        $commandLine = [string]$process.CommandLine
        if ([string]::IsNullOrWhiteSpace($commandLine)) { continue }
        if ($commandLine -notmatch '(?i)[\\/]OpenMargin[\\/]watchdog[\\/]Watchdog\.(ps1|vbs)') { continue }

        $scriptPath = $null
        if ($commandLine -match '(?i)"([^"]*Watchdog\.(?:ps1|vbs))"') {
            $scriptPath = $matches[1]
        } elseif ($commandLine -match '(?i)([A-Za-z]:\\[^"\s]*Watchdog\.(?:ps1|vbs))') {
            $scriptPath = $matches[1]
        }

        if ($scriptPath) {
            $dir = Get-OmLcFullPath (Split-Path -Parent $scriptPath)
            $configPath = Join-Path $dir 'watchdog-config.json'
            $belongs = $false
            $decided = $false
            if (Test-Path $configPath) {
                try {
                    $config = Get-Content -Path $configPath -Raw -Encoding UTF8 -ErrorAction Stop | ConvertFrom-Json
                    if ($config.PSObject.Properties['repoRoot'] -and $config.repoRoot) {
                        $decided = $true
                        if (Test-OmLcPathEqual $config.repoRoot $AppRoot) { $belongs = $true }
                    }
                } catch { }
            }
            if (-not $decided -and (Test-OmLcPathEqual $dir $oursDir)) {
                $belongs = $true
            }
            if ($belongs) { $result.Add($process) }
        } elseif ($commandLine.ToLowerInvariant().Contains($oursDir.ToLowerInvariant())) {
            $result.Add($process)
        }
    }
    return @($result)
}

function Stop-OmLcProcessSet {
    param(
        $Processes,
        [Parameter(Mandatory = $true)][string]$Label,
        [switch]$DryRun
    )
    $actions = [System.Collections.Generic.List[object]]::new()
    foreach ($process in @($Processes)) {
        if ($null -eq $process) { continue }
        $processId = [int]$process.ProcessId
        $actionName = ($Label + ' pid ' + $processId)
        if ($DryRun) {
            $actions.Add((New-OmLcAction -Name $actionName -Status 'planned' -Detail 'Stop-Process -Force'))
            continue
        }
        try {
            Stop-Process -Id $processId -Force -ErrorAction Stop
            try { Wait-Process -Id $processId -Timeout 5 -ErrorAction SilentlyContinue } catch { }
            $actions.Add((New-OmLcAction -Name $actionName -Status 'done' -Detail 'stopped'))
        } catch {
            $actions.Add((New-OmLcAction -Name $actionName -Status 'failed' -Detail $_.Exception.Message))
        }
    }
    return @($actions)
}

function Get-OmLcScheduledTaskInfo {
    param([string]$TaskName = 'OpenMarginWatchdog')
    try {
        $task = Get-ScheduledTask -TaskName $TaskName -ErrorAction Stop
        return [pscustomobject]@{ exists = $true; state = [string]$task.State; task = $task }
    } catch {
        return [pscustomobject]@{ exists = $false; state = $null; task = $null }
    }
}

function Stop-OmLcScheduledTask {
    param(
        [string]$TaskName = 'OpenMarginWatchdog',
        [switch]$DryRun
    )
    $info = Get-OmLcScheduledTaskInfo -TaskName $TaskName
    if (-not $info.exists) {
        return (New-OmLcAction -Name ('watchdog task ' + $TaskName) -Status 'not_found')
    }
    if ($info.state -ne 'Running') {
        return (New-OmLcAction -Name ('watchdog task ' + $TaskName) -Status 'skipped' -Detail ('state ' + $info.state))
    }
    if ($DryRun) {
        return (New-OmLcAction -Name ('watchdog task ' + $TaskName) -Status 'planned' -Detail 'Stop-ScheduledTask')
    }
    try {
        Stop-ScheduledTask -TaskName $TaskName -ErrorAction Stop
        return (New-OmLcAction -Name ('watchdog task ' + $TaskName) -Status 'done')
    } catch {
        return (New-OmLcAction -Name ('watchdog task ' + $TaskName) -Status 'failed' -Detail $_.Exception.Message)
    }
}

function Remove-OmLcScheduledTask {
    param(
        [string]$TaskName = 'OpenMarginWatchdog',
        [switch]$DryRun
    )
    $info = Get-OmLcScheduledTaskInfo -TaskName $TaskName
    if (-not $info.exists) {
        return (New-OmLcAction -Name ('watchdog task ' + $TaskName) -Status 'not_found')
    }
    if ($DryRun) {
        return (New-OmLcAction -Name ('watchdog task ' + $TaskName) -Status 'planned' -Detail 'Unregister-ScheduledTask')
    }
    try {
        Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction Stop
        return (New-OmLcAction -Name ('watchdog task ' + $TaskName) -Status 'done' -Detail 'unregistered')
    } catch {
        return (New-OmLcAction -Name ('watchdog task ' + $TaskName) -Status 'failed' -Detail $_.Exception.Message)
    }
}

function Get-OmLcComposeDownArguments {
    param(
        [Parameter(Mandatory = $true)][string]$AppRoot,
        [string]$ProjectName = 'personal-finance-system'
    )
    $composeFile = Join-Path $AppRoot 'compose.yaml'
    # No -v / --volumes and no --rmi: Docker volumes and built images are
    # deliberately preserved across upgrade and uninstall.
    return @(
        'compose',
        '--project-name', $ProjectName,
        '--project-directory', $AppRoot,
        '-f', $composeFile,
        'down'
    )
}

function Invoke-OmLcComposeDown {
    param(
        [Parameter(Mandatory = $true)][string]$AppRoot,
        [string]$ProjectName = 'personal-finance-system',
        [switch]$DryRun
    )
    $composeFile = Join-Path $AppRoot 'compose.yaml'
    if (-not (Test-Path $composeFile)) {
        return (New-OmLcAction -Name 'compose down' -Status 'skipped' -Detail ('compose file not found: ' + $composeFile))
    }
    $docker = Get-OmLcCommandPath 'docker'
    if (-not $docker) {
        return (New-OmLcAction -Name 'compose down' -Status 'skipped' -Detail 'docker not found')
    }
    $arguments = Get-OmLcComposeDownArguments -AppRoot $AppRoot -ProjectName $ProjectName
    $display = 'docker ' + ($arguments -join ' ')
    if ($DryRun) {
        return (New-OmLcAction -Name 'compose down' -Status 'planned' -Detail $display)
    }
    try {
        $output = & $docker @arguments 2>&1 | Out-String
        if ($LASTEXITCODE -eq 0) {
            return (New-OmLcAction -Name 'compose down' -Status 'done' -Detail ('exit 0; ' + $output))
        }
        return (New-OmLcAction -Name 'compose down' -Status 'failed' -Detail ('exit ' + $LASTEXITCODE + '; ' + $output))
    } catch {
        return (New-OmLcAction -Name 'compose down' -Status 'failed' -Detail $_.Exception.Message)
    }
}

function Write-OmLcActionList {
    param($Actions)
    foreach ($action in @($Actions)) {
        if ($action.detail) {
            Write-Host (('[lifecycle] ' + $action.name + ': ' + $action.status + ' - ' + $action.detail))
        } else {
            Write-Host (('[lifecycle] ' + $action.name + ': ' + $action.status))
        }
    }
}
