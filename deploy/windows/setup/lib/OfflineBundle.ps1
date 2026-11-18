# Open Margin setup engine: offline Docker image bundle.
#
# The release asset is a .tar.gz containing manifest.json and images.tar.
# Importing it never deletes the user's downloaded file: the archive is
# validated in place, extracted into a private temp directory, and removed
# only after `docker load` finishes. A successful import records
# offlineImported=true so the next compose start uses --no-build.
#
# Windows PowerShell 5.1 compatible.

$script:OmOfflineKnownReleases = @{
    '1.3.0' = [pscustomobject]@{
        sha256    = 'd74f570191302c023639fa2eeb52526f53a49a6a6212e26c3e185e026f543783'
        sizeBytes = 161126294
    }
    '1.3.1' = [pscustomobject]@{
        sha256    = '9b0982e7398eba36d0db86d4d2628442381767dbaed9aed284879bb47725e844'
        sizeBytes = 161124859
    }
}

$script:OmOfflineReleaseBase = 'https://github.com/zxyzx0430/open-margin/releases/download/'

function Resolve-OmOfflineBundleVersion {
    param([string]$Version = '')
    if (-not [string]::IsNullOrWhiteSpace($Version)) {
        return ([string]$Version).Trim().TrimStart('v')
    }
    try {
        $state = Read-OmState
        if ($state -and -not [string]::IsNullOrWhiteSpace([string]$state.engineVersion)) {
            return ([string]$state.engineVersion).TrimStart('v')
        }
    } catch { }
    return '1.3.1'
}

function Get-OmOfflineBundleInfo {
    param([string]$Version = '')
    $resolved = Resolve-OmOfflineBundleVersion -Version $Version
    $assetName = 'OpenMargin-Offline-' + $resolved + '.tar.gz'
    $baseUrl = $script:OmOfflineReleaseBase + 'v' + $resolved + '/'
    $known = $null
    if ($script:OmOfflineKnownReleases.ContainsKey($resolved)) {
        $known = $script:OmOfflineKnownReleases[$resolved]
    }
    return [pscustomobject]@{
        version      = $resolved
        tag          = ('v' + $resolved)
        assetName    = $assetName
        url          = ($baseUrl + $assetName)
        sha256Url    = ($baseUrl + $assetName + '.sha256')
        manifestUrl  = ($baseUrl + 'OpenMargin-Offline-' + $resolved + '.manifest.json')
        sha256       = $(if ($known) { $known.sha256 } else { $null })
        sizeBytes    = $(if ($known) { $known.sizeBytes } else { $null })
        knownRelease = ($null -ne $known)
    }
}

function Get-OmOfflineBundleExpectedHash {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [string]$Version = '',
        [string]$ExpectedSha256 = ''
    )
    if ($ExpectedSha256 -match '^[0-9a-fA-F]{64}$') {
        return [pscustomobject]@{ sha256 = $ExpectedSha256.ToLowerInvariant(); source = 'explicit' }
    }
    $fullPath = [IO.Path]::GetFullPath($Path)
    $info = Get-OmOfflineBundleInfo -Version $Version
    $candidates = New-Object System.Collections.Generic.List[string]
    $candidates.Add($fullPath + '.sha256')
    $directory = Split-Path -Parent $fullPath
    if ($directory) {
        $candidates.Add((Join-Path $directory ($info.assetName + '.sha256')))
    }
    foreach ($candidate in $candidates) {
        if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) { continue }
        try {
            $raw = [IO.File]::ReadAllText($candidate)
        } catch {
            continue
        }
        $match = [regex]::Match($raw, '(?i)\b([0-9a-f]{64})\b')
        if ($match.Success) {
            return [pscustomobject]@{ sha256 = $match.Groups[1].Value.ToLowerInvariant(); source = 'sha256-file' }
        }
    }
    if ($info.sha256) {
        return [pscustomobject]@{ sha256 = [string]$info.sha256; source = 'release' }
    }
    return $null
}

function Test-OmOfflineBundleHash {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$ExpectedSha256
    )
    try {
        $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant()
    } catch {
        return [pscustomobject]@{ ok = $false; code = 'HASH_FAILED'; message = $_.Exception.Message; expected = $ExpectedSha256.ToLowerInvariant(); actual = $null }
    }
    $expected = $ExpectedSha256.ToLowerInvariant()
    return [pscustomobject]@{
        ok       = ($actual -eq $expected)
        code     = $(if ($actual -eq $expected) { 'HASH_OK' } else { 'SHA256_MISMATCH' })
        expected = $expected
        actual   = $actual
    }
}

function Test-OmOfflineArchiveEntryName {
    param([AllowNull()][string]$Name)
    if ([string]::IsNullOrEmpty($Name)) { return $false }
    if ($Name.IndexOf([char]0) -ge 0) { return $false }
    # Windows tar treats both separators as path separators. The bundle is
    # built with forward slashes, so a backslash here is suspicious.
    if ($Name.Contains('\')) { return $false }
    if ($Name.StartsWith('/')) { return $false }
    if ($Name -match '^[A-Za-z]:') { return $false }
    if ($Name.StartsWith('~')) { return $false }
    $trimmed = $Name.TrimEnd('/')
    foreach ($part in $trimmed.Split('/')) {
        if ($part -eq '' -or $part -eq '.') { continue }
        if ($part -eq '..') { return $false }
        # Colons enable NTFS alternate data streams in extracted paths.
        if ($part.Contains(':')) { return $false }
    }
    return $true
}

function Get-OmOfflineTarPath {
    $tar = Get-OmCommandPath 'tar.exe'
    if (-not $tar) { $tar = Get-OmCommandPath 'tar' }
    return $tar
}

function Get-OmOfflineArchiveIssues {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [string]$TarPath = ''
    )
    $ErrorActionPreference = 'Continue'
    $PSNativeCommandUseErrorActionPreference = $false
    if (-not $TarPath) { $TarPath = Get-OmOfflineTarPath }
    if (-not $TarPath) {
        return [pscustomobject]@{ ok = $false; code = 'TAR_MISSING'; issues = @('tar was not found'); entries = @() }
    }
    $issues = New-Object System.Collections.Generic.List[string]
    $rawNames = & $TarPath -tzf $Path 2>&1
    $namesExit = $LASTEXITCODE
    if ($namesExit -ne 0) {
        return [pscustomobject]@{ ok = $false; code = 'ARCHIVE_INVALID'; issues = @('tar -tzf failed: ' + (($rawNames | Out-String).Trim())); entries = @() }
    }
    $names = @($rawNames | ForEach-Object { ([string]$_).TrimEnd("`r") } | Where-Object { $_ -ne '' })
    $rawVerbose = & $TarPath -tvzf $Path 2>&1
    $verboseExit = $LASTEXITCODE
    if ($verboseExit -ne 0) {
        return [pscustomobject]@{ ok = $false; code = 'ARCHIVE_INVALID'; issues = @('tar -tvzf failed: ' + (($rawVerbose | Out-String).Trim())); entries = @() }
    }
    $verboseLines = @($rawVerbose | ForEach-Object { ([string]$_).TrimEnd("`r") } | Where-Object { $_ -ne '' })
    if ($verboseLines.Count -ne $names.Count) {
        $issues.Add('archive listing is ambiguous (name/verbose count mismatch)')
    }
    foreach ($name in $names) {
        if (-not (Test-OmOfflineArchiveEntryName -Name $name)) {
            $issues.Add('unsafe entry: ' + $name)
        }
    }
    foreach ($line in $verboseLines) {
        if ($line -notmatch '^([A-Za-z-])') {
            $issues.Add('unrecognized tar entry type')
            continue
        }
        $kind = $matches[1]
        # Regular files and directories only. Symlinks and hardlinks could
        # redirect an otherwise safe name outside the temp directory.
        if ($kind -ne '-' -and $kind -ne 'd') {
            $issues.Add('unsupported tar entry type: ' + $kind)
        }
    }
    $normalized = @($names | ForEach-Object {
        $n = ([string]$_).TrimEnd('/')
        if ($n.StartsWith('./')) { $n = $n.Substring(2) }
        $n
    })
    foreach ($required in @('manifest.json', 'images.tar')) {
        if ($normalized -notcontains $required) {
            $issues.Add('missing required entry: ' + $required)
        }
    }
    $unique = New-Object System.Collections.Generic.List[string]
    foreach ($issue in $issues) {
        if (-not $unique.Contains($issue)) { $unique.Add($issue) }
    }
    return [pscustomobject]@{
        ok      = ($unique.Count -eq 0)
        code    = $(if ($unique.Count -eq 0) { 'ARCHIVE_OK' } else { 'ARCHIVE_UNSAFE' })
        issues  = @($unique)
        entries = @($names)
    }
}

function Test-OmOfflineExtractedTree {
    param([Parameter(Mandatory = $true)][string]$Destination)
    $root = [IO.Path]::GetFullPath($Destination).TrimEnd('\')
    $prefix = $root + '\'
    $issues = New-Object System.Collections.Generic.List[string]
    try {
        foreach ($item in @(Get-ChildItem -LiteralPath $root -Recurse -Force -ErrorAction Stop)) {
            $full = [IO.Path]::GetFullPath($item.FullName)
            if ($full -ne $root -and -not $full.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
                $issues.Add('extracted path leaves destination: ' + $item.Name)
            }
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                $issues.Add('extracted reparse point: ' + $item.Name)
            }
        }
    } catch {
        $issues.Add('cannot inspect extracted tree: ' + $_.Exception.Message)
    }
    return [pscustomobject]@{
        ok     = ($issues.Count -eq 0)
        code   = $(if ($issues.Count -eq 0) { 'TREE_OK' } else { 'TREE_UNSAFE' })
        issues = @($issues)
    }
}

function Expand-OmOfflineArchive {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Destination,
        [string]$TarPath = ''
    )
    $listing = Get-OmOfflineArchiveIssues -Path $Path -TarPath $TarPath
    if (-not $listing.ok) {
        return [pscustomobject]@{ ok = $false; code = $listing.code; issues = @($listing.issues) }
    }
    if (-not $TarPath) { $TarPath = Get-OmOfflineTarPath }
    if (-not (Test-Path -LiteralPath $Destination)) {
        New-Item -ItemType Directory -Force -Path $Destination | Out-Null
    }
    $ErrorActionPreference = 'Continue'
    $PSNativeCommandUseErrorActionPreference = $false
    $output = & $TarPath -xzf $Path -C $Destination 2>&1
    if ($LASTEXITCODE -ne 0) {
        return [pscustomobject]@{ ok = $false; code = 'EXTRACT_FAILED'; issues = @(('tar -xzf failed: ' + (($output | Out-String).Trim()))) }
    }
    $tree = Test-OmOfflineExtractedTree -Destination $Destination
    return [pscustomobject]@{ ok = $tree.ok; code = $tree.code; issues = @($tree.issues) }
}

function Test-OmOfflineBundleManifest {
    param(
        $Manifest,
        [string]$ExpectedVersion = ''
    )
    if ($null -eq $Manifest) {
        return [pscustomobject]@{ ok = $false; code = 'MANIFEST_INVALID'; message = 'manifest.json 为空。'; images = @(); version = $null }
    }
    if ([string]$Manifest.kind -ne 'open-margin-offline-docker-images') {
        return [pscustomobject]@{ ok = $false; code = 'MANIFEST_INVALID'; message = 'manifest.json 的 kind 不是离线镜像包。'; images = @(); version = $null }
    }
    try {
        if ([int]$Manifest.schema -ne 1) {
            return [pscustomobject]@{ ok = $false; code = 'MANIFEST_INVALID'; message = 'manifest.json 的 schema 版本不受支持。'; images = @(); version = $null }
        }
    } catch {
        return [pscustomobject]@{ ok = $false; code = 'MANIFEST_INVALID'; message = 'manifest.json 缺少 schema。'; images = @(); version = $null }
    }
    if (-not $Manifest.PSObject.Properties['images']) {
        return [pscustomobject]@{ ok = $false; code = 'MANIFEST_INVALID'; message = 'manifest.json 缺少 images 列表。'; images = @(); version = $null }
    }
    $images = @($Manifest.images | ForEach-Object { [string]$_ } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    if ($images.Count -eq 0 -or $images.Count -ne @($Manifest.images).Count) {
        return [pscustomobject]@{ ok = $false; code = 'MANIFEST_INVALID'; message = 'manifest.json 的 images 列表为空或包含空项。'; images = @(); version = $null }
    }
    $manifestVersion = ''
    if ($Manifest.PSObject.Properties['version']) {
        $manifestVersion = ([string]$Manifest.version).Trim().TrimStart('v')
    }
    if (-not $manifestVersion) {
        return [pscustomobject]@{ ok = $false; code = 'MANIFEST_INVALID'; message = 'manifest.json 缺少 version。'; images = @(); version = $null }
    }
    if (-not [string]::IsNullOrWhiteSpace($ExpectedVersion)) {
        $expected = ([string]$ExpectedVersion).Trim().TrimStart('v')
        if ($expected -and $manifestVersion -ne $expected) {
            return [pscustomobject]@{
                ok = $false; code = 'VERSION_MISMATCH'; message = ('离线镜像包版本 ' + $manifestVersion + ' 与向导版本 ' + $expected + ' 不一致。');
                images = @(); version = $manifestVersion
            }
        }
    }
    return [pscustomobject]@{ ok = $true; code = 'MANIFEST_OK'; message = ''; images = @($images); version = $manifestVersion }
}

function Test-OmOfflineDockerReady {
    param([string]$DockerPath = '')
    if (-not $DockerPath) { $DockerPath = Get-OmCommandPath 'docker' }
    if (-not $DockerPath -or -not (Test-Path -LiteralPath $DockerPath -PathType Leaf)) {
        return [pscustomobject]@{ installed = $false; running = $false; path = $null; output = 'docker not found' }
    }
    $command = Invoke-OmDependencyCommand -FilePath $DockerPath -Arguments @('info')
    $running = ($command.exitCode -eq 0)
    $output = $command.output
    return [pscustomobject]@{ installed = $true; running = $running; path = $DockerPath; output = (Protect-OmText $output) }
}

function Get-OmOfflineTrimmedText {
    param([AllowNull()][string]$Text, [int]$MaxLength = 4000)
    if ([string]::IsNullOrEmpty($Text)) { return '' }
    if ($Text.Length -le $MaxLength) { return $Text }
    return ($Text.Substring(0, $MaxLength) + '... (truncated)')
}

function Invoke-OmOfflineDockerLoad {
    param(
        [Parameter(Mandatory = $true)][string]$ImagesTar,
        [string]$DockerPath = '',
        [switch]$DryRun
    )
    $arguments = @('load', '-i', $ImagesTar)
    if (-not $DockerPath) { $DockerPath = Get-OmCommandPath 'docker' }
    if ($DryRun) {
        return [pscustomobject]@{ ok = $true; code = 'DRY_RUN'; exitCode = 0; output = ''; arguments = @($arguments); dockerPath = $DockerPath }
    }
    if (-not $DockerPath -or -not (Test-Path -LiteralPath $DockerPath -PathType Leaf)) {
        return [pscustomobject]@{ ok = $false; code = 'DOCKER_MISSING'; exitCode = -1; output = 'docker not found'; arguments = @($arguments); dockerPath = $null }
    }
    $command = Invoke-OmDependencyCommand -FilePath $DockerPath -Arguments $arguments
    $output = $command.output
    $exitCode = $command.exitCode
    return [pscustomobject]@{
        ok        = ($exitCode -eq 0)
        code      = $(if ($exitCode -eq 0) { 'DOCKER_LOADED' } else { 'DOCKER_LOAD_FAILED' })
        exitCode  = $exitCode
        output    = (Get-OmOfflineTrimmedText -Text (Protect-OmText $output))
        arguments = @($arguments)
        dockerPath = $DockerPath
    }
}

function Set-OmOfflineProgress {
    param([string]$Stage, [int]$Percent = -1, [string]$Message = '')
    try { $null = Set-OmProgress -Stage $Stage -Percent $Percent -Message $Message } catch { }
}

function New-OmOfflineFailure {
    param(
        [string]$Code,
        [string]$Message,
        [string]$Detail = '',
        [bool]$Retryable = $true,
        [bool]$ActionRequired = $true,
        [string]$File = '',
        $Extra = $null
    )
    $result = [ordered]@{
        ok             = $false
        code           = $Code
        message        = $Message
        detail         = (Get-OmOfflineTrimmedText -Text (Protect-OmText $Detail))
        retryable      = $Retryable
        action_required = $ActionRequired
        file           = $File
    }
    if ($null -ne $Extra) {
        foreach ($property in $Extra.PSObject.Properties) {
            $result[$property.Name] = $property.Value
        }
    }
    return [pscustomobject]$result
}

function Import-OmOfflineBundle {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [string]$Version = '',
        [string]$ExpectedSha256 = '',
        [string]$DockerPath = '',
        [switch]$DryRun,
        [switch]$KeepTemp
    )
    $leaf = Split-Path -Leaf $Path
    try {
        $resolved = (Resolve-Path -LiteralPath $Path -ErrorAction Stop).Path
    } catch {
        return (New-OmOfflineFailure -Code 'FILE_NOT_FOUND' -Message '找不到离线镜像包，请重新选择下载好的 .tar.gz 文件。' -File $leaf)
    }
    if (-not (Test-Path -LiteralPath $resolved -PathType Leaf)) {
        return (New-OmOfflineFailure -Code 'FILE_NOT_FOUND' -Message '离线镜像包不是一个文件，请重新选择。' -File $leaf)
    }
    if (-not $resolved.ToLowerInvariant().EndsWith('.tar.gz')) {
        return (New-OmOfflineFailure -Code 'INVALID_FILE_TYPE' -Message '离线镜像包必须是 OpenMargin-Offline-<版本>.tar.gz。' -File $leaf)
    }

    $resolvedVersion = Resolve-OmOfflineBundleVersion -Version $Version
    $hashSpec = Get-OmOfflineBundleExpectedHash -Path $resolved -Version $resolvedVersion -ExpectedSha256 $ExpectedSha256
    if ($null -eq $hashSpec) {
        return (New-OmOfflineFailure -Code 'SHA256_UNAVAILABLE' -Message '找不到可信的 SHA256。请把 Release 旁的 .sha256 文件与离线包放在同一目录后重试。' -File $leaf)
    }

    $progress = -not $DryRun
    if ($progress) { Set-OmOfflineProgress -Stage 'offline-verify' -Percent 8 -Message '校验离线镜像包 SHA256' }
    $hash = Test-OmOfflineBundleHash -Path $resolved -ExpectedSha256 $hashSpec.sha256
    if (-not $hash.ok) {
        return (New-OmOfflineFailure -Code $hash.code -Message 'SHA256 不一致，文件可能没有下载完整或被篡改。请删除后重新下载，不要继续导入。' -Detail (('expected ' + $hash.expected + ' actual ' + $hash.actual)) -Retryable $true -File $leaf -Extra ([pscustomobject]@{ expectedSha256 = $hash.expected; actualSha256 = $hash.actual }))
    }

    if (-not $DryRun) {
        $docker = Test-OmOfflineDockerReady -DockerPath $DockerPath
        if (-not $docker.installed) {
            return (New-OmOfflineFailure -Code 'DOCKER_MISSING' -Message '没有找到 Docker。请先完成环境检查并安装、启动 Docker Desktop，然后再导入离线镜像包。' -Detail $docker.output -File $leaf)
        }
        if (-not $docker.running) {
            return (New-OmOfflineFailure -Code 'DOCKER_NOT_RUNNING' -Message 'Docker 没有运行。请启动 Docker Desktop，等它显示 Engine running 后再导入。' -Detail $docker.output -File $leaf)
        }
    }

    $tarPath = Get-OmOfflineTarPath
    if (-not $tarPath) {
        return (New-OmOfflineFailure -Code 'TAR_MISSING' -Message '系统缺少 tar.exe，无法解压离线镜像包。请升级 Windows 10/11 后重试。' -File $leaf)
    }
    if ($progress) { Set-OmOfflineProgress -Stage 'offline-verify' -Percent 25 -Message '检查压缩包路径是否安全' }
    $listing = Get-OmOfflineArchiveIssues -Path $resolved -TarPath $tarPath
    if (-not $listing.ok) {
        return (New-OmOfflineFailure -Code $listing.code -Message '离线镜像包结构不安全或不完整，已停止导入。' -Detail (@($listing.issues) -join '; ') -File $leaf)
    }
    if ($DryRun) {
        return [pscustomobject]@{
            ok = $true; code = 'DRY_RUN'; dryRun = $true;
            message = '演练完成：SHA256 和压缩包结构通过，没有解压或加载镜像。';
            version = $resolvedVersion; sha256 = $hash.actual; file = $leaf;
            entries = @($listing.entries); noBuild = $true
        }
    }

    $tempRoot = Join-Path ([IO.Path]::GetTempPath()) ('openmargin-offline-' + [guid]::NewGuid().ToString('N'))
    try {
        New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null
        Set-OmOfflineProgress -Stage 'offline-extract' -Percent 40 -Message '安全解压离线镜像包'
        $expanded = Expand-OmOfflineArchive -Path $resolved -Destination $tempRoot -TarPath $tarPath
        if (-not $expanded.ok) {
            return (New-OmOfflineFailure -Code $expanded.code -Message '解压离线镜像包失败，已停止导入。' -Detail (@($expanded.issues) -join '; ') -File $leaf)
        }

        $manifestPath = Join-Path $tempRoot 'manifest.json'
        $imagesTar = Join-Path $tempRoot 'images.tar'
        if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf) -or -not (Test-Path -LiteralPath $imagesTar -PathType Leaf)) {
            return (New-OmOfflineFailure -Code 'MANIFEST_MISSING' -Message '离线镜像包缺少 manifest.json 或 images.tar。' -File $leaf)
        }
        $manifest = $null
        try {
            $manifestRaw = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 -ErrorAction Stop
            $manifest = $manifestRaw | ConvertFrom-Json
        } catch {
            return (New-OmOfflineFailure -Code 'MANIFEST_INVALID' -Message 'manifest.json 无法解析。' -Detail $_.Exception.Message -File $leaf)
        }
        $manifestCheck = Test-OmOfflineBundleManifest -Manifest $manifest -ExpectedVersion $resolvedVersion
        if (-not $manifestCheck.ok) {
            return (New-OmOfflineFailure -Code $manifestCheck.code -Message $manifestCheck.message -File $leaf -Extra ([pscustomobject]@{ version = $manifestCheck.version }))
        }
        $imagesInfo = Get-Item -LiteralPath $imagesTar -ErrorAction SilentlyContinue
        if (-not $imagesInfo -or $imagesInfo.Length -le 0) {
            return (New-OmOfflineFailure -Code 'IMAGES_TAR_INVALID' -Message 'images.tar 为空，离线镜像包可能损坏。' -File $leaf)
        }

        Set-OmOfflineProgress -Stage 'offline-load' -Percent 65 -Message '正在加载 Docker 镜像（可能需要几分钟）'
        $load = Invoke-OmOfflineDockerLoad -ImagesTar $imagesTar -DockerPath $DockerPath
        if (-not $load.ok) {
            return (New-OmOfflineFailure -Code $load.code -Message 'docker load 失败。Docker 可能在加载过程中停止或磁盘空间不足。' -Detail $load.output -File $leaf)
        }

        $state = Read-OmState
        $state | Add-Member -NotePropertyName offlineImported -NotePropertyValue $true -Force
        $state | Add-Member -NotePropertyName offlineBundleVersion -NotePropertyValue $manifestCheck.version -Force
        $state | Add-Member -NotePropertyName offlineImportedAt -NotePropertyValue ((Get-Date).ToString('o')) -Force
        $state | Add-Member -NotePropertyName offlineBundleName -NotePropertyValue $leaf -Force
        $state | Add-Member -NotePropertyName offlineImages -NotePropertyValue @($manifestCheck.images) -Force
        Save-OmState $state
        Set-OmOfflineProgress -Stage 'offline-complete' -Percent 100 -Message '离线镜像包已导入，部署将使用 --no-build'
        Write-OmLog -Level INFO -Message ('offline bundle imported: ' + $leaf + ' images=' + @($manifestCheck.images).Count)
        return [pscustomobject]@{
            ok = $true; code = 'OFFLINE_IMPORTED'; dryRun = $false;
            message = '离线镜像包已导入。下一次部署会直接使用已加载镜像（--no-build），不再重新构建。';
            version = $manifestCheck.version; sha256 = $hash.actual; file = $leaf;
            images = @($manifestCheck.images); noBuild = $true
        }
    } catch {
        return (New-OmOfflineFailure -Code 'IMPORT_EXCEPTION' -Message '导入离线镜像包时发生异常。' -Detail $_.Exception.Message -File $leaf)
    } finally {
        if (-not $KeepTemp -and (Test-Path -LiteralPath $tempRoot)) {
            try { Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue } catch { }
        }
    }
}

function Select-OmOfflineBundleFile {
    param(
        [string]$InitialDirectory = '',
        [string]$Title = '选择清衡离线镜像包',
        [switch]$NonInteractive
    )
    if ($NonInteractive -or -not [Environment]::UserInteractive) {
        return [pscustomobject]@{ ok = $false; code = 'PICKER_UNAVAILABLE'; message = '当前会话无法打开文件选择框，请手动输入离线镜像包路径。'; retryable = $true }
    }
    if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
        return [pscustomobject]@{ ok = $false; code = 'PICKER_UNAVAILABLE'; message = '只有 Windows 支持本机文件选择框。'; retryable = $true }
    }
    try {
        Add-Type -AssemblyName System.Windows.Forms -ErrorAction Stop
    } catch {
        return [pscustomobject]@{ ok = $false; code = 'PICKER_UNAVAILABLE'; message = 'Windows 文件选择组件不可用，请手动输入路径。'; retryable = $true }
    }
    if (-not $InitialDirectory) {
        $downloads = Join-Path ([Environment]::GetFolderPath('UserProfile')) 'Downloads'
        if (Test-Path -LiteralPath $downloads) { $InitialDirectory = $downloads }
        else { $InitialDirectory = [Environment]::GetFolderPath('MyDocuments') }
    }
    $filter = '离线镜像包 (*.tar.gz)|*.tar.gz|所有文件 (*.*)|*.*'
    $apartment = [Threading.Thread]::CurrentThread.GetApartmentState()
    if ($apartment -eq [Threading.ApartmentState]::STA) {
        try {
            $dialog = New-Object System.Windows.Forms.OpenFileDialog
            $dialog.Title = $Title
            $dialog.Filter = $filter
            $dialog.CheckFileExists = $true
            $dialog.Multiselect = $false
            $dialog.RestoreDirectory = $true
            if ($InitialDirectory) { $dialog.InitialDirectory = $InitialDirectory }
            $dialogResult = $dialog.ShowDialog()
            if ($dialogResult -eq [System.Windows.Forms.DialogResult]::OK -and $dialog.FileName) {
                return [pscustomobject]@{ ok = $true; code = 'PICKER_SELECTED'; path = [IO.Path]::GetFullPath($dialog.FileName); message = '' }
            }
            return [pscustomobject]@{ ok = $false; code = 'PICKER_CANCELLED'; message = '没有选择文件。'; retryable = $true }
        } catch {
            return [pscustomobject]@{ ok = $false; code = 'PICKER_FAILED'; message = '打开文件选择框失败，请手动输入路径。'; detail = $_.Exception.Message; retryable = $true }
        }
    }

    # The wizard may run under PowerShell 7 in MTA. Run the dialog in an STA
    # child process and read the chosen path from a temporary file. The child
    # only writes the path; it never receives credentials or setup tokens.
    $outputPath = Join-Path ([IO.Path]::GetTempPath()) ('openmargin-picker-' + [guid]::NewGuid().ToString('N') + '.txt')
    $escapedTitle = $Title.Replace("'", "''")
    $escapedInitial = ([string]$InitialDirectory).Replace("'", "''")
    $escapedOutput = $outputPath.Replace("'", "''")
    $childScript = @(
        "Add-Type -AssemblyName System.Windows.Forms",
        "`$dialog = New-Object System.Windows.Forms.OpenFileDialog",
        "`$dialog.Title = '$escapedTitle'",
        "`$dialog.Filter = '$filter'",
        "`$dialog.CheckFileExists = `$true",
        "`$dialog.Multiselect = `$false",
        "`$dialog.RestoreDirectory = `$true",
        "if ('$escapedInitial') { `$dialog.InitialDirectory = '$escapedInitial' }",
        "if (`$dialog.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK -and `$dialog.FileName) {",
        "  [IO.File]::WriteAllText('$escapedOutput', `$dialog.FileName, (New-Object System.Text.UTF8Encoding(`$false))",
        "}"
    ) -join "`r`n"
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($childScript))
    $powershell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    if (-not (Test-Path -LiteralPath $powershell)) { $powershell = (Get-Process -Id $PID).Path }
    try {
        $process = Start-Process -FilePath $powershell -ArgumentList @('-NoProfile', '-STA', '-EncodedCommand', $encoded) -PassThru -Wait
        if (Test-Path -LiteralPath $outputPath) {
            try {
                $selected = [IO.File]::ReadAllText($outputPath).Trim()
            } finally {
                Remove-Item -LiteralPath $outputPath -Force -ErrorAction SilentlyContinue
            }
            if ($selected) {
                return [pscustomobject]@{ ok = $true; code = 'PICKER_SELECTED'; path = [IO.Path]::GetFullPath($selected); message = '' }
            }
        }
        if ($process -and $process.ExitCode -ne 0) {
            return [pscustomobject]@{ ok = $false; code = 'PICKER_FAILED'; message = '文件选择框启动失败，请手动输入路径。'; retryable = $true }
        }
        return [pscustomobject]@{ ok = $false; code = 'PICKER_CANCELLED'; message = '没有选择文件。'; retryable = $true }
    } catch {
        return [pscustomobject]@{ ok = $false; code = 'PICKER_FAILED'; message = '打开文件选择框失败，请手动输入路径。'; detail = $_.Exception.Message; retryable = $true }
    } finally {
        if (Test-Path -LiteralPath $outputPath) { Remove-Item -LiteralPath $outputPath -Force -ErrorAction SilentlyContinue }
    }
}
