#Requires -Version 5.1
<#
.SYNOPSIS
    Add or remove HTTP URL ACLs for the loopback setup wizard.

.DESCRIPTION
    HttpListener requires a URL reservation even for http://127.0.0.1/.
    The installer runs elevated once and reserves 18770-18779 for the local
    Users group (SID S-1-5-32-545). The wizard stays bound to loopback only;
    this reservation does not expose the wizard to the network.

    The script is intentionally idempotent: it removes a stale reservation for
    the same loopback URL before adding it again.
#>
param(
    [int[]]$Ports = @(18770, 18771, 18772, 18773, 18774, 18775, 18776, 18777, 18778, 18779),
    [switch]$Remove
)

$ErrorActionPreference = 'Continue'
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch { }

$netsh = Join-Path $env:SystemRoot 'System32\netsh.exe'
if (-not (Test-Path $netsh)) { $netsh = 'netsh.exe' }

function Test-IsAdmin {
    try {
        $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
        $principal = New-Object Security.Principal.WindowsPrincipal($identity)
        return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    } catch {
        return $false
    }
}

if (-not (Test-IsAdmin)) {
    Write-Warning 'URL ACL 需要管理员权限。请用管理员 PowerShell 运行本脚本；安装器已自动提权。'
    exit 0
}

$userCandidates = @(
    '*S-1-5-32-545',                    # BUILTIN\Users, locale-independent
    ($env:USERDOMAIN + '\' + $env:USERNAME),
    '*S-1-1-0'                          # Everyone fallback
)

$failures = 0
foreach ($port in $Ports) {
    $url = 'http://127.0.0.1:' + $port + '/'
    $null = & $netsh http delete urlacl ('url=' + $url) 2>&1
    if ($Remove) {
        Write-Host ('removed URL ACL ' + $url)
        continue
    }

    $added = $false
    foreach ($user in $userCandidates) {
        $output = & $netsh http add urlacl ('url=' + $url) ('user=' + $user) 2>&1 | Out-String
        if ($LASTEXITCODE -eq 0) {
            Write-Host ('reserved ' + $url + ' for ' + $user)
            $added = $true
            break
        }
    }
    if (-not $added) {
        $failures++
        Write-Warning ('无法为 ' + $url + ' 添加 URL ACL；向导可能无法启动。请以管理员运行 netsh http add urlacl url=' + $url + ' user=*S-1-5-32-545')
    }
}

if ($failures -gt 0) { exit 1 }
exit 0
