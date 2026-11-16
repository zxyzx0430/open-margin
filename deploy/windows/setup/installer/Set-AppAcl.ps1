#Requires -Version 5.1
<#
.SYNOPSIS
    Apply deterministic ACLs to the installed Open Margin app tree.

.DESCRIPTION
    The setup engine runs as the interactive user and needs to create .env at
    the app root plus secrets/ files. Code and documentation stay read/execute
    for local Users; secrets are restricted to the installing user,
    Administrators and SYSTEM.

    SIDs are used instead of localized group names so this works on Chinese and
    other non-English Windows installations. Run elevated from the installer.
#>
param(
    [Parameter(Mandatory = $true)][string]$AppRoot,
    [string]$OriginalUser = ''
)

$ErrorActionPreference = 'Continue'
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch { }

$icacls = Join-Path $env:SystemRoot 'System32\icacls.exe'
if (-not (Test-Path $icacls)) { $icacls = 'icacls.exe' }

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Warning 'ACL 设置需要管理员权限；安装器应以管理员运行。'
    exit 0
}

if (-not (Test-Path $AppRoot)) {
    Write-Warning ('App root not found: ' + $AppRoot)
    exit 1
}

$users = '*S-1-5-32-545'          # BUILTIN\Users
$admins = '*S-1-5-32-544'         # BUILTIN\Administrators
$system = '*S-1-5-18'             # NT AUTHORITY\SYSTEM
$currentUser = '*' + $identity.User.Value

$failures = New-Object System.Collections.Generic.List[string]

# The app root is writable so the engine can create/update .env.
$output = & $icacls $AppRoot /grant:r ($users + ':(OI)(CI)M') 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) { $failures.Add('grant root to Users: ' + $output.Trim()) }

# Read-only code/document trees for local Users.
foreach ($name in @('backend', 'deploy', 'docs')) {
    $dir = Join-Path $AppRoot $name
    if (-not (Test-Path $dir)) { continue }
    $output = & $icacls $dir /inheritance:r /grant:r `
        ($users + ':(OI)(CI)RX') `
        ($admins + ':(OI)(CI)F') `
        ($system + ':(OI)(CI)F') 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) { $failures.Add(('secure ' + $dir + ': ' + $output.Trim())) }
}

# Secrets: the user who started Setup, Administrators and SYSTEM only.
# {username} is the original user even when Setup is elevated for another
# account; fall back to Users if it cannot be resolved.
$secrets = Join-Path $AppRoot 'secrets'
if (-not (Test-Path $secrets)) { New-Item -ItemType Directory -Force -Path $secrets | Out-Null }

$secretGrantees = New-Object System.Collections.Generic.List[string]
$originalSid = ''
if ($OriginalUser) {
    foreach ($candidate in @($OriginalUser, ($env:USERDOMAIN + '\' + $OriginalUser))) {
        try {
            $account = New-Object Security.Principal.NTAccount($candidate)
            $originalSid = $account.Translate([Security.Principal.SecurityIdentifier]).Value
            if ($originalSid) { break }
        } catch { }
    }
}
if ($originalSid) {
    $secretGrantees.Add('*' + $originalSid + ':(OI)(CI)F')
} else {
    $secretGrantees.Add($users + ':(OI)(CI)M')
}
$secretGrantees.Add($currentUser + ':(OI)(CI)F')
$secretGrantees.Add($admins + ':(OI)(CI)F')
$secretGrantees.Add($system + ':(OI)(CI)F')

$output = & $icacls $secrets /inheritance:r /grant:r @secretGrantees 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) { $failures.Add('secure secrets: ' + $output.Trim()) }

if ($failures.Count -gt 0) {
    foreach ($failure in $failures) { Write-Warning $failure }
    exit 1
}

Write-Host 'App ACLs applied.'
exit 0
