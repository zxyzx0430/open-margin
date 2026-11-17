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

    Image collection:
      * Built images come from compose services with a `build:` key under the
        windows-public-sync profile.
      * Base images come from image-only compose services (all profiles), every
        Dockerfile FROM line, and the explicit nginx/python/Wallos defaults.
      * Missing base images are pulled explicitly; a failed pull stops with a
        clear error before docker save.

    Use -DryRun to print the built/base image plan without building, pulling,
    saving, or creating any output. Use -ForceLegacyComposeConfig to exercise
    the YAML fallback (`config --services` + per-service config) in CI.

    compose config needs the referenced env files to exist. The script creates
    temporary .env / secrets/backend.env / secrets/wallos-ledger.env placeholders
    before any compose call and removes only the files it created in finally.

    Note: the setup wizard can import this bundle from the environment screen
    (`select-offline-file` / `import-offline`). A successful import records
    `offlineImported=true`, and the next compose start uses `--no-build`.
    The setup CLI still supports an explicit `deploy --no-build`.
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
    [switch]$KeepStaging,
    [switch]$DryRun,
    [switch]$ForceLegacyComposeConfig
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

function Invoke-ComposeCommand {
    param(
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$Description
    )
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & $script:DockerPath @Arguments 2>&1 | Out-String
        if ($LASTEXITCODE -ne 0) {
            throw ($Description + ' failed (exit ' + $LASTEXITCODE + '): ' + $output.Trim())
        }
        return $output
    } finally {
        $ErrorActionPreference = $previous
    }
}

function New-ComposeArguments {
    param(
        [switch]$AllProfiles,
        [switch]$NoProfiles
    )
    $arguments = @('compose', '--project-name', $ProjectName)
    if (-not $NoProfiles) {
        if ($AllProfiles) {
            $arguments += @('--profile', '*')
        } else {
            $arguments += @('--profile', 'windows-public-sync')
        }
    }
    return $arguments
}

function Get-ComposeServiceInfoFromJson {
    param([switch]$AllProfiles)
    $arguments = @(New-ComposeArguments -AllProfiles:$AllProfiles) + @('config', '--format', 'json')
    $output = Invoke-ComposeCommand -Arguments $arguments -Description 'docker compose config --format json'
    try {
        $config = $output | ConvertFrom-Json
    } catch {
        throw ('docker compose config --format json returned invalid JSON: ' + $_.Exception.Message + ' Output: ' + $output.Trim())
    }

    $result = New-Object System.Collections.Generic.List[object]
    foreach ($property in @($config.services.PSObject.Properties)) {
        $service = $property.Value
        $buildProperty = $service.PSObject.Properties['build']
        $imageProperty = $service.PSObject.Properties['image']
        $context = ''
        $dockerfile = 'Dockerfile'
        if ($buildProperty) {
            $build = $buildProperty.Value
            if ($build -is [string]) {
                $context = $build
            } else {
                if ($build.PSObject.Properties['context']) { $context = [string]$build.context }
                if ($build.PSObject.Properties['dockerfile']) { $dockerfile = [string]$build.dockerfile }
            }
        }
        $result.Add([pscustomobject]@{
            Name = [string]$property.Name
            HasBuild = ($null -ne $buildProperty)
            Image = $(if ($imageProperty) { [string]$imageProperty.Value } else { '' })
            Context = $context
            Dockerfile = $dockerfile
        })
    }
    return $result.ToArray()
}

function Get-ComposeServiceInfoFromYaml {
    param([switch]$AllProfiles)
    $baseArguments = @(New-ComposeArguments -AllProfiles:$AllProfiles)
    $serviceOutput = $null
    try {
        $serviceOutput = Invoke-ComposeCommand -Arguments (@($baseArguments) + @('config', '--services')) -Description 'docker compose config --services'
    } catch {
        $profileError = $_.Exception.Message
        # Older Compose releases may not support profiles or the wildcard
        # profile. Default (unprofiled) services still give us finance-api and
        # wallos; nginx/python/Wallos bases come from the explicit defaults.
        $baseArguments = @(New-ComposeArguments -NoProfiles)
        try {
            $serviceOutput = Invoke-ComposeCommand -Arguments (@($baseArguments) + @('config', '--services')) -Description 'docker compose config --services (without profiles)'
        } catch {
            throw ('docker compose config --services failed. With profile: ' + $profileError + ' | Without profile: ' + $_.Exception.Message)
        }
    }
    $names = @($serviceOutput -split "`r?`n" |
        ForEach-Object { $_.Trim() } |
        Where-Object { $_ -and $_ -match '^[A-Za-z0-9][A-Za-z0-9_.-]*$' })

    $result = New-Object System.Collections.Generic.List[object]
    foreach ($name in $names) {
        $text = Invoke-ComposeCommand -Arguments (@($baseArguments) + @('config', $name)) -Description ('docker compose config ' + $name)
        # `compose config <service>` returns the whole model on current Compose
        # releases, not just the requested service. Scope the inspection to the
        # requested service block so a sibling's build/image key cannot leak in.
        $serviceText = $text
        if ($text -match '(?m)^services:') {
            $serviceBlock = [regex]::Match(
                $text,
                ('(?ms)^  ' + [regex]::Escape($name) + ':\s*\r?\n(.*?)(?=^  [A-Za-z0-9_.-]+:\s*$|^[A-Za-z])')
            )
            if (-not $serviceBlock.Success) {
                throw ('cannot locate service ''' + $name + ''' in docker compose config output: ' + $text.Trim())
            }
            $serviceText = $serviceBlock.Groups[1].Value
        }
        $hasBuild = ($serviceText -match '(?m)^\s{2,}build:\s*$')
        $image = ''
        $imageMatch = [regex]::Match($serviceText, '(?m)^\s{2,}image:\s*["'']?([^"''\r\n]+)["'']?\s*$')
        if ($imageMatch.Success) { $image = $imageMatch.Groups[1].Value.Trim() }

        $context = ''
        $dockerfile = 'Dockerfile'
        if ($hasBuild) {
            $buildBlock = [regex]::Match($serviceText, '(?ms)^(\s{2,})build:\s*\r?\n((?:\1\s+.*\r?\n?)*)')
            if ($buildBlock.Success) {
                $block = $buildBlock.Groups[2].Value
                $contextMatch = [regex]::Match($block, '(?m)^\s+context:\s*["'']?([^"''\r\n]+)["'']?\s*$')
                if ($contextMatch.Success) { $context = $contextMatch.Groups[1].Value.Trim() }
                $dockerfileMatch = [regex]::Match($block, '(?m)^\s+dockerfile:\s*["'']?([^"''\r\n]+)["'']?\s*$')
                if ($dockerfileMatch.Success) { $dockerfile = $dockerfileMatch.Groups[1].Value.Trim() }
            }
        }

        $result.Add([pscustomobject]@{
            Name = $name
            HasBuild = $hasBuild
            Image = $image
            Context = $context
            Dockerfile = $dockerfile
        })
    }
    return $result.ToArray()
}

function Get-ComposeServiceInfo {
    param([switch]$AllProfiles)
    if (-not $ForceLegacyComposeConfig) {
        try {
            return @(Get-ComposeServiceInfoFromJson -AllProfiles:$AllProfiles)
        } catch {
            $jsonError = $_.Exception.Message
            Write-Warning ('JSON compose inspection failed; falling back to YAML: ' + $jsonError)
            try {
                return @(Get-ComposeServiceInfoFromYaml -AllProfiles:$AllProfiles)
            } catch {
                throw ('docker compose config failed. JSON attempt: ' + $jsonError + ' | YAML fallback: ' + $_.Exception.Message)
            }
        }
    }
    return @(Get-ComposeServiceInfoFromYaml -AllProfiles:$AllProfiles)
}

function Get-ImagePlan {
    # Built images come only from services enabled by the build profile.
    $built = New-Object System.Collections.Generic.List[string]
    $dockerfiles = New-Object System.Collections.Generic.List[string]
    foreach ($service in @(Get-ComposeServiceInfo)) {
        if (-not $service.HasBuild) { continue }
        $builtImage = if ($service.Image) { $service.Image } else { $ProjectName + '-' + $service.Name }
        if ($builtImage -and -not $built.Contains($builtImage)) { $built.Add($builtImage) }

        $context = $service.Context
        if (-not $context) { $context = '.' }
        $dockerfile = $service.Dockerfile
        if (-not $dockerfile) { $dockerfile = 'Dockerfile' }
        $dockerfilePath = if ([IO.Path]::IsPathRooted($dockerfile)) { $dockerfile } else { Join-Path $context $dockerfile }
        if (Test-Path $dockerfilePath) { $dockerfiles.Add($dockerfilePath) }
    }

    # Base images come from every image-only service in every profile, every
    # build Dockerfile FROM line, and the explicit defaults
    # (nginx/python/Wallos).
    $base = New-Object System.Collections.Generic.List[string]
    foreach ($service in @(Get-ComposeServiceInfo -AllProfiles)) {
        if ($service.HasBuild) { continue }
        if ($service.Image -and -not $base.Contains($service.Image)) { $base.Add($service.Image) }
    }
    foreach ($image in @(Get-DockerfileBaseImages -Paths @($dockerfiles))) {
        if ($image -and -not $base.Contains($image)) { $base.Add($image) }
    }
    foreach ($image in @($BaseImages)) {
        if ($image -and -not $base.Contains($image)) { $base.Add($image) }
    }
    foreach ($image in @($built)) {
        if ($base.Contains($image)) { [void]$base.Remove($image) }
    }

    return [pscustomobject]@{ Built = @($built); Base = @($base) }
}

function Get-DockerfileBaseImages {
    param([string[]]$Paths = @())
    $result = New-Object System.Collections.Generic.List[string]
    $candidates = @($Paths)
    if ($candidates.Count -eq 0) {
        $candidates = @(
            (Join-Path $repoRoot 'backend/Dockerfile'),
            (Join-Path $repoRoot 'deploy/wallos-extension/Dockerfile')
        )
    }
    foreach ($dockerfile in $candidates) {
        if (-not $dockerfile -or -not (Test-Path $dockerfile)) { continue }
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

if (-not $Version) { $Version = Resolve-BundleVersion '' }
if (-not $OutputDir) { $OutputDir = Join-Path $repoRoot 'releases/offline' }
$OutputDir = [IO.Path]::GetFullPath($OutputDir)
$bundleName = 'OpenMargin-Offline-' + $Version + '.tar.gz'
$bundlePath = Join-Path $OutputDir $bundleName
$shaPath = $bundlePath + '.sha256'
$manifestPath = Join-Path $OutputDir ('OpenMargin-Offline-' + $Version + '.manifest.json')

if ((Test-Path $bundlePath) -and -not $Force -and -not $DryRun) {
    Write-Step ('bundle already exists: ' + $bundlePath)
    Write-Step 'use -Force to rebuild it'
    if (Test-Path $shaPath) { Get-Content -Path $shaPath -Raw | Write-Output }
    exit 0
}

$dockerCommand = Get-Command docker.exe -ErrorAction SilentlyContinue
if (-not $dockerCommand) { $dockerCommand = Get-Command docker -ErrorAction SilentlyContinue }
if (-not $dockerCommand) { throw 'docker was not found. Install Docker Desktop with Linux containers first.' }
$script:DockerPath = $dockerCommand.Source

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
        (Join-Path $repoRoot '.env') = @(
            '# offline-bundle-placeholder',
            'FINANCE_API_PORT=8767',
            'WALLOS_PORT=18082',
            'GATEWAY_PORT=18768',
            'FINANCE_TIMEZONE=Asia/Shanghai'
        ) -join "`n"
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

    $plan = Get-ImagePlan
    $builtText = if (@($plan.Built).Count -gt 0) { @($plan.Built) -join ', ' } else { '(none)' }
    $baseText = if (@($plan.Base).Count -gt 0) { @($plan.Base) -join ', ' } else { '(none)' }
    Write-Step ('built images: ' + $builtText)
    Write-Step ('base images: ' + $baseText)

    if ($DryRun) {
        Write-Output 'DRY RUN: no build, pull, docker save or bundle output'
        Write-Output ('built=' + (@($plan.Built) -join ','))
        Write-Output ('base=' + (@($plan.Base) -join ','))
        return
    }

    $previousEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & $script:DockerPath info 2>$null | Out-Null
    $dockerReady = ($LASTEXITCODE -eq 0)
    $ErrorActionPreference = $previousEap
    if (-not $dockerReady) { throw 'Docker engine is not running. Start Docker Desktop and retry.' }

    if (-not (Test-Path $OutputDir)) { New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null }

    if (-not $SkipBuild) {
        Write-Step ('building compose project ' + $ProjectName + ' (profile windows-public-sync)')
        Invoke-Docker -Arguments @('compose', '--project-name', $ProjectName, '--profile', 'windows-public-sync', 'build')
    } else {
        Write-Step 'skipping build (-SkipBuild)'
    }

    foreach ($image in @($plan.Built)) {
        if (-not (Test-ImagePresent -Image $image)) {
            throw ('build did not produce image: ' + $image + '. Run without -SkipBuild and check the compose build output.')
        }
    }

    foreach ($image in @($plan.Base)) {
        if (Test-ImagePresent -Image $image) { continue }
        Write-Step ('pulling missing base image ' + $image)
        try {
            Invoke-Docker -Arguments @('pull', $image)
        } catch {
            throw ('cannot pull base image ''' + $image + '''. Check Docker Hub access/proxy or load it locally, then retry. ' + $_.Exception.Message)
        }
        if (-not (Test-ImagePresent -Image $image)) {
            throw ('base image ''' + $image + ''' is still missing after docker pull.')
        }
    }

    $images = New-Object System.Collections.Generic.List[string]
    foreach ($image in @($plan.Built) + @($plan.Base)) {
        if ($image -and -not $images.Contains($image)) { $images.Add($image) }
    }

    foreach ($image in @($images)) {
        if (-not (Test-ImagePresent -Image $image)) {
            throw ('image is missing before docker save: ' + $image)
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
        '3. Open the setup wizard ("清衡设置"), go to the environment screen,',
        '   and use "我已下载，导入" to select this .tar.gz. The wizard verifies',
        '   the SHA256, extracts it safely and runs docker load for you.',
        '',
        '   Manual alternative: tar -xzf the archive, docker load -i ./images.tar,',
        '   then start the wizard. A successful wizard import records',
        '   offlineImported=true and the next deploy uses --no-build.',
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
