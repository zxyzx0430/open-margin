# Run from root: powershell -ExecutionPolicy Bypass -File .\deploy\windows\Install-OpenMargin.ps1 -PublicBase https://YOUR-PC.YOUR-TAILNET.ts.net/finance
param(
  [Parameter(Mandatory=$true)][string]$PublicBase,
  [switch]$ConfigureTailscale,
  [int]$OwnerId=1
)
$ErrorActionPreference="Stop"
$root=(Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Set-Location $root
if ($PublicBase -notmatch '^https://[a-zA-Z0-9.-]+/finance/?$') {
  throw "PublicBase must look like https://windows-pc.example.ts.net/finance"
}
$PublicBase=$PublicBase.TrimEnd('/')
if ($OwnerId -lt 1) { throw "OwnerId must be positive" }
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
  throw "Install Docker Desktop, enable Linux containers and start Docker Desktop."
}
& docker info *> $null
if ($LASTEXITCODE -ne 0) { throw "Docker Desktop is not running." }
& docker compose version *> $null
if ($LASTEXITCODE -ne 0) { throw "Docker Compose v2 is required." }

$secretPath=Join-Path $root "secrets"
New-Item -ItemType Directory -Force $secretPath | Out-Null
$backend=Join-Path $secretPath "backend.env"
$ledger=Join-Path $secretPath "wallos-ledger.env"
$envFile=Join-Path $root ".env"
if ((Test-Path $backend) -xor (Test-Path $ledger)) {
  throw "Only one secret exists. Stop to avoid overwriting existing data."
}
$utf8=New-Object System.Text.UTF8Encoding($false)
$nl=[Environment]::NewLine
function New-Secret {
  $bytes=New-Object byte[] 48
  $rng=[Security.Cryptography.RandomNumberGenerator]::Create()
  try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
  return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
}
if (-not (Test-Path $backend)) {
  $device=New-Secret; $ledgerToken=New-Secret; $admin=New-Secret
  $be=@(
    "FINANCE_PUBLIC_URL=$PublicBase"
    "FINANCE_DEVICE_TOKEN=$device"
    "FINANCE_ADMIN_TOKEN=$admin"
    "FINANCE_WALLOS_LEDGER_TOKEN=$ledgerToken"
    "FINANCE_WALLOS_OWNER_ID=$OwnerId"
    "FINANCE_TIMEZONE=Asia/Shanghai"
    "FINANCE_OAUTH_CALLBACK_HOSTS=chatgpt.com,chat.openai.com"
  ) -join $nl
  $wl=@(
    "FINANCE_WALLOS_LEDGER_TOKEN=$ledgerToken"
    "FINANCE_LEDGER_API_URL=http://finance-api:8767/api/v1/wallos-ledger"
    "FINANCE_WALLOS_OWNER_ID=$OwnerId"
    "FINANCE_TIMEZONE=Asia/Shanghai"
  ) -join $nl
  [IO.File]::WriteAllText($backend,$be+$nl,$utf8)
  [IO.File]::WriteAllText($ledger,$wl+$nl,$utf8)
  Write-Host "Created private configuration: keep secrets/backend.env outside Git."
} else {
  $current=Select-String -Path $backend -Pattern '^FINANCE_PUBLIC_URL=' | Select-Object -First 1
  if (-not $current -or $current.Line -ne "FINANCE_PUBLIC_URL=$PublicBase") {
    throw "Existing setup uses another URL. Do not overwrite old secrets."
  }
  Write-Host "Using existing credentials and persistent SQLite Docker volumes."
}
if (-not (Test-Path $envFile)) {
  [IO.File]::WriteAllText($envFile,
    ("FINANCE_API_PORT=8767","WALLOS_PORT=18082","FINANCE_TIMEZONE=Asia/Shanghai" -join $nl)+$nl,$utf8)
}
& docker compose config --quiet
if ($LASTEXITCODE -ne 0) { throw "Compose configuration invalid" }
& docker compose up -d --build
if ($LASTEXITCODE -ne 0) { throw "Docker Compose failed" }
& docker compose ps
Write-Host "Windows local dashboard: http://127.0.0.1:18082/"
Write-Host "Android HTTPS upload and download: $PublicBase"
Write-Host "Use FINANCE_DEVICE_TOKEN from secrets/backend.env as the Android sync token."
Write-Host "Register the first Wallos owner privately before sharing any URL."

if ($ConfigureTailscale) {
  $ts=Get-Command tailscale -ErrorAction SilentlyContinue
  if (-not $ts) {
    $candidate="C:\Program Files\Tailscale\tailscale.exe"
    if (Test-Path $candidate) { $ts=$candidate }
  }
  if (-not $ts) { throw "Install and sign in to Tailscale on Windows and Android first." }
  & $ts serve --bg --https=443 --set-path=/finance http://127.0.0.1:8767
  if ($LASTEXITCODE -ne 0) { throw "Failed serving API; check tailscale serve status" }
  & $ts serve --bg --https=443 --set-path=/finance/wallos http://127.0.0.1:18082
  if ($LASTEXITCODE -ne 0) { throw "Failed serving Wallos; check tailscale serve status" }
  & $ts serve status
  Write-Host "Verify the URL on Android with Tailscale enabled."
}
