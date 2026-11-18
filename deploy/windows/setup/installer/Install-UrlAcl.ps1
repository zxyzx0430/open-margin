#Requires -Version 5.1
<#
.SYNOPSIS
    Add or remove HTTP URL ACLs for the loopback setup wizard.

.DESCRIPTION
    HttpListener requires a URL reservation even for http://127.0.0.1/ unless
    the wizard runs elevated. This script keeps a narrow reservation for the
    un-elevated (`-NoElevate`) path only.

    Security model:
      - No BUILTIN\Users (S-1-5-32-545) and no Everyone (S-1-1-0) grant.
      - The reservation DACL grants only the installing/original user and
        BUILTIN\Administrators, expressed with SIDs so it works on localized
        Windows installations.
      - The user is resolved from -OriginalUserSid, then -OriginalUser, then
        the current WindowsIdentity. The current identity is a safe fallback
        under normal UAC elevation (same user SID).
      - If no identity can be resolved, the documented last-resort fallback is
        the legacy BUILTIN\Users grant; a warning is printed.

    The script is idempotent: it removes a stale reservation for the same
    loopback URL before adding it again.

.PARAMETER DryRun
    Print the exact netsh argument vectors (and a machine-readable plan line)
    without changing any reservation. Never requires elevation.

.PARAMETER Remove
    Delete the reservations for the given ports instead of adding them.
#>
param(
    [int[]]$Ports = @(18770, 18771, 18772, 18773, 18774, 18775, 18776, 18777, 18778, 18779),
    [switch]$Remove,
    [string]$OriginalUser = '',
    [string]$OriginalUserSid = '',
    [switch]$DryRun
)

$ErrorActionPreference = 'Continue'
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch { }

$netsh = Join-Path $env:SystemRoot 'System32\netsh.exe'
if (-not (Test-Path $netsh)) { $netsh = 'netsh.exe' }

function Test-OmUrlAclAdmin {
    try {
        $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
        $principal = New-Object Security.Principal.WindowsPrincipal($identity)
        return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    } catch {
        return $false
    }
}

function Resolve-OmUrlAclUserSid {
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

if (-not $DryRun -and -not (Test-OmUrlAclAdmin)) {
    Write-Warning 'URL ACL 需要管理员权限。请用管理员 PowerShell 运行本脚本；安装器已自动提权。'
    exit 1
}

try {
    $userSidValue = Resolve-OmUrlAclUserSid -ExplicitSid $OriginalUserSid -User $OriginalUser
} catch {
    Write-Error $_.Exception.Message
    exit 2
}
$fallbackToUsers = $false
if (-not $userSidValue) {
    Write-Error 'Could not resolve the installing user SID.'
    exit 2
}

$adminsSid = 'S-1-5-32-544'
# SDDL DACL: generic execute (listen) for the installing user and Administrators.
$sddl = 'D:(A;;GX;;;' + $userSidValue + ')(A;;GX;;;' + $adminsSid + ')'

$plan = New-Object System.Collections.Generic.List[object]
$fallbackPlans = New-Object System.Collections.Generic.List[object]
foreach ($port in $Ports) {
    if ($port -lt 1 -or $port -gt 65535) { continue }
    $url = 'http://127.0.0.1:' + $port + '/'
    $plan.Add([pscustomobject]@{
        tool      = 'netsh'
        arguments = @('http', 'delete', 'urlacl', ('url=' + $url))
        reason    = ('remove any previous reservation for ' + $url)
    })
    if ($Remove) { continue }
    $plan.Add([pscustomobject]@{
        tool      = 'netsh'
        arguments = @('http', 'add', 'urlacl', ('url=' + $url), ('sddl=' + $sddl))
        reason    = ('reserve ' + $url + ' for installing user and Administrators')
    })
    $fallbackPlans.Add([pscustomobject]@{
        tool      = 'netsh'
        arguments = @('http', 'add', 'urlacl', ('url=' + $url), ('user=*' + $userSidValue))
        reason    = ('fallback reservation for ' + $url + ' when the SDDL form is rejected')
    })
}

if ($DryRun) {
    foreach ($item in $plan) {
        Write-Host ('[dry-run] ' + $item.tool + ' ' + ($item.arguments -join ' '))
    }
    $payload = [ordered]@{
        ok              = $true
        dryRun          = $true
        remove          = [bool]$Remove
        ports           = @($Ports)
        originalUserSid = $userSidValue
        administratorsSid = $adminsSid
        sddl            = $sddl
        fallbackToUsers = $fallbackToUsers
        commands        = @($plan.ToArray())
        fallbackCommands = @($fallbackPlans.ToArray())
    }
    Write-Output ('OPENMARGIN_URLACL_PLAN=' + ($payload | ConvertTo-Json -Depth 8 -Compress))
    exit 0
}

$failures = 0
foreach ($port in $Ports) {
    if ($port -lt 1 -or $port -gt 65535) { continue }
    $url = 'http://127.0.0.1:' + $port + '/'
    $null = & $netsh http delete urlacl ('url=' + $url) 2>&1
    if ($Remove) {
        Write-Host ('removed URL ACL ' + $url)
        continue
    }

    $output = & $netsh http add urlacl ('url=' + $url) ('sddl=' + $sddl) 2>&1 | Out-String
    if ($LASTEXITCODE -eq 0) {
        Write-Host ('reserved ' + $url + ' for ' + $userSidValue + ' and ' + $adminsSid)
        continue
    }

    # Fallback: single-principal reservation for the installing user. This is
    # narrower than the old Users grant and still lets the wizard bind.
    $fallbackOutput = & $netsh http add urlacl ('url=' + $url) ('user=*' + $userSidValue) 2>&1 | Out-String
    if ($LASTEXITCODE -eq 0) {
        Write-Warning ('SDDL reservation failed for ' + $url + '; granted the installing user only. ' + $output.Trim())
        continue
    }
    $failures++
    Write-Warning ('无法为 ' + $url + ' 添加 URL ACL。请以管理员运行: netsh http add urlacl url=' + $url + ' user=*' + $userSidValue + ' ; sddl error: ' + $output.Trim() + ' ; user error: ' + $fallbackOutput.Trim())
}

if ($failures -gt 0) { exit 1 }
exit 0
