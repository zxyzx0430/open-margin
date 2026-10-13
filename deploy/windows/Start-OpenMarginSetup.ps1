# Interactive first-time setup; never changes existing remote configuration silently.
param(
  [ValidateSet("ApiOnly","PrivateFull")][string]$ExposureMode="ApiOnly"
)
$ErrorActionPreference="Stop"
$root=(Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$installer=Join-Path $PSScriptRoot "Install-OpenMargin.ps1"

Write-Host "Open Margin | Windows first connection" -ForegroundColor Cyan
Write-Host "Mode: $ExposureMode (ApiOnly keeps Wallos on localhost)"
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
  throw "Docker Desktop is required: https://www.docker.com/products/docker-desktop/"
}
& docker info *> $null
if ($LASTEXITCODE -ne 0) {
  throw "Start Docker Desktop and enable Linux containers / WSL2."
}
$ts=(Get-Command tailscale -ErrorAction SilentlyContinue)
if (-not $ts) {
  $fallback="C:\Program Files\Tailscale\tailscale.exe"
  if (Test-Path $fallback) { $ts=$fallback }
}
if (-not $ts) { throw "Install Tailscale: https://tailscale.com/download" }

$json=((& $ts status --json) -join [Environment]::NewLine)
if ($LASTEXITCODE -ne 0) { throw "Sign into Tailscale on Windows first." }
$status=$json | ConvertFrom-Json
if ($status.BackendState -ne "Running") { throw "Tailscale is not connected." }
$hostName=([string]$status.Self.DNSName).TrimEnd('.')
if (-not $hostName.EndsWith(".ts.net")) {
  throw "Tailscale MagicDNS hostname missing. Enable MagicDNS and HTTPS in the Tailscale admin console."
}
$url="https://$hostName/finance"
Write-Host "Detected device sync address: $url"
Write-Host "Install the Tailscale app on Android and join the same tailnet."
Write-Host "Step 1/2: Start private local containers, do not expose them remotely yet."
& powershell -NoProfile -ExecutionPolicy Bypass -File $installer -PublicBase $url -ExposureMode $ExposureMode
if ($LASTEXITCODE -ne 0) { throw "Local install failed. Remote Serve was not configured." }
Write-Host "Open http://127.0.0.1:18082/ on this PC and register the first Wallos account."
Start-Process "http://127.0.0.1:18082/"
$ready=Read-Host "After owner registration, type READY to enable private sync"
if ($ready -cne "READY") { Write-Host "Stopped safely. Re-run the wizard when ready."; exit 0 }
Write-Host "Step 2/2: Enable private HTTPS for this tailnet."
& powershell -NoProfile -ExecutionPolicy Bypass -File $installer -PublicBase $url -ExposureMode $ExposureMode -ConfigureTailscale
if ($LASTEXITCODE -ne 0) { throw "Tailscale Serve setup failed. Local data remains unchanged." }
Write-Host "Windows setup completed."
Write-Host "On Android: Settings -> Windows first connection wizard."
Write-Host "Server URL: $url"
Write-Host "Device token: read FINANCE_DEVICE_TOKEN in $root\secrets\backend.env locally."
Write-Host "Never share this file or put the token in a URL."
