#Requires -Version 5.1
<#
.SYNOPSIS
    Open the local Wallos ledger page using the port configured in .env.
#>
param(
    [string]$AppRoot = ''
)

$ErrorActionPreference = 'Stop'

if (-not $AppRoot) {
    $AppRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
}

$port = 18082
$envPath = Join-Path $AppRoot '.env'
if (Test-Path $envPath) {
    try {
        $line = Select-String -Path $envPath -Pattern '^WALLOS_PORT=(\d+)\s*$' | Select-Object -First 1
        if ($line) {
            $parsed = 0
            if ([int]::TryParse($line.Matches[0].Groups[1].Value, [ref]$parsed) -and $parsed -gt 0) {
                $port = $parsed
            }
        }
    } catch { }
}

Start-Process ('http://127.0.0.1:' + $port + '/')
