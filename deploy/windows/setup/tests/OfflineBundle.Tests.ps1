BeforeAll {
    $script:modulePath = Join-Path $PSScriptRoot '..\OpenMargin.Setup.psd1'
    Import-Module $script:modulePath -Force -DisableNameChecking
    $script:repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
    $script:stateRoot = Join-Path $TestDrive 'offline-state'
    Initialize-OmPaths -StateRoot $script:stateRoot -RepoRoot $script:repoRoot
    $script:ports = [pscustomobject]@{ api = 8767; wallos = 18082; gateway = 18768; setup = 18770 }
    # System.Formats.Tar is available in PowerShell 7 / .NET 7+. The
    # traversal/symlink fixtures use it; skip those two cases on 5.1 while
    # keeping the entry-name validator and all other tests active.
    $script:hasTarWriter = $null -ne ('System.Formats.Tar.TarWriter' -as [type])
    $script:tar = (Get-Command tar.exe -ErrorAction SilentlyContinue).Source
    if (-not $script:tar) { $script:tar = (Get-Command tar -ErrorAction SilentlyContinue).Source }

    function New-TestOfflineBundle {
        param(
            [Parameter(Mandatory = $true)][string]$Root,
            [string]$Version = '1.3.0',
            [string]$Kind = 'open-margin-offline-docker-images',
            [int]$Schema = 1,
            [string[]]$Images = @('personal-finance-system-finance-api', 'personal-finance-system-wallos'),
            [switch]$NoImages,
            [switch]$NoSha256
        )
        $staging = Join-Path $Root ('staging-' + [guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Force -Path $staging | Out-Null
        $manifest = [ordered]@{
            schema = $Schema
            kind = $Kind
            version = $Version
            images = @($Images)
            notes = 'pester fixture'
        }
        $utf8 = New-Object System.Text.UTF8Encoding($false)
        [IO.File]::WriteAllText((Join-Path $staging 'manifest.json'), ($manifest | ConvertTo-Json -Depth 6), $utf8)
        if (-not $NoImages) {
            [IO.File]::WriteAllBytes((Join-Path $staging 'images.tar'), [byte[]](1..64))
        }
        $bundle = Join-Path $Root ('OpenMargin-Offline-' + $Version + '.tar.gz')
        & $script:tar -czf $bundle -C $staging . | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'fixture tar failed' }
        if (-not $NoSha256) {
            $hash = (Get-FileHash -LiteralPath $bundle -Algorithm SHA256).Hash.ToLowerInvariant()
            [IO.File]::WriteAllText($bundle + '.sha256', ($hash + '  ' + (Split-Path -Leaf $bundle) + "`r`n"), $utf8)
        }
        return $bundle
    }

    function New-FakeDocker {
        param([Parameter(Mandatory = $true)][string]$Root)
        if (-not (Test-Path $Root)) { New-Item -ItemType Directory -Force -Path $Root | Out-Null }
        $log = Join-Path $Root 'docker-args.log'
        $cmd = Join-Path $Root 'docker.cmd'
        $body = @(
            '@echo off',
            ('echo %*>>"' + $log + '"'),
            'if /I "%1"=="info" exit /b 0',
            'if /I "%1"=="load" (',
            '  echo Loaded image: fake-offline-image',
            '  exit /b 0',
            ')',
            'exit /b 0'
        ) -join "`r`n"
        [IO.File]::WriteAllText($cmd, $body, [Text.Encoding]::ASCII)
        return [pscustomobject]@{ path = $cmd; log = $log }
    }

    function New-TestTarGz {
        param(
            [Parameter(Mandatory = $true)][string]$Path,
            [Parameter(Mandatory = $true)][string]$EntryName,
            [ValidateSet('file', 'symlink')][string]$Type = 'file',
            [string]$LinkName = ''
        )
        $tarPath = $Path + '.tar'
        $stream = [IO.File]::Create($tarPath)
        $writer = [System.Formats.Tar.TarWriter]::new($stream, [System.Formats.Tar.TarEntryFormat]::Ustar)
        try {
            if ($Type -eq 'symlink') {
                $entry = [System.Formats.Tar.UstarTarEntry]::new([System.Formats.Tar.TarEntryType]::SymbolicLink, $EntryName)
                $entry.LinkName = $LinkName
            } else {
                $entry = [System.Formats.Tar.UstarTarEntry]::new([System.Formats.Tar.TarEntryType]::RegularFile, $EntryName)
                $bytes = [Text.Encoding]::UTF8.GetBytes('evil')
                $entry.DataStream = [IO.MemoryStream]::new($bytes)
            }
            $writer.WriteEntry($entry)
        } finally {
            $writer.Dispose()
            $stream.Dispose()
        }
        $input = [IO.File]::OpenRead($tarPath)
        $output = [IO.File]::Create($Path)
        $gzip = [IO.Compression.GZipStream]::new($output, [IO.Compression.CompressionMode]::Compress)
        try { $input.CopyTo($gzip) } finally { $gzip.Dispose(); $output.Dispose(); $input.Dispose() }
        Remove-Item -LiteralPath $tarPath -Force -ErrorAction SilentlyContinue
        return $Path
    }
}

Describe 'Offline bundle' {
BeforeEach {
    Save-OmState (New-OmDefaultState -RepoRoot $script:repoRoot)
}

Describe 'Offline bundle release info' {
    It 'returns the 1.3.0 asset URL and known SHA256' {
        $info = Get-OmOfflineBundleInfo -Version 'v1.3.0'
        $info.version | Should -Be '1.3.0'
        $info.assetName | Should -Be 'OpenMargin-Offline-1.3.0.tar.gz'
        $info.url | Should -Be 'https://github.com/zxyzx0430/open-margin/releases/download/v1.3.0/OpenMargin-Offline-1.3.0.tar.gz'
        $info.sha256 | Should -Be 'd74f570191302c023639fa2eeb52526f53a49a6a6212e26c3e185e026f543783'
        $info.knownRelease | Should -BeTrue
        $info.sizeBytes | Should -Be 161126294
    }

    It 'falls back to 1.3.0 and still builds a URL for unknown versions' {
        $fallback = Get-OmOfflineBundleInfo
        $fallback.version | Should -Be '1.3.0'
        $unknown = Get-OmOfflineBundleInfo -Version '9.9.9'
        $unknown.version | Should -Be '9.9.9'
        $unknown.url | Should -Match '/v9\.9\.9/OpenMargin-Offline-9\.9\.9\.tar\.gz$'
        $unknown.sha256 | Should -BeNullOrEmpty
        $unknown.knownRelease | Should -BeFalse
    }
}

Describe 'Offline bundle SHA256 verification' {
    It 'reads a sibling .sha256 file' {
        $root = Join-Path $TestDrive 'sha-ok'
        New-Item -ItemType Directory -Force -Path $root | Out-Null
        $bundle = New-TestOfflineBundle -Root $root
        $expected = Get-OmOfflineBundleExpectedHash -Path $bundle -Version '1.3.0'
        $expected.source | Should -Be 'sha256-file'
        $hash = Test-OmOfflineBundleHash -Path $bundle -ExpectedSha256 $expected.sha256
        $hash.ok | Should -BeTrue
        $hash.code | Should -Be 'HASH_OK'
    }

    It 'returns SHA256_MISMATCH and keeps the downloaded file' {
        $root = Join-Path $TestDrive 'sha-bad'
        New-Item -ItemType Directory -Force -Path $root | Out-Null
        $bundle = New-TestOfflineBundle -Root $root
        $bad = ('0' * 64)
        $result = Import-OmOfflineBundle -Path $bundle -Version '1.3.0' -ExpectedSha256 $bad -DryRun
        $result.ok | Should -BeFalse
        $result.code | Should -Be 'SHA256_MISMATCH'
        Test-Path -LiteralPath $bundle | Should -BeTrue
    }

    It 'reports SHA256_UNAVAILABLE for an unknown version without a .sha256 file' {
        $root = Join-Path $TestDrive 'sha-missing'
        New-Item -ItemType Directory -Force -Path $root | Out-Null
        $bundle = New-TestOfflineBundle -Root $root -Version '9.9.9' -NoSha256
        $result = Import-OmOfflineBundle -Path $bundle -Version '9.9.9' -DryRun
        $result.ok | Should -BeFalse
        $result.code | Should -Be 'SHA256_UNAVAILABLE'
        Test-Path -LiteralPath $bundle | Should -BeTrue
    }
}

Describe 'Offline bundle safe extraction' {
    It 'rejects absolute, traversal, drive and stream entry names' {
        Test-OmOfflineArchiveEntryName -Name './images.tar' | Should -BeTrue
        Test-OmOfflineArchiveEntryName -Name 'manifest.json' | Should -BeTrue
        Test-OmOfflineArchiveEntryName -Name '../evil.txt' | Should -BeFalse
        Test-OmOfflineArchiveEntryName -Name 'dir/../evil.txt' | Should -BeFalse
        Test-OmOfflineArchiveEntryName -Name '/etc/passwd' | Should -BeFalse
        Test-OmOfflineArchiveEntryName -Name 'C:\Windows\win.ini' | Should -BeFalse
        Test-OmOfflineArchiveEntryName -Name 'dir\evil.txt' | Should -BeFalse
        Test-OmOfflineArchiveEntryName -Name 'file:stream' | Should -BeFalse
    }

    It 'extracts a safe bundle into a temp destination' {
        $root = Join-Path $TestDrive 'expand-ok'
        New-Item -ItemType Directory -Force -Path $root | Out-Null
        $bundle = New-TestOfflineBundle -Root $root
        $destination = Join-Path $root 'extracted'
        $result = Expand-OmOfflineArchive -Path $bundle -Destination $destination -TarPath $script:tar
        $result.ok | Should -BeTrue
        $result.code | Should -Be 'TREE_OK'
        Test-Path -LiteralPath (Join-Path $destination 'manifest.json') | Should -BeTrue
        Test-Path -LiteralPath (Join-Path $destination 'images.tar') | Should -BeTrue
    }

    It 'rejects a tar.gz containing a path traversal entry before extraction' -Skip:(-not $script:hasTarWriter) {
        $root = Join-Path $TestDrive 'expand-evil'
        New-Item -ItemType Directory -Force -Path $root | Out-Null
        $archive = Join-Path $root 'evil.tar.gz'
        New-TestTarGz -Path $archive -EntryName '../evil.txt' | Out-Null
        $destination = Join-Path $root 'out'
        $result = Expand-OmOfflineArchive -Path $archive -Destination $destination -TarPath $script:tar
        $result.ok | Should -BeFalse
        $result.code | Should -Be 'ARCHIVE_UNSAFE'
        @($result.issues) | Should -Contain 'unsafe entry: ../evil.txt'
        Test-Path -LiteralPath (Join-Path $root 'evil.txt') | Should -BeFalse
    }

    It 'rejects a tar.gz containing a symlink entry' -Skip:(-not $script:hasTarWriter) {
        $root = Join-Path $TestDrive 'expand-link'
        New-Item -ItemType Directory -Force -Path $root | Out-Null
        $archive = Join-Path $root 'link.tar.gz'
        New-TestTarGz -Path $archive -EntryName 'link' -Type symlink -LinkName 'C:\Windows\win.ini' | Out-Null
        $destination = Join-Path $root 'out'
        $result = Expand-OmOfflineArchive -Path $archive -Destination $destination -TarPath $script:tar
        $result.ok | Should -BeFalse
        $result.code | Should -Be 'ARCHIVE_UNSAFE'
    }
}

Describe 'Offline bundle manifest validation' {
    It 'accepts a matching manifest' {
        $manifest = [pscustomobject]@{ schema = 1; kind = 'open-margin-offline-docker-images'; version = '1.3.0'; images = @('a', 'b') }
        $result = Test-OmOfflineBundleManifest -Manifest $manifest -ExpectedVersion '1.3.0'
        $result.ok | Should -BeTrue
        @($result.images).Count | Should -Be 2
    }

    It 'rejects wrong kind, schema, empty images and version mismatch' {
        (Test-OmOfflineBundleManifest -Manifest ([pscustomobject]@{ schema = 1; kind = 'other'; version = '1.3.0'; images = @('a') })).code | Should -Be 'MANIFEST_INVALID'
        (Test-OmOfflineBundleManifest -Manifest ([pscustomobject]@{ schema = 2; kind = 'open-margin-offline-docker-images'; version = '1.3.0'; images = @('a') })).code | Should -Be 'MANIFEST_INVALID'
        (Test-OmOfflineBundleManifest -Manifest ([pscustomobject]@{ schema = 1; kind = 'open-margin-offline-docker-images'; version = '1.3.0'; images = @() })).code | Should -Be 'MANIFEST_INVALID'
        (Test-OmOfflineBundleManifest -Manifest ([pscustomobject]@{ schema = 1; kind = 'open-margin-offline-docker-images'; version = '1.2.0'; images = @('a') }) -ExpectedVersion '1.3.0').code | Should -Be 'VERSION_MISMATCH'
    }

    It 'rejects a bundle whose manifest has the wrong kind' {
        $root = Join-Path $TestDrive 'manifest-bad'
        New-Item -ItemType Directory -Force -Path $root | Out-Null
        $bundle = New-TestOfflineBundle -Root $root -Kind 'not-an-offline-bundle'
        $docker = New-FakeDocker -Root (Join-Path $root 'docker')
        $result = Import-OmOfflineBundle -Path $bundle -Version '1.3.0' -DockerPath $docker.path
        $result.ok | Should -BeFalse
        $result.code | Should -Be 'MANIFEST_INVALID'
        Test-Path -LiteralPath $bundle | Should -BeTrue
    }
}

Describe 'Offline bundle import' {
    It 'loads images with a fake docker and records offlineImported' {
        $root = Join-Path $TestDrive 'import-ok'
        New-Item -ItemType Directory -Force -Path $root | Out-Null
        $bundle = New-TestOfflineBundle -Root $root
        $docker = New-FakeDocker -Root (Join-Path $root 'docker')
        $result = Import-OmOfflineBundle -Path $bundle -Version '1.3.0' -DockerPath $docker.path
        $result.ok | Should -BeTrue
        $result.code | Should -Be 'OFFLINE_IMPORTED'
        $result.noBuild | Should -BeTrue
        @($result.images).Count | Should -Be 2
        $state = Read-OmState
        $state.offlineImported | Should -BeTrue
        $state.offlineBundleVersion | Should -Be '1.3.0'
        $state.offlineBundleName | Should -Be 'OpenMargin-Offline-1.3.0.tar.gz'
        Test-Path -LiteralPath $bundle | Should -BeTrue
        $log = Get-Content -LiteralPath $docker.log -Raw
        $log | Should -Match 'load -i'
        $log | Should -Match 'images\.tar'
    }

    It 'reports DOCKER_MISSING before extracting when docker is absent' {
        $root = Join-Path $TestDrive 'import-nodocker'
        New-Item -ItemType Directory -Force -Path $root | Out-Null
        $bundle = New-TestOfflineBundle -Root $root
        $missing = Join-Path $root 'missing-docker.exe'
        $result = Import-OmOfflineBundle -Path $bundle -Version '1.3.0' -DockerPath $missing
        $result.ok | Should -BeFalse
        $result.code | Should -Be 'DOCKER_MISSING'
        $result.action_required | Should -BeTrue
        (Read-OmState).offlineImported | Should -BeFalse
        Test-Path -LiteralPath $bundle | Should -BeTrue
    }

    It 'reports a missing images.tar as invalid' {
        $root = Join-Path $TestDrive 'import-noimages'
        New-Item -ItemType Directory -Force -Path $root | Out-Null
        $bundle = New-TestOfflineBundle -Root $root -NoImages
        $docker = New-FakeDocker -Root (Join-Path $root 'docker')
        $result = Import-OmOfflineBundle -Path $bundle -Version '1.3.0' -DockerPath $docker.path
        $result.ok | Should -BeFalse
        $result.code | Should -Be 'ARCHIVE_UNSAFE'
        $result.detail | Should -Match 'missing required entry: images.tar'
    }

    It 'returns a dry-run result without extracting or changing state' {
        $root = Join-Path $TestDrive 'import-dryrun'
        New-Item -ItemType Directory -Force -Path $root | Out-Null
        $bundle = New-TestOfflineBundle -Root $root
        $result = Import-OmOfflineBundle -Path $bundle -Version '1.3.0' -DryRun
        $result.ok | Should -BeTrue
        $result.code | Should -Be 'DRY_RUN'
        $result.dryRun | Should -BeTrue
        $result.sha256 | Should -Match '^[0-9a-f]{64}$'
        (Read-OmState).offlineImported | Should -BeFalse
    }
}

Describe 'Offline bundle web action contract' {
    It 'returns MISSING_PATH for import-offline without a path' {
        $result = Invoke-OmWebAction -Action 'import-offline' -Params ([pscustomobject]@{}) -Ports $script:ports -StateRoot $script:stateRoot -RepoRoot $script:repoRoot -DryRun
        $result.ok | Should -BeFalse
        $result.code | Should -Be 'MISSING_PATH'
    }

    It 'returns a dry-run result for import-offline' {
        $root = Join-Path $TestDrive 'action-import'
        New-Item -ItemType Directory -Force -Path $root | Out-Null
        $bundle = New-TestOfflineBundle -Root $root
        $result = Invoke-OmWebAction -Action 'import-offline' -Params ([pscustomobject]@{ path = $bundle; version = '1.3.0' }) -Ports $script:ports -StateRoot $script:stateRoot -RepoRoot $script:repoRoot -DryRun
        $result.ok | Should -BeTrue
        $result.code | Should -Be 'DRY_RUN'
    }

    It 'does not open a picker in non-interactive/dry-run mode' {
        $result = Invoke-OmWebAction -Action 'select-offline-file' -Params ([pscustomobject]@{}) -Ports $script:ports -StateRoot $script:stateRoot -RepoRoot $script:repoRoot -DryRun
        $result.ok | Should -BeFalse
        $result.code | Should -Be 'PICKER_UNAVAILABLE'
    }

    It 'automatically passes --no-build to deploy after an offline import' {
        $state = Read-OmState
        $state | Add-Member -NotePropertyName offlineImported -NotePropertyValue $true -Force
        Save-OmState $state
        Mock -ModuleName OpenMargin.Setup Invoke-OmRemoteDeploy {
            param($RepoRoot, $Ports, $AdminUser, $AdminPassword, $AdminEmail, $ProjectName, $SkipFunnel, $InstallWatchdog, $NoBuild, $DryRun)
            return [pscustomobject]@{ ok = $true; noBuild = [bool]$NoBuild }
        }
        $result = Invoke-OmWebAction -Action 'deploy' -Params ([pscustomobject]@{ adminUser = 'alex'; adminPassword = 'password123'; ackPublic = $true }) -Ports $script:ports -StateRoot $script:stateRoot -RepoRoot $script:repoRoot
        $result.ok | Should -BeTrue
        $result.noBuild | Should -BeTrue
    }
}
}
