#Requires -Version 5.1
<#
.SYNOPSIS
    Build the optional offline Docker image bundle for Open Margin.

.DESCRIPTION
    Builds the Windows public-sync compose services, saves the built images and
    the base images used by compose.yaml into a single images.tar, then writes a
    compressed Release asset plus a SHA256 file and a manifest.

    The bundle is a separate GitHub Release asset; it is NOT embedded in
    OpenMargin-Setup.exe. Users may download or forward it manually.

    Safety:
      * Never runs `docker compose down`, `volume rm`, `system prune` or `-v`.
      * Only deletes files this script created (temporary placeholder secrets
        and its own staging directory).
      * Re-running with -Force overwrites only the bundle outputs.

    Note: the setup CLI supports `open-margin-setup.ps1 deploy --no-build`, so
    a loaded bundle can be used without rebuilding. The web wizard still runs
    `docker compose up --build` by default; fully offline web-wizard installs
    are not verified in v1.
#>
param(
    [string]$Version = '',
    [string]$OutputDir = '',
    [string]$ProjectName = 'personal-finance-system',
    [string[]]$BaseImages = @(
        'python:3.11-slim',
        'nginx:1.28-alpine',
        'bellamy/wallos@sha256:0006879778f31a962a3873f7682514b92a3c7665dcbc4764fcc241ad968eaccd'
    ),
    [switch]$SkipBuild,
    [switch]$Force,
    [switch]$KeepStaging
)

$ErrorActionPreference = 'Stop'

$setupRoot = $PSScriptRoot
$repoRoot = (Resolve-Path (Join-Path $setupRoot '../../..')).Path
$composePath = Join-Path $repoRoot 'compose.yaml'
if (-not (Test-Path $composePath)) { throw ('compose.yaml not found: ' + $composePath) }

function Write-Step {
    param([string]$Message)
    Write-Host ('[offline-bundle] ' + $Message)
}

function Resolve-BundleVersion {
    param([string]$Requested)
    if ($Requested) { return $Requested.TrimStart('v') }
    $git = Get-Command git.exe -ErrorAction SilentlyContinue
    if (-not $git) { $git = Get-Command git -ErrorAction SilentlyContinue }
    if ($git) {
        try {
            $tag = (& $git.Source -C $repoRoot describe --tags --always 2>$null | Select-Object -First 1)
            if ($LASTEXITCODE -eq 0 -and $tag) { return ([string]$tag).Trim().TrimStart('v') }
        } catch { }
    }
    return '1.3.0'
}

function Invoke-Docker {
    param([string[]]$Arguments)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & $script:DockerPath @Arguments 2>&1 | Out-String
        if (-not [string]::IsNullOrWhiteSpace($output)) { Write-Host $output.TrimEnd() }
        if ($LASTEXITCODE -ne 0) {
            throw ('docker ' + ($Arguments -join ' ') + ' failed with exit code ' + $LASTEXITCODE)
        }
    } finally {
        $ErrorActionPreference = $previous
    }
}

function Test-ImagePresent {
    param([string]$Image)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $script:DockerPath image inspect $Image 2>$null | Out-Null
        return ($LASTEXITCODE -eq 0)
    } catch {
        return $false
    } finally {
        $ErrorActionPreference = $previous
    }
}

function Get-ComposeImages {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & $script:DockerPath compose --project-name $ProjectName --profile windows-public-sync config --images 2>$null
        if ($LASTEXITCODE -ne 0) { return @() }
        return @($output | Where-Object { $_ -and $_.Trim() -match '^[A-Za-z0-9][A-Za-z0-9._/:@-]*$' } | ForEach-Object { $_.Trim() })
    } finally {
        $ErrorActionPreference = $previous
    }
}

function Get-DockerfileBaseImages {
    $result = New-Object System.Collections.Generic.List[string]
    foreach ($relative in @('backend/Dockerfile', 'deploy/wallos-extension/Dockerfile')) {
        $dockerfile = Join-Path $repoRoot $relative
        if (-not (Test-Path $dockerfile)) { continue }
        foreach ($line in @(Get-Content -Path $dockerfile -ErrorAction SilentlyContinue)) {
            $match = [regex]::Match([string]$line, '^\s*FROM\s+([^\s]+)', [Text.RegularExpressions.RegexOptions]::IgnoreCase)
            if ($match.Success) {
                $image = $match.Groups[1].Value
                if ($image -and $image -ne 'scratch' -and -not $result.Contains($image)) {
                    $result.Add($image)
                }
            }
        }
    }
    return @($result)
}

function Get-ComposeDeclaredImages {
    $result = New-Object System.Collections.Generic.List[string]
    $text = Get-Content -Path $composePath -Raw -ErrorAction SilentlyContinue
    if (-not $text) { return @() }
    foreach ($match in [regex]::Matches([string]$text, '(?m)^\s*image:\s*["'']?([^"''\s#]+)')) {
        $image = $match.Groups[1].Value.Trim()
        if ($image -and -not $image.Contains('${') -and -not $result.Contains($image)) {
            $result.Add($image)
        }
    }
    return @($result)
}

if (-not $Version) { $Version = Resolve-BundleVersion '' }
if (-not $OutputDir) { $OutputDir = Join-Path $repoRoot 'releases/offline' }
$OutputDir = [IO.Path]::GetFullPath($OutputDir)
$bundleName = 'OpenMargin-Offline-' + $Version + '.tar.gz'
$bundlePath = Join-Path $OutputDir $bundleName
$shaPath = $bundlePath + '.sha256'
$manifestPath = Join-Path $OutputDir ('OpenMargin-Offline-' + $Version + '.manifest.json')

if ((Test-Path $bundlePath) -and -not $Force) {
    Write-Step ('bundle already exists: ' + $bundlePath)
    Write-Step 'use -Force to rebuild it'
    if (Test-Path $shaPath) { Get-Content -Path $shaPath -Raw | Write-Output }
    exit 0
}

$dockerCommand = Get-Command docker.exe -ErrorAction SilentlyContinue
if (-not $dockerCommand) { $dockerCommand = Get-Command docker -ErrorAction SilentlyContinue }
if (-not $dockerCommand) { throw 'docker was not found. Install Docker Desktop with Linux containers first.' }
$script:DockerPath = $dockerCommand.Source

$previousEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& $script:DockerPath info 2>$null | Out-Null
$dockerReady = ($LASTEXITCODE -eq 0)
$ErrorActionPreference = $previousEap
if (-not $dockerReady) { throw 'Docker engine is not running. Start Docker Desktop and retry.' }

if (-not (Test-Path $OutputDir)) { New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null }

$createdSecretFiles = New-Object System.Collections.Generic.List[string]
$createdSecretsDir = $false
$staging = Join-Path $OutputDir ('.staging-' + [guid]::NewGuid().ToString('N'))
$utf8 = New-Object System.Text.UTF8Encoding($false)

try {
    # compose.yaml validates env_file existence even for `build`. Create
    # clearly-marked placeholders only when the real files are absent, and
    # remove them in finally. Never overwrite real secrets.
    $secretsDir = Join-Path $repoRoot 'secrets'
    if (-not (Test-Path $secretsDir)) {
        New-Item -ItemType Directory -Force -Path $secretsDir | Out-Null
        $createdSecretsDir = $true
    }
    $placeholders = @{
        (Join-Path $secretsDir 'backend.env') = @(
            'FINANCE_DEVICE_TOKEN=offline-bundle-placeholder',
            'FINANCE_ADMIN_TOKEN=offline-bundle-placeholder',
            'FINANCE_WALLOS_LEDGER_TOKEN=offline-bundle-placeholder',
            'FINANCE_WALLOS_OWNER_ID=1',
            'FINANCE_TIMEZONE=Asia/Shanghai'
        ) -join "`n"
        (Join-Path $secretsDir 'wallos-ledger.env') = @(
            'FINANCE_WALLOS_LEDGER_TOKEN=offline-bundle-placeholder',
            'FINANCE_LEDGER_API_URL=http://finance-api:8767/api/v1/wallos-ledger',
            'FINANCE_WALLOS_OWNER_ID=1',
            'FINANCE_TIMEZONE=Asia/Shanghai'
        ) -join "`n"
    }
    foreach ($path in $placeholders.Keys) {
        if (-not (Test-Path $path)) {
            [IO.File]::WriteAllText($path, $placeholders[$path] + "`n", $utf8)
            $createdSecretFiles.Add($path)
        }
    }

    if (-not $SkipBuild) {
        Write-Step ('building compose project ' + $ProjectName + ' (profile windows-public-sync)')
        Invoke-Docker -Arguments @('compose', '--project-name', $ProjectName, '--profile', 'windows-public-sync', 'build')
    } else {
        Write-Step 'skipping build (-SkipBuild)'
    }

    $images = New-Object System.Collections.Generic.List[string]
    foreach ($image in @(Get-ComposeImages)) {
        if (-not $images.Contains($image)) { $images.Add($image) }
    }
    if ($images.Count -eq 0) {
        # Compose v2 normally names built images <project>-<service>.
        foreach ($image in @($ProjectName + '-finance-api', $ProjectName + '-wallos')) {
            if (-not $images.Contains($image)) { $images.Add($image) }
        }
    }

    # Fail before pulling large base images if the build did not produce the
    # expected service images.
    foreach ($image in @($images)) {
        if (-not (Test-ImagePresent -Image $image)) {
            throw ('built image is missing after build: ' + $image + ' (run without -SkipBuild)')
        }
    }

    foreach ($baseImage in @($BaseImages) + @(Get-DockerfileBaseImages) + @(Get-ComposeDeclaredImages)) {
        if (-not $baseImage) { continue }
        if (-not (Test-ImagePresent -Image $baseImage)) {
            Write-Step ('pulling base image ' + $baseImage)
            Invoke-Docker -Arguments @('pull', $baseImage)
        }
        if (-not $images.Contains($baseImage)) { $images.Add($baseImage) }
    }

    foreach ($image in @($images)) {
        if (-not (Test-ImagePresent -Image $image)) {
            throw ('image is missing after build: ' + $image)
        }
    }

    New-Item -ItemType Directory -Force -Path $staging | Out-Null
    $imagesTar = Join-Path $staging 'images.tar'
    Write-Step ('saving ' + $images.Count + ' image(s) to images.tar')
    Invoke-Docker -Arguments (@('save', '-o', $imagesTar) + @($images))
    $tarInfo = Get-Item $imagesTar
    if ($tarInfo.Length -le 0) { throw 'docker save produced an empty images.tar' }

    $previousEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $dockerVersion = (& $script:DockerPath version --format '{{.Server.Version}}' 2>$null | Select-Object -First 1)
    $ErrorActionPreference = $previousEap
    $manifest = [ordered]@{
        schema = 1
        kind = 'open-margin-offline-docker-images'
        version = $Version
        createdAt = (Get-Date).ToUniversalTime().ToString('o')
        projectName = $ProjectName
        composeProfiles = @('windows-public-sync')
        images = @($images)
        dockerServerVersion = [string]$dockerVersion
        notes = 'Contains built Open Margin images and their base images. No secrets or database volumes.'
    }
    [IO.File]::WriteAllText((Join-Path $staging 'manifest.json'), ($manifest | ConvertTo-Json -Depth 6), $utf8)

    $restore = @(
        '# Open Margin offline Docker bundle',
        '',
        'This archive contains images.tar (built Open Margin images + base images).',
        'It does not contain secrets, databases or Docker volumes.',
        '',
        '1. Verify the SHA256 printed by the release page:',
        '   Get-FileHash ./OpenMargin-Offline-' + $Version + '.tar.gz -Algorithm SHA256',
        '',
        '2. Extract images.tar, then load it:',
        '   tar -xzf ./OpenMargin-Offline-' + $Version + '.tar.gz',
        '   docker load -i ./images.tar',
        '',
        '3. Start the setup wizard from the Start Menu ("清衡设置") and continue.',
        '',
        'v1 caveat: the web wizard currently runs `docker compose up --build`;',
        'the setup CLI supports `deploy --no-build` to use these loaded images.',
        'Fully offline web-wizard installation is not verified in v1.',
        ''
    ) -join "`r`n"
    [IO.File]::WriteAllText((Join-Path $staging 'RESTORE.md'), $restore, $utf8)

    $tar = Get-Command tar.exe -ErrorAction SilentlyContinue
    if (-not $tar) { $tar = Get-Command tar -ErrorAction SilentlyContinue }
    if (-not $tar) { throw 'tar was not found; cannot create the compressed bundle.' }

    Write-Step ('compressing to ' + $bundleName)
    & $tar.Source -czf $bundlePath -C $staging .
    if ($LASTEXITCODE -ne 0) { throw ('tar failed with exit code ' + $LASTEXITCODE) }

    $hash = (Get-FileHash -Path $bundlePath -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText($shaPath, ($hash + '  ' + $bundleName + "`r`n"), $utf8)
    [IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 6), $utf8)

    $size = [math]::Round((Get-Item $bundlePath).Length / 1MB, 2)
    Write-Step ('built: ' + $bundlePath)
    Write-Step ('size: ' + $size + ' MiB')
    Write-Step ('sha256: ' + $hash)
    Write-Output $bundlePath
    Write-Output $shaPath
    Write-Output $manifestPath
} finally {
    foreach ($path in $createdSecretFiles) {
        try {
            if (Test-Path $path) {
                $text = Get-Content -Path $path -Raw -ErrorAction SilentlyContinue
                if ($text -and $text.Contains('offline-bundle-placeholder')) {
                    Remove-Item -Force $path -ErrorAction SilentlyContinue
                }
            }
        } catch { }
    }
    if ($createdSecretsDir) {
        try {
            $remaining = Get-ChildItem -Path (Join-Path $repoRoot 'secrets') -Force -ErrorAction SilentlyContinue
            if (-not $remaining) { Remove-Item -Force (Join-Path $repoRoot 'secrets') -ErrorAction SilentlyContinue }
        } catch { }
    }
    if (-not $KeepStaging -and (Test-Path $staging)) {
        try { Remove-Item -Recurse -Force $staging -ErrorAction SilentlyContinue } catch { }
    }
}
