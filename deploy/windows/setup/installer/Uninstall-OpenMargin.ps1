#Requires -Version 5.1
<#
.SYNOPSIS
    Detach and clean up a running Open Margin install before the Inno Setup
    uninstaller removes program files.

.DESCRIPTION
    Called from [UninstallRun], which Inno Setup executes as the first uninstall
    step while the installed scripts still exist. The script:
      - stops the hidden wizard server belonging to this app root,
      - stops the watchdog process and uninstalls the OpenMarginWatchdog task
        through `open-margin-setup.ps1 uninstall-watchdog`,
      - removes only this project's Tailscale Funnel/Serve route (the engine
        module refuses to touch foreign routes),
      - stops the compose project with `docker compose ... down` (never -v,
        Docker volumes are preserved),
      - removes the loopback URL ACL reservations,
      - preserves secrets/, .env, Docker volumes and %LOCALAPPDATA% state and
        writes a summary of what remains and how to remove it manually.

    Docker and Tailscale are optional at uninstall time. Missing tools are
    reported and never block the uninstaller.

    This script is ASCII-only because it may run before/without the installed
    UTF-8 normalization. It never calls `exit`; Inno continues even on failure.

.PARAMETER DryRun
    Report the planned actions without stopping processes, touching Docker or
    Tailscale, or removing URL ACLs. Safe for tests.

.PARAMETER SkipFunnel
    Do not touch Tailscale Funnel/Serve routes.

.PARAMETER SkipCompose
    Do not stop the compose project.

.PARAMETER SkipWatchdog
    Do not stop watchdog processes.

.PARAMETER SkipWatchdogTask
    Do not uninstall the OpenMarginWatchdog scheduled task. Tests use this to
    avoid touching a real machine-level task.

.PARAMETER SkipUrlAcl
    Do not remove loopback URL ACL reservations.

.PARAMETER SummaryPath
    Where to write the human-readable summary. Defaults to
    %ProgramData%\OpenMargin\logs\uninstall-summary-<timestamp>.txt with a
    fallback to the per-user state root.
#>
param(
    [string]$AppRoot = '',
    [string]$StateRoot = '',
    [string]$ProjectName = 'personal-finance-system',
    [string]$TaskName = 'OpenMarginWatchdog',
    [string]$SummaryPath = '',
    [switch]$DryRun,
    [switch]$SkipFunnel,
    [switch]$SkipCompose,
    [switch]$SkipWatchdog,
    [switch]$SkipWatchdogTask,
    [switch]$SkipUrlAcl,
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
$setupRoot = Split-Path -Parent $PSScriptRoot
$powershell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
if (-not (Test-Path $powershell)) { $powershell = 'powershell.exe' }

$actions = [System.Collections.Generic.List[object]]::new()
$warnings = [System.Collections.Generic.List[string]]::new()

# 1. Hidden wizard server. Only the process whose command line points at this
# app root is stopped; unrelated PowerShell windows are never touched.
$wizardStatus = Get-OmLcWizardStatus -AppRoot $AppRoot -StateRoot $StateRoot
$wizardProcesses = @(Get-OmLcWizardProcesses -AppRoot $AppRoot)
if ($wizardProcesses.Count -gt 0) {
    foreach ($action in @(Stop-OmLcProcessSet -Processes $wizardProcesses -Label 'wizard' -DryRun:$DryRun)) {
        $actions.Add($action)
    }
} else {
    $actions.Add((New-OmLcAction -Name 'wizard' -Status 'not_found' -Detail 'no Start-OpenMarginSetupWeb.ps1 process for this app root'))
}

if ($null -ne $wizardStatus.state) {
    $remaining = @()
    if (-not $DryRun) { $remaining = @(Get-OmLcWizardProcesses -AppRoot $AppRoot) }
    if ($remaining.Count -gt 0) {
        $actions.Add((New-OmLcAction -Name 'wizard state' -Status 'skipped' -Detail 'wizard still running; setup-web.json kept'))
    } elseif ($wizardStatus.statePidAlive -and -not $wizardStatus.stateMatches) {
        # PID reuse: never kill the unrelated process, but drop our stale state.
        if ($DryRun) {
            $actions.Add((New-OmLcAction -Name 'wizard state' -Status 'planned' -Detail ('PID belongs to another process; remove stale ' + $wizardStatus.state.path)))
        } else {
            try {
                Remove-Item -Force $wizardStatus.state.path -ErrorAction Stop
                $actions.Add((New-OmLcAction -Name 'wizard state' -Status 'done' -Detail 'PID belongs to another process; removed stale setup-web.json'))
            } catch {
                $warnings.Add('could not remove setup-web.json: ' + $_.Exception.Message)
            }
        }
    } elseif ($DryRun) {
        $actions.Add((New-OmLcAction -Name 'wizard state' -Status 'planned' -Detail ('remove ' + $wizardStatus.state.path)))
    } else {
        try {
            Remove-Item -Force $wizardStatus.state.path -ErrorAction Stop
            $actions.Add((New-OmLcAction -Name 'wizard state' -Status 'done' -Detail 'removed stale setup-web.json'))
        } catch {
            $warnings.Add('could not remove setup-web.json: ' + $_.Exception.Message)
        }
    }
}

# 2. Watchdog process and scheduled task.
if (-not $SkipWatchdog) {
    $watchdogProcesses = @(Get-OmLcWatchdogProcesses -StateRoot $StateRoot -AppRoot $AppRoot)
    if ($watchdogProcesses.Count -gt 0) {
        foreach ($action in @(Stop-OmLcProcessSet -Processes $watchdogProcesses -Label 'watchdog' -DryRun:$DryRun)) {
            $actions.Add($action)
        }
    } else {
        $actions.Add((New-OmLcAction -Name 'watchdog' -Status 'not_found' -Detail 'no watchdog process for this app root'))
    }
}

if ($SkipWatchdogTask) {
    $actions.Add((New-OmLcAction -Name 'watchdog task' -Status 'skipped' -Detail 'skipped by -SkipWatchdogTask'))
} else {
    # Prefer the documented CLI entry point; it also clears watchdog state.
    $cli = Join-Path $setupRoot 'open-margin-setup.ps1'
    if (Test-Path $cli) {
        $cliArgs = @(
            '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $cli,
            'uninstall-watchdog', '--state-root', $StateRoot, '--repo-root', $AppRoot, '--json'
        )
        if ($DryRun) { $cliArgs += '--dry-run' }
        try {
            $cliOutput = & $powershell @cliArgs 2>&1 | Out-String
            $cliCode = $LASTEXITCODE
            $code = $null
            try {
                $parsed = $cliOutput.Trim() | ConvertFrom-Json
                if ($parsed.PSObject.Properties['code']) { $code = [string]$parsed.code }
            } catch { }
            $status = 'done'
            if ($cliCode -ne 0 -or $code -eq 'WATCHDOG_REMOVE_FAILED') { $status = 'failed' }
            if ($DryRun) { $status = 'planned' }
            $actions.Add((New-OmLcAction -Name 'watchdog task' -Status $status -Detail ('open-margin-setup.ps1 uninstall-watchdog exit ' + $cliCode + ' ' + [string]$code)))
            if ($status -eq 'failed') {
                $warnings.Add('watchdog task removal failed; remove OpenMarginWatchdog in Task Scheduler manually')
            }
        } catch {
            $actions.Add((New-OmLcAction -Name 'watchdog task' -Status 'failed' -Detail $_.Exception.Message))
            $warnings.Add('watchdog CLI failed: ' + $_.Exception.Message)
        }
    } else {
        $actions.Add((Remove-OmLcScheduledTask -TaskName $TaskName -DryRun:$DryRun))
    }
}

# 3. Tailscale Funnel/Serve route. The engine module only removes our own route
# and returns FOREIGN_* instead of touching somebody else's Serve/Funnel.
if ($SkipFunnel) {
    $actions.Add((New-OmLcAction -Name 'funnel' -Status 'skipped' -Detail 'skipped by -SkipFunnel'))
} else {
    $modulePath = Join-Path $setupRoot 'OpenMargin.Setup.psd1'
    if (-not (Test-Path $modulePath)) {
        $actions.Add((New-OmLcAction -Name 'funnel' -Status 'skipped' -Detail 'setup engine module not found'))
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
            } elseif ($DryRun) {
                $actions.Add((New-OmLcAction -Name 'funnel' -Status 'planned' -Detail 'tailscale funnel --https=443 off (only when our route is the sole route)'))
                $actions.Add((New-OmLcAction -Name 'serve' -Status 'planned' -Detail 'tailscale serve --https=443 off (only when our route is the sole route)'))
            } else {
                $funnel = Disable-OmFunnel -GatewayPort $gatewayPort
                $funnelStatus = 'done'
                if (-not $funnel.ok) {
                    if ($funnel.code -like 'FOREIGN_*' -or $funnel.code -eq 'FUNNEL_STATUS_UNAVAILABLE') { $funnelStatus = 'skipped' } else { $funnelStatus = 'failed' }
                }
                $actions.Add((New-OmLcAction -Name 'funnel' -Status $funnelStatus -Detail ([string]$funnel.code + ' ' + [string]$funnel.message)))

                $serveStatus = Get-OmTailscaleStatusJson -Kind serve
                if ($null -eq $serveStatus) {
                    $actions.Add((New-OmLcAction -Name 'serve' -Status 'skipped' -Detail 'cannot read serve status'))
                } else {
                    $foreign = Test-OmForeignRoutes -Status $serveStatus -GatewayPort $gatewayPort
                    $ours = @(Get-OmRouteHandlers $serveStatus | Where-Object { Test-OmOurProxy -Proxy $_.proxy -GatewayPort $gatewayPort })
                    if ($foreign.foreign) {
                        $actions.Add((New-OmLcAction -Name 'serve' -Status 'skipped' -Detail 'foreign Serve route present; not touched'))
                    } elseif ($ours.Count -eq 0) {
                        $actions.Add((New-OmLcAction -Name 'serve' -Status 'not_found' -Detail 'no Serve route for this app'))
                    } else {
                        $served = Invoke-OmTailscale -Arguments @('serve', '--https=443', 'off')
                        $actions.Add((New-OmLcAction -Name 'serve' -Status $(if ($served.exitCode -eq 0) { 'done' } else { 'failed' }) -Detail ('exit ' + $served.exitCode + ' ' + [string]$served.output)))
                    }
                }
            }
        } catch {
            $actions.Add((New-OmLcAction -Name 'funnel' -Status 'failed' -Detail $_.Exception.Message))
            $warnings.Add('funnel cleanup failed: ' + $_.Exception.Message)
        }
    }
}

# 4. Compose project. `down` without -v: named volumes keep ledger data.
if ($SkipCompose) {
    $actions.Add((New-OmLcAction -Name 'compose down' -Status 'skipped' -Detail 'skipped by -SkipCompose'))
} else {
    $actions.Add((Invoke-OmLcComposeDown -AppRoot $AppRoot -ProjectName $ProjectName -DryRun:$DryRun))
}

# 5. Loopback URL ACL reservations. The narrow installer reservation is only
# needed for the un-elevated wizard path; removing it does not affect data.
if ($SkipUrlAcl) {
    $actions.Add((New-OmLcAction -Name 'url acl' -Status 'skipped' -Detail 'skipped by -SkipUrlAcl'))
} else {
    $aclScript = Join-Path $PSScriptRoot 'Install-UrlAcl.ps1'
    if (-not (Test-Path $aclScript)) {
        $actions.Add((New-OmLcAction -Name 'url acl' -Status 'skipped' -Detail 'Install-UrlAcl.ps1 not found'))
    } else {
        $aclArgs = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $aclScript, '-Remove')
        if ($DryRun) { $aclArgs += '-DryRun' }
        try {
            $aclOutput = & $powershell @aclArgs 2>&1 | Out-String
            $aclCode = $LASTEXITCODE
            $status = 'done'
            if ($DryRun) { $status = 'planned' }
            elseif ($aclCode -ne 0) { $status = 'failed' }
            $actions.Add((New-OmLcAction -Name 'url acl' -Status $status -Detail ('exit ' + $aclCode + '; ' + $aclOutput)))
            if ($status -eq 'failed') {
                $warnings.Add('URL ACL removal failed; the reservation is harmless but can be removed with: netsh http delete urlacl url=http://127.0.0.1:18770/')
            }
        } catch {
            $actions.Add((New-OmLcAction -Name 'url acl' -Status 'failed' -Detail $_.Exception.Message))
            $warnings.Add('URL ACL removal failed: ' + $_.Exception.Message)
        }
    }
}

# 6. Summary: what was done, what remains, and how to remove it manually.
$secretsPath = Join-Path $AppRoot 'secrets'
$envPath = Join-Path $AppRoot '.env'
$composePath = Join-Path $AppRoot 'compose.yaml'

$preserved = [System.Collections.Generic.List[object]]::new()
$preserved.Add([pscustomobject]@{ path = $secretsPath; kind = 'secrets'; exists = (Test-Path $secretsPath); note = 'device/admin tokens; delete only if you no longer need this ledger' })
$preserved.Add([pscustomobject]@{ path = $envPath; kind = 'env'; exists = (Test-Path $envPath); note = 'port/timezone configuration written by the setup engine' })
$preserved.Add([pscustomobject]@{ path = ('docker volumes for compose project ' + $ProjectName); kind = 'docker-volumes'; exists = $true; note = 'ledger data; preserved by design' })
$preserved.Add([pscustomobject]@{ path = $StateRoot; kind = 'user-state'; exists = (Test-Path $StateRoot); note = 'setup state, logs, backups and watchdog state' })
$programDataLogs = Join-Path $env:ProgramData 'OpenMargin\logs'
$preserved.Add([pscustomobject]@{ path = $programDataLogs; kind = 'logs'; exists = (Test-Path $programDataLogs); note = 'install/uninstall logs' })

$manualRemoval = [System.Collections.Generic.List[string]]::new()
$manualRemoval.Add('Stop the project if it is still running: docker compose --project-name "' + $ProjectName + '" --project-directory "' + $AppRoot + '" -f "' + $composePath + '" down')
$manualRemoval.Add('List the preserved volumes: docker volume ls --filter label=com.docker.compose.project=' + $ProjectName)
$manualRemoval.Add('Remove the volumes printed above ONLY if you want to destroy the ledger: docker volume rm <volume name>')
$manualRemoval.Add('Remove secrets: Remove-Item -Recurse -Force "' + $secretsPath + '"')
$manualRemoval.Add('Remove .env: Remove-Item -Force "' + $envPath + '"')
$manualRemoval.Add('Remove per-user state and logs: Remove-Item -Recurse -Force "' + $StateRoot + '"')
$manualRemoval.Add('Remove machine logs: Remove-Item -Recurse -Force "' + $programDataLogs + '"')

$failed = @($actions | Where-Object { $_.status -eq 'failed' }).Count
$summaryLines = [System.Collections.Generic.List[string]]::new()
$summaryLines.Add('Open Margin uninstall summary')
$summaryLines.Add('Generated: ' + (Get-Date).ToString('yyyy-MM-dd HH:mm:ss'))
$summaryLines.Add('Mode: ' + $(if ($DryRun) { 'dry run (nothing changed)' } else { 'uninstall' }))
$summaryLines.Add('App root: ' + $AppRoot)
$summaryLines.Add('State root: ' + $StateRoot)
$summaryLines.Add('')
$summaryLines.Add('Actions:')
foreach ($action in $actions) {
    $line = '  ' + $action.name.PadRight(16) + ' ' + $action.status
    if ($action.detail) { $line += ' - ' + $action.detail }
    $summaryLines.Add($line)
}
$summaryLines.Add('')
$summaryLines.Add('Preserved (not removed by this script or the uninstaller):')
foreach ($item in $preserved) {
    $state = $(if ($item.exists) { 'exists' } else { 'absent' })
    $summaryLines.Add('  [' + $state + '] ' + $item.path + ' - ' + $item.note)
}
$summaryLines.Add('')
$summaryLines.Add('Manual cleanup (the last volume command DESTROYS ledger data):')
foreach ($line in $manualRemoval) { $summaryLines.Add('  ' + $line) }
$summaryLines.Add('')
$summaryLines.Add('Notes:')
$summaryLines.Add('  - Inno Setup removes installed program files after this script returns.')
$summaryLines.Add('  - Docker volumes, secrets/, .env and user state are intentionally kept.')
$summaryLines.Add('  - If Docker or Tailscale was missing, the corresponding action is marked skipped.')
if ($failed -gt 0) {
    $summaryLines.Add('  - ' + $failed + ' action(s) failed; see the Actions list above.')
}
foreach ($warning in $warnings) { $summaryLines.Add('  - WARNING: ' + $warning) }

if (-not $SummaryPath) {
    $base = Join-Path $env:ProgramData 'OpenMargin\logs'
    if (-not $env:ProgramData) { $base = Join-Path $StateRoot 'logs' }
    $SummaryPath = Join-Path $base ('uninstall-summary-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.txt')
}
$summaryWritten = $false
try {
    $summaryDir = Split-Path -Parent $SummaryPath
    if ($summaryDir -and -not (Test-Path $summaryDir)) { New-Item -ItemType Directory -Force -Path $summaryDir | Out-Null }
    [System.IO.File]::WriteAllText($SummaryPath, ($summaryLines -join [Environment]::NewLine) + [Environment]::NewLine, (New-Object System.Text.UTF8Encoding($false)))
    $summaryWritten = $true
} catch {
    $warnings.Add('could not write summary: ' + $_.Exception.Message)
    try {
        $fallback = Join-Path (Join-Path $StateRoot 'logs') ('uninstall-summary-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.txt')
        if (-not (Test-Path (Split-Path -Parent $fallback))) { New-Item -ItemType Directory -Force -Path (Split-Path -Parent $fallback) | Out-Null }
        [System.IO.File]::WriteAllText($fallback, ($summaryLines -join [Environment]::NewLine) + [Environment]::NewLine, (New-Object System.Text.UTF8Encoding($false)))
        $SummaryPath = $fallback
        $summaryWritten = $true
    } catch {
        $warnings.Add('fallback summary path also failed: ' + $_.Exception.Message)
    }
}

if ($Json) {
    $payload = [ordered]@{
        ok            = ($failed -eq 0)
        dryRun        = [bool]$DryRun
        appRoot       = $AppRoot
        stateRoot     = $StateRoot
        actions       = @($actions.ToArray())
        preserved     = @($preserved.ToArray())
        manualRemoval = @($manualRemoval.ToArray())
        warnings      = @($warnings.ToArray())
        summaryPath   = $(if ($summaryWritten) { $SummaryPath } else { $null })
    }
    $payload | ConvertTo-Json -Depth 8
} else {
    foreach ($line in $summaryLines) { Write-Host $line }
    Write-Host ''
    if ($summaryWritten) { Write-Host ('Summary written to: ' + $SummaryPath) }
    Write-Host 'Done. The installer will now remove program files; secrets/, .env, volumes and user state remain.'
}
