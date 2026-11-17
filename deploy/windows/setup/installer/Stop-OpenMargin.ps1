#Requires -Version 5.1
<#
.SYNOPSIS
    Stop a running Open Margin install before files are replaced.

.DESCRIPTION
    Used by the Inno Setup PrepareToInstall hook (upgrade/repair) and by the
    Start Menu launcher for single-instance checks. It stops only:
      - the hidden wizard server (Start-OpenMarginSetupWeb.ps1) whose command
        line points at this app root,
      - the per-user watchdog process and scheduled task,
      - the compose project with `docker compose ... down` (never -v).
    Docker volumes, secrets/, .env and user state are not touched.

    The script never calls `exit` so it can be invoked in-process with `&`
    (for example from Launch-Setup.ps1). It always reports a JSON summary when
    -Json is used and otherwise writes human-readable lines.

.PARAMETER Status
    Only report what is running. Nothing is stopped or deleted.

.PARAMETER ForInstall
    Non-interactive mode used by the installer. Equivalent to the default stop
    with no optional scopes; failures are reported but never block setup.

.PARAMETER WizardOnly
    Stop the hidden wizard only. Useful for single-instance decisions.

.PARAMETER IncludeFunnel
    Also remove this project's Tailscale Funnel/Serve route. Off by default:
    uninstall handles routes with the engine module.
#>
param(
    [string]$AppRoot = '',
    [string]$StateRoot = '',
    [string]$ProjectName = 'personal-finance-system',
    [string]$TaskName = 'OpenMarginWatchdog',
    [switch]$ForInstall,
    [switch]$WizardOnly,
    [switch]$SkipWatchdog,
    [switch]$SkipWatchdogTask,
    [switch]$SkipCompose,
    [switch]$IncludeFunnel,
    [switch]$Status,
    [switch]$DryRun,
    [switch]$Json
)

$ErrorActionPreference = 'Continue'
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch { }
. (Join-Path $PSScriptRoot 'Lifecycle.Common.ps1')

if (-not $AppRoot) {
    $candidate = Join-Path $PSScriptRoot '..\..\..\..'
    try { $AppRoot = (Resolve-Path $candidate).Path } catch { $AppRoot = $candidate }
}
if (-not $StateRoot) { $StateRoot = Join-Path $env:LOCALAPPDATA 'OpenMargin' }
$AppRoot = Get-OmLcFullPath $AppRoot
$StateRoot = Get-OmLcFullPath $StateRoot

$processes = Get-OmLcProcesses
$wizardStatus = Get-OmLcWizardStatus -AppRoot $AppRoot -StateRoot $StateRoot -Processes $processes

if ($Status) {
    $watchdogProcesses = @(Get-OmLcWatchdogProcesses -StateRoot $StateRoot -AppRoot $AppRoot -Processes $processes)
    $watchdogPids = [System.Collections.Generic.List[int]]::new()
    foreach ($process in $watchdogProcesses) { $watchdogPids.Add([int]$process.ProcessId) }
    $task = Get-OmLcScheduledTaskInfo -TaskName $TaskName
    $payload = [ordered]@{
        ok            = $true
        status        = 'status'
        appRoot       = $AppRoot
        stateRoot     = $StateRoot
        composeProject = $ProjectName
        wizard        = [ordered]@{
            running       = [bool]$wizardStatus.running
            pids          = @($wizardStatus.pids)
            statePid      = $wizardStatus.state.pid
            statePidAlive = [bool]$wizardStatus.statePidAlive
            stateMatches  = [bool]$wizardStatus.stateMatches
            staleState    = ([bool]($null -ne $wizardStatus.state) -and (-not $wizardStatus.running))
            port          = $wizardStatus.port
            url           = $wizardStatus.url
        }
        watchdog      = [ordered]@{
            processCount = $watchdogProcesses.Count
            processPids  = @($watchdogPids.ToArray())
            taskExists   = [bool]$task.exists
            taskState    = $task.state
        }
    }
    if ($Json) {
        $payload | ConvertTo-Json -Depth 6
    } else {
        Write-Host ('Open Margin lifecycle status')
        Write-Host ('  app root:   ' + $AppRoot)
        Write-Host ('  state root: ' + $StateRoot)
        Write-Host ('  wizard:     running=' + $payload.wizard.running + ' pids=' + (@($payload.wizard.pids) -join ',') + ' statePid=' + $payload.wizard.statePid + ' matches=' + $payload.wizard.stateMatches)
        Write-Host ('  watchdog:   processes=' + $payload.watchdog.processCount + ' task=' + $payload.watchdog.taskState)
    }
    return
}

$actions = [System.Collections.Generic.List[object]]::new()

# 1. Hidden wizard server. Only stop processes whose command line contains the
# exact Start-OpenMarginSetupWeb.ps1 path under this app root.
$wizardProcesses = @(Get-OmLcWizardProcesses -AppRoot $AppRoot -Processes $processes)
if ($wizardProcesses.Count -gt 0) {
    foreach ($action in @(Stop-OmLcProcessSet -Processes $wizardProcesses -Label 'wizard' -DryRun:$DryRun)) {
        $actions.Add($action)
    }
} else {
    $actions.Add((New-OmLcAction -Name 'wizard' -Status 'not_found' -Detail 'no Start-OpenMarginSetupWeb.ps1 process for this app root'))
}

# Stale state file: remove only when no verified wizard process remains and the
# recorded PID does not belong to an unrelated live process.
if ($null -ne $wizardStatus.state) {
    $remaining = @()
    if (-not $DryRun) { $remaining = @(Get-OmLcWizardProcesses -AppRoot $AppRoot) }
    if ($remaining.Count -gt 0) {
        $actions.Add((New-OmLcAction -Name 'wizard state' -Status 'skipped' -Detail 'wizard still running; setup-web.json kept'))
    } elseif ($wizardStatus.statePidAlive -and -not $wizardStatus.stateMatches) {
        # PID reuse: the recorded process is not our wizard. Never kill it, but
        # the state file is stale and must not make Launch-Setup reuse it.
        if ($DryRun) {
            $actions.Add((New-OmLcAction -Name 'wizard state' -Status 'planned' -Detail ('PID belongs to another process; remove stale ' + $wizardStatus.state.path)))
        } else {
            try {
                Remove-Item -Force $wizardStatus.state.path -ErrorAction Stop
                $actions.Add((New-OmLcAction -Name 'wizard state' -Status 'done' -Detail 'PID belongs to another process; removed stale setup-web.json'))
            } catch {
                $actions.Add((New-OmLcAction -Name 'wizard state' -Status 'failed' -Detail $_.Exception.Message))
            }
        }
    } elseif ($DryRun) {
        $actions.Add((New-OmLcAction -Name 'wizard state' -Status 'planned' -Detail ('remove ' + $wizardStatus.state.path)))
    } else {
        try {
            Remove-Item -Force $wizardStatus.state.path -ErrorAction Stop
            $actions.Add((New-OmLcAction -Name 'wizard state' -Status 'done' -Detail 'removed stale setup-web.json'))
        } catch {
            $actions.Add((New-OmLcAction -Name 'wizard state' -Status 'failed' -Detail $_.Exception.Message))
        }
    }
}

if (-not $WizardOnly) {
    # 2. Watchdog process and scheduled task. The task is stopped, not removed;
    # an upgrade keeps the registration so the next logon can start it again.
    if (-not $SkipWatchdog) {
        $watchdogProcesses = @(Get-OmLcWatchdogProcesses -StateRoot $StateRoot -AppRoot $AppRoot)
        if ($watchdogProcesses.Count -gt 0) {
            foreach ($action in @(Stop-OmLcProcessSet -Processes $watchdogProcesses -Label 'watchdog' -DryRun:$DryRun)) {
                $actions.Add($action)
            }
        } else {
            $actions.Add((New-OmLcAction -Name 'watchdog' -Status 'not_found' -Detail 'no watchdog process for this app root'))
        }
        if ($SkipWatchdogTask) {
            $actions.Add((New-OmLcAction -Name 'watchdog task' -Status 'skipped' -Detail 'skipped by -SkipWatchdogTask'))
        } else {
            $actions.Add((Stop-OmLcScheduledTask -TaskName $TaskName -DryRun:$DryRun))
        }
    }

    # 3. Compose project. `down` without -v keeps named volumes (ledger data).
    if (-not $SkipCompose) {
        $actions.Add((Invoke-OmLcComposeDown -AppRoot $AppRoot -ProjectName $ProjectName -DryRun:$DryRun))
    }

    # 4. Optional Funnel/Serve removal. Off by default; uninstall owns routes.
    if ($IncludeFunnel) {
        $modulePath = Join-Path (Split-Path -Parent $PSScriptRoot) 'OpenMargin.Setup.psd1'
        if (-not (Test-Path $modulePath)) {
            $actions.Add((New-OmLcAction -Name 'funnel' -Status 'skipped' -Detail 'setup engine module not found'))
        } elseif ($DryRun) {
            $actions.Add((New-OmLcAction -Name 'funnel' -Status 'planned' -Detail 'tailscale funnel/serve --https=443 off (only when our route is the sole route)'))
        } else {
            try {
                Import-Module $modulePath -Force -DisableNameChecking
                Initialize-OmPaths -StateRoot $StateRoot -RepoRoot $AppRoot
                $gatewayPort = 18768
                try {
                    $state = Read-OmState
                    if ($state.ports.gateway) { $gatewayPort = [int]$state.ports.gateway }
                } catch { }
                if (-not (Find-OmTailscale)) {
                    $actions.Add((New-OmLcAction -Name 'funnel' -Status 'skipped' -Detail 'Tailscale not installed'))
                } else {
                    $funnel = Disable-OmFunnel -GatewayPort $gatewayPort
                    $actions.Add((New-OmLcAction -Name 'funnel' -Status $(if ($funnel.ok) { 'done' } else { 'skipped' }) -Detail ([string]$funnel.code + ' ' + [string]$funnel.message)))
                    $serve = Get-OmTailscaleStatusJson -Kind serve
                    if ($null -eq $serve) {
                        $actions.Add((New-OmLcAction -Name 'serve' -Status 'skipped' -Detail 'cannot read serve status'))
                    } else {
                        $foreign = Test-OmForeignRoutes -Status $serve -GatewayPort $gatewayPort
                        $ours = @(Get-OmRouteHandlers $serve | Where-Object { Test-OmOurProxy -Proxy $_.proxy -GatewayPort $gatewayPort })
                        if ($foreign.foreign) {
                            $actions.Add((New-OmLcAction -Name 'serve' -Status 'skipped' -Detail 'foreign Serve route present'))
                        } elseif ($ours.Count -eq 0) {
                            $actions.Add((New-OmLcAction -Name 'serve' -Status 'not_found'))
                        } else {
                            $served = Invoke-OmTailscale -Arguments @('serve', '--https=443', 'off')
                            $actions.Add((New-OmLcAction -Name 'serve' -Status $(if ($served.exitCode -eq 0) { 'done' } else { 'failed' }) -Detail ('exit ' + $served.exitCode)))
                        }
                    }
                }
            } catch {
                $actions.Add((New-OmLcAction -Name 'funnel' -Status 'failed' -Detail $_.Exception.Message))
            }
        }
    }
}

$failed = @($actions | Where-Object { $_.status -eq 'failed' }).Count
if ($Json) {
    $payload = [ordered]@{
        ok        = ($failed -eq 0)
        dryRun    = [bool]$DryRun
        forInstall = [bool]$ForInstall
        appRoot   = $AppRoot
        stateRoot = $StateRoot
        actions   = @($actions.ToArray())
    }
    $payload | ConvertTo-Json -Depth 8
} else {
    Write-Host ('Open Margin stop (' + $(if ($DryRun) { 'dry run' } else { 'live' }) + ')')
    Write-Host ('  app root:   ' + $AppRoot)
    Write-Host ('  state root: ' + $StateRoot)
    Write-OmLcActionList -Actions @($actions.ToArray())
    if ($failed -gt 0) {
        Write-Host ('  warnings:   ' + $failed + ' action(s) failed; see details above')
    }
}
