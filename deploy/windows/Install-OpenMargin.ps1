# Local-only: powershell -ExecutionPolicy Bypass -File .\deploy\windows\Install-OpenMargin.ps1
# Optional phone sync: rerun with -PublicBase https://YOUR-PC.YOUR-TAILNET.ts.net/finance -ConfigureTailscale
param(
  [string]$PublicBase="https://local.openmargin.invalid/finance",
  [switch]$ConfigureTailscale,
  [ValidateSet("ApiOnly","PrivateFull")][string]$ExposureMode="ApiOnly",
  [int]$OwnerId=1
)
$ErrorActionPreference="Stop"
$root=(Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Set-Location $root
if ($PublicBase -notmatch '^https://[a-zA-Z0-9.-]+/finance/?$') {
  throw "PublicBase must look like https://windows-pc.example.ts.net/finance"
}
$PublicBase=$PublicBase.TrimEnd('/')
# A local-only installation needs neither a Tailscale address nor a domain.
# Only enforce a real Tailscale host when explicitly enabling the remote entry.
if ($ConfigureTailscale -and
    ($PublicBase -eq "https://local.openmargin.invalid/finance" -or
     ([uri]$PublicBase).Host -notlike "*.ts.net")) {
  throw "To enable private Tailscale Serve use a real *.ts.net HTTPS /finance address."
}
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
if (-not $PSBoundParameters.ContainsKey("PublicBase") -and (Test-Path $backend)) {
  $existingUrl=Select-String -Path $backend -Pattern '^FINANCE_PUBLIC_URL=' | Select-Object -First 1
  if ($existingUrl) { $PublicBase=$existingUrl.Line.Substring("FINANCE_PUBLIC_URL=".Length).TrimEnd('/') }
}
if ($PublicBase -notmatch '^https://[a-zA-Z0-9.-]+/finance/?$' -or
    ($ConfigureTailscale -and
      ($PublicBase -eq "https://local.openmargin.invalid/finance" -or
       ([uri]$PublicBase).Host -notlike "*.ts.net"))) {
  throw "Use a real *.ts.net HTTPS /finance URL to enable phone sync."
}
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
  if (-not $current) { throw "Missing FINANCE_PUBLIC_URL in existing private configuration." }
  if ($current.Line -ne "FINANCE_PUBLIC_URL=$PublicBase") {
    # A local-only install may opt into private HTTPS on a later day.
    # The first wizard pass initializes containers without exposing Serve,
    # so the explicitly requested new *.ts.net URL must be accepted then.
    # Any previously configured real server URL stays immutable.
    if ($current.Line -ne "FINANCE_PUBLIC_URL=https://local.openmargin.invalid/finance" -or
        -not $PSBoundParameters.ContainsKey("PublicBase") -or
        ([uri]$PublicBase).Host -notlike "*.ts.net") {
      throw "Existing setup uses another URL. Do not overwrite existing server identity."
    }
    $lines=[IO.File]::ReadAllLines($backend)
    $replaced=0
    $lines=@($lines | ForEach-Object {
      if ($_ -eq $current.Line) { $replaced++; "FINANCE_PUBLIC_URL=$PublicBase" }
      else { $_ }
    })
    if ($replaced -ne 1) { throw "Unexpected private configuration layout." }
    [IO.File]::WriteAllText($backend,($lines -join $nl)+$nl,$utf8)
    Write-Host "Promoted local deployment to requested private HTTPS address."
  }
  Write-Host "Using existing credentials and persistent SQLite Docker volumes."
}
if (-not (Test-Path $envFile)) {
  [IO.File]::WriteAllText($envFile,
    ("FINANCE_API_PORT=8767","WALLOS_PORT=18082","FINANCE_TIMEZONE=Asia/Shanghai" -join $nl)+$nl,$utf8)
}
& docker compose config --quiet
if ($LASTEXITCODE -ne 0) { throw "Compose configuration invalid" }
if ($ExposureMode -eq "ApiOnly") {
  & docker compose --profile windows-private-sync up -d --build
} else {
  & docker compose up -d --build
}
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
  # The first user must be registered over localhost, before sharing on the tailnet.
  & docker compose exec -T wallos php -r '$db=new SQLite3("/var/www/html/db/wallos.db");$count=$db->querySingle("SELECT COUNT(*) FROM user");exit(($count>=1)?0:1);'
  if ($LASTEXITCODE -ne 0) {
    throw "Register the first Wallos owner on http://127.0.0.1:18082/ before enabling Tailscale."
  }
  # Tailscale Serve is node-wide. Never reset or overwrite another app's
  # routes, including routes from an older Open Margin install.
  $marker=Join-Path $secretPath "tailscale-exposure-mode.txt"
  if (Test-Path $marker) {
    $prior=(Get-Content $marker -Raw).Trim()
    if ($prior -ne $ExposureMode) {
      throw "Existing mode is $prior. Switching exposure requires an explicit migration; existing Serve routes unchanged."
    }
  }
  $serveJson=((& $ts serve status --json 2>$null) -join "")
  if ($LASTEXITCODE -ne 0) {
    throw "Unable to inspect existing Tailscale Serve config; refusing changes."
  }
  # Empty Serve output may still contain empty Web/TCP objects.
  $serveData=$null
  if ($serveJson.Trim() -notin @("","null")) {
    try { $serveData=$serveJson | ConvertFrom-Json }
    catch { throw "Invalid Tailscale Serve JSON; refusing changes." }
  }
  $nonempty=$false
  if ($null -ne $serveData) {
    foreach ($field in @("TCP","Web","Services")) {
      $entry=$serveData.PSObject.Properties[$field]
      if ($null -ne $entry -and $null -ne $entry.Value) {
        $value=$entry.Value
        if (($value -is [array] -and $value.Count -gt 0) -or
            ($value.PSObject.Properties.Count -gt 0)) { $nonempty=$true }
      }
    }
  }
  # Do not accept a foreign route just because our port appears somewhere.
  # Check every configured web handler and TCP listener before touching Serve.
  if ($nonempty) {
    if ($ExposureMode -eq "ApiOnly") {
      $allowedRoutes=@{"/"="http://127.0.0.1:18768"}
    } else {
      $allowedRoutes=@{
        "/finance"="http://127.0.0.1:8767"
        "/finance/wallos"="http://127.0.0.1:18082"
      }
    }
    $seen=0
    if ($null -ne $serveData.Services -and
        $serveData.Services.PSObject.Properties.Count -gt 0) {
      throw "Other Tailscale Services exist; do not modify Serve automatically."
    }
    foreach ($listener in @($serveData.TCP.PSObject.Properties)) {
      if ($listener.Name -ne "443" -or -not $listener.Value.HTTPS) {
        throw "Foreign TCP listener exists; Serve configuration unchanged."
      }
    }
    foreach ($site in @($serveData.Web.PSObject.Properties)) {
      foreach ($route in @($site.Value.Handlers.PSObject.Properties)) {
        if (-not $allowedRoutes.ContainsKey($route.Name) -or
            $route.Value.Proxy -ne $allowedRoutes[$route.Name]) {
          throw "Unknown Tailscale Serve route exists; will not overwrite another app."
        }
        $seen++
      }
    }
    if ($seen -ne $allowedRoutes.Count) {
      throw "Existing Tailscale Serve configuration is not owned by this mode."
    }
    if ($null -ne $serveData.AllowFunnel) {
      foreach ($item in @($serveData.AllowFunnel.PSObject.Properties)) {
        if ($item.Value) { throw "Funnel exposure detected; refusing changes." }
      }
    }
  }
  if ($ExposureMode -eq "ApiOnly") {
    # Root proxy is safe ONLY because the local gateway defaults to deny.
    & $ts serve --bg --https=443 http://127.0.0.1:18768
    if ($LASTEXITCODE -ne 0) { throw "Failed to enable private API gateway" }
  } else {
    & $ts serve --bg --https=443 --set-path=/finance http://127.0.0.1:8767
    if ($LASTEXITCODE -ne 0) { throw "Failed serving API" }
    & $ts serve --bg --https=443 --set-path=/finance/wallos http://127.0.0.1:18082
    if ($LASTEXITCODE -ne 0) { throw "Failed serving Wallos" }
  }
  [IO.File]::WriteAllText($marker,$ExposureMode+$nl,$utf8)
  & $ts serve status
  Write-Host "Verify /finance/health and authenticated /finance/api/v2/sync/changes on Android."
  Write-Host "ApiOnly forbids remote Wallos; browser management stays at localhost:18082."

}
