#Requires -Version 5.1
<#
.SYNOPSIS
    Apply deterministic ACLs to the installed Open Margin app tree.

.DESCRIPTION
    Security model:
      - The app root is writable only for the installing/original user,
        Administrators and SYSTEM. BUILTIN\Users no longer receives Modify on
        the root, compose.yaml or .env.
      - Code and documentation trees (backend, deploy, docs) are read/execute
        for BUILTIN\Users so Start Menu shortcuts work for local users.
      - secrets/ is restricted to the installing/original user, Administrators
        and SYSTEM. Legacy Users/Everyone grants are removed explicitly.

    The installing user is resolved from -OriginalUserSid, then -OriginalUser,
    then the current WindowsIdentity. Under normal UAC elevation the elevated
    token keeps the same user SID, so the current identity is a safe fallback.
    If the requested identity cannot be resolved, the script fails explicitly.

    SIDs are used instead of localized group names so this works on Chinese and
    other non-English Windows installations.

.PARAMETER DryRun
    Print the exact icacls argument vectors (and a machine-readable plan line)
    without changing any ACL. Used by Pester and for reviewing upgrades.

    Run elevated from the installer for a real application.
#>
param(
    [Parameter(Mandatory = $true)][string]$AppRoot,
    [string]$OriginalUser = '',
    [string]$OriginalUserSid = '',
    [switch]$DryRun
)

$ErrorActionPreference = 'Continue'
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch { }

$icacls = Join-Path $env:SystemRoot 'System32\icacls.exe'
if (-not (Test-Path $icacls)) { $icacls = 'icacls.exe' }

function Resolve-OmAclUserSid {
    param([string]$ExplicitSid, [string]$User)
    if ($ExplicitSid) {
        $value = $ExplicitSid.Trim().TrimStart('*')
        try {
            return (New-Object System.Security.Principal.SecurityIdentifier($value)).Value
        } catch {
            throw ('invalid -OriginalUserSid: ' + $ExplicitSid)
        }
    }
    if ($User) {
        if ($User -match '^S-1-[0-9-]+$') {
            try { return (New-Object System.Security.Principal.SecurityIdentifier($User)).Value } catch { }
        }
        $candidates = New-Object System.Collections.Generic.List[string]
        $candidates.Add($User)
        if ($env:USERDOMAIN) { $candidates.Add($env:USERDOMAIN + '\' + $User) }
        if ($env:COMPUTERNAME) { $candidates.Add($env:COMPUTERNAME + '\' + $User) }
        foreach ($candidate in $candidates) {
            try {
                $account = New-Object System.Security.Principal.NTAccount($candidate)
                return $account.Translate([System.Security.Principal.SecurityIdentifier]).Value
            } catch { }
        }
    }
    if ($User) { throw ('Cannot resolve installing user: ' + $User) }
    try {
        return ([System.Security.Principal.WindowsIdentity]::GetCurrent()).User.Value
    } catch {
        return $null
    }
}

if (-not (Test-Path $AppRoot)) {
    Write-Warning ('App root not found: ' + $AppRoot)
    exit 1
}
$AppRoot = (Resolve-Path $AppRoot).Path
if (-not $DryRun) {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        Write-Warning 'ACL 设置需要管理员权限；安装器应以管理员运行。'
        exit 1
    }
}

try {
    $userSidValue = Resolve-OmAclUserSid -ExplicitSid $OriginalUserSid -User $OriginalUser
} catch {
    Write-Error $_.Exception.Message
    exit 2
}
$fallbackToUsers = $false
if (-not $userSidValue) {
    Write-Error 'Could not resolve the installing user SID.'
    exit 2
}

$installUser = '*' + $userSidValue
$users = '*S-1-5-32-545'          # BUILTIN\Users
$admins = '*S-1-5-32-544'         # BUILTIN\Administrators
$system = '*S-1-5-18'             # NT AUTHORITY\SYSTEM
$everyone = '*S-1-1-0'            # Everyone (legacy cleanup only)

$plan = New-Object System.Collections.Generic.List[object]
function Add-AclPlan {
    param([string[]]$Arguments, [string]$Reason)
    $plan.Add([pscustomobject]@{ tool = 'icacls'; arguments = $Arguments; reason = $Reason })
}

# --- App root -------------------------------------------------------------
# Remove legacy explicit grants first: /inheritance:r only drops inherited
# ACEs, so an old explicit Users:M ACE from a previous installer would survive
# unless it is removed by SID.
Add-AclPlan @($AppRoot, '/remove:g', $users, $everyone) 'remove legacy Users/Everyone grants from app root'
Add-AclPlan @(
    $AppRoot, '/inheritance:r', '/grant:r',
    ($installUser + ':(OI)(CI)M'),
    ($admins + ':(OI)(CI)F'),
    ($system + ':(OI)(CI)F')
) 'app root writable only for installing user/Administrators/SYSTEM'

# --- Code and documentation trees ----------------------------------------
foreach ($name in @('backend', 'deploy', 'docs')) {
    $dir = Join-Path $AppRoot $name
    if (-not (Test-Path $dir)) { continue }
    Add-AclPlan @($dir, '/remove:g', $everyone) ('remove legacy Everyone grant from ' + $name)
    Add-AclPlan @(
        $dir, '/inheritance:r', '/grant:r',
        ($users + ':(OI)(CI)RX'),
        ($admins + ':(OI)(CI)F'),
        ($system + ':(OI)(CI)F')
    ) ('read/execute code tree ' + $name)
}

# --- secrets/ -------------------------------------------------------------
# Created by the setup engine at runtime; create it now if it does not exist.
$secrets = Join-Path $AppRoot 'secrets'
if (-not $DryRun -and -not (Test-Path $secrets)) {
    New-Item -ItemType Directory -Force -Path $secrets | Out-Null
}
Add-AclPlan @($secrets, '/remove:g', $users, $everyone, '/T') 'remove legacy Users/Everyone grants from secrets'
Add-AclPlan @(
    $secrets, '/inheritance:r', '/grant:r',
    ($installUser + ':(OI)(CI)F'),
    ($admins + ':(OI)(CI)F'),
    ($system + ':(OI)(CI)F'),
    '/T'
) 'secrets restricted to installing user/Administrators/SYSTEM'

# --- Root configuration files --------------------------------------------
# These are inherited from the root, but an old explicit assignment (or a file
# created before the ACL change) must not keep a broad grant.
foreach ($fileName in @('compose.yaml', '.env')) {
    $file = Join-Path $AppRoot $fileName
    if (-not (Test-Path $file)) { continue }
    Add-AclPlan @($file, '/remove:g', $users, $everyone) ('remove legacy Users/Everyone grants from ' + $fileName)
    Add-AclPlan @(
        $file, '/inheritance:r', '/grant:r',
        ($installUser + ':M'),
        ($admins + ':F'),
        ($system + ':F')
    ) ($fileName + ' writable only for installing user/Administrators/SYSTEM')
}

if ($DryRun) {
    foreach ($item in $plan) {
        Write-Host ('[dry-run] ' + $item.tool + ' ' + ($item.arguments -join ' '))
    }
    $payload = [ordered]@{
        ok                = $true
        dryRun            = $true
        appRoot           = $AppRoot
        originalUserSid   = $userSidValue
        fallbackToUsers   = $fallbackToUsers
        commands          = @($plan.ToArray())
    }
    Write-Output ('OPENMARGIN_ACL_PLAN=' + ($payload | ConvertTo-Json -Depth 8 -Compress))
    exit 0
}

$failures = New-Object System.Collections.Generic.List[string]
foreach ($item in $plan) {
    $arguments = @($item.arguments)
    $output = & $icacls @arguments 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) {
        $failures.Add($item.reason + ': ' + $output.Trim())
    }
}

if ($failures.Count -eq 0) {
    foreach ($path in @($AppRoot, $secrets)) {
        try {
            $acl = Get-Acl -LiteralPath $path -ErrorAction Stop
            $rules = @($acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]))
            if ($rules.Count -eq 0) { throw 'DACL is empty' }
            $allow = @($rules | Where-Object { $_.IdentityReference.Value -eq $userSidValue -and $_.AccessControlType -eq 'Allow' -and ($_.FileSystemRights -band [Security.AccessControl.FileSystemRights]::ReadData) })
            $deny = @($rules | Where-Object { $_.IdentityReference.Value -eq $userSidValue -and $_.AccessControlType -eq 'Deny' -and ($_.FileSystemRights -band [Security.AccessControl.FileSystemRights]::ReadData) })
            if ($allow.Count -eq 0 -or $deny.Count -gt 0) { throw 'installing user has no usable read grant' }
        } catch { $failures.Add($path + ': ACL verification failed: ' + $_.Exception.Message) }
    }
}
if ($failures.Count -gt 0) {
    foreach ($failure in $failures) { Write-Warning $failure }
    exit 1
}

Write-Host 'App ACLs applied.'
exit 0
