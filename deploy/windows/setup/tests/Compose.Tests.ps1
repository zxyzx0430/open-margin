BeforeAll {
    $script:modulePath = Join-Path $PSScriptRoot '..\OpenMargin.Setup.psd1'
    Import-Module $script:modulePath -Force -DisableNameChecking
    $script:stateRoot = Join-Path $TestDrive 'compose-tests'
    Initialize-OmPaths -StateRoot $script:stateRoot -RepoRoot (Join-Path $TestDrive 'repo')
    $script:repoRoot = Join-Path $TestDrive 'repo'
    New-Item -ItemType Directory -Force -Path $script:repoRoot | Out-Null
    Set-Content -Path (Join-Path $script:repoRoot 'compose.yaml') -Value 'services: {}' -Encoding UTF8
    $script:fakeDocker = Join-Path $TestDrive 'docker.cmd'
    Set-Content -Path $script:fakeDocker -Encoding ASCII -Value @(
        '@echo off',
        'echo Image test Building 1>&2',
        'exit /b 0'
    )
}

Describe 'Invoke-OmDockerCompose under ErrorActionPreference Stop' {
    BeforeAll {
        Mock -ModuleName OpenMargin.Setup Get-OmCommandPath {
            param($Name)
            return $script:fakeDocker
        }
    }

    It 'does not turn docker stderr progress into a terminating error' {
        $previous = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Stop'
            $result = Invoke-OmDockerCompose -Arguments @('ps') -RepoRoot $script:repoRoot -ProjectName 'test'
        } finally {
            $ErrorActionPreference = $previous
        }
        $result.exitCode | Should -Be 0
        $result.output | Should -Match 'Image test Building'
    }
}

Describe 'Wallos owner check command' {
    It 'keeps the php -r snippet free of double quotes' {
        $source = Get-Content -Path (Join-Path $PSScriptRoot '..\lib\Wallos.ps1') -Raw -Encoding UTF8
        $line = ($source -split "`r?`n" | Where-Object { $_ -match '\$php\s*=' } | Select-Object -First 1)
        $line | Should -Not -BeNullOrEmpty
        $line | Should -Not -Match '"'
        $line | Should -Match "SELECT COUNT\(\*\) FROM user"
    }
}

Describe 'Image pull preflight' {
    BeforeEach {
        Mock -ModuleName OpenMargin.Setup New-OmSecrets { return [pscustomobject]@{} }
        Mock -ModuleName OpenMargin.Setup Get-OmCommandPath { return 'docker.exe' }
        Mock -ModuleName OpenMargin.Setup Invoke-OmDockerCompose { return [pscustomobject]@{ exitCode = 0; output = 'ok' } }
    }
    It 'stops before compose and suggests offline import after a registry failure' {
        Mock -ModuleName OpenMargin.Setup Invoke-OmDependencyCommand { return [pscustomobject]@{ exitCode = 1; output = 'registry unreachable' } }
        $result = Invoke-OmComposeUp -RepoRoot $script:repoRoot -SkipHealth
        $result.ok | Should -BeFalse
        $result.step | Should -Be 'pull'
        $result.output | Should -Match '离线'
        Should -Invoke Invoke-OmDockerCompose -ModuleName OpenMargin.Setup -Times 0
    }
    It 'waits and retries a daemon failure only once' {
        $script:pullCalls = 0
        Mock -ModuleName OpenMargin.Setup Wait-OmDockerReady { return $true }
        Mock -ModuleName OpenMargin.Setup Invoke-OmDependencyCommand {
            $script:pullCalls++
            return [pscustomobject]@{ exitCode = 1; output = 'cannot connect to the docker daemon' }
        }
        $result = Invoke-OmComposeUp -RepoRoot $script:repoRoot -SkipHealth
        $result.ok | Should -BeFalse
        Should -Invoke Wait-OmDockerReady -ModuleName OpenMargin.Setup -Times 1 -Exactly
        Should -Invoke Invoke-OmDependencyCommand -ModuleName OpenMargin.Setup -Times 2 -Exactly
    }
}

Describe 'Secrets ACL verification' {
    It 'restricts existing secret files in a path with spaces and Chinese characters' {
        $repo = Join-Path $TestDrive '用户 数据'
        New-Item -ItemType Directory -Force -Path $repo | Out-Null
        $first = New-OmSecrets -RepoRoot $repo
        $second = New-OmSecrets -RepoRoot $repo
        $first.created | Should -BeTrue
        $second.created | Should -BeFalse
        $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
        foreach ($path in @($first.secretsPath, $first.backendEnv, $first.ledgerEnv)) {
            $rules = @((Get-Acl -LiteralPath $path).GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]))
            $rules.Count | Should -BeGreaterThan 0
            @($rules | Where-Object { $_.IdentityReference.Value -eq $sid -and $_.AccessControlType -eq 'Allow' -and ($_.FileSystemRights -band [Security.AccessControl.FileSystemRights]::ReadData) }).Count | Should -BeGreaterThan 0
        }
    }
}

Describe 'ACL verification when PowerShell Security autoload is unavailable' {
    It 'still verifies a secrets file beneath a Chinese spaced path' {
        $dir = Join-Path $TestDrive '中文 用户 数据'
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
        $path = Join-Path $dir 'private secrets.txt'
        [IO.File]::WriteAllText($path, 'test')
        Mock -ModuleName OpenMargin.Setup Get-Acl { throw 'Microsoft.PowerShell.Security is unavailable in this scope' }
        { Set-OmFileAcl -Path $path } | Should -Not -Throw
    }
}

Describe 'DACL security descriptor verification' {
    It 'rejects a missing or empty DACL' {
        $module = Get-Module -Name OpenMargin.Setup -ErrorAction Stop
        $sid = 'S-1-5-21-1-2-3-1001'
        $missing = & $module {
            param($descriptor, $targetSid)
            Test-OmDaclSecurityDescriptor -SecurityDescriptor $descriptor -Sid $targetSid
        } 'O:SYG:SY' $sid
        $missing.HasDacl | Should -BeFalse
        $empty = & $module {
            param($descriptor, $targetSid)
            Test-OmDaclSecurityDescriptor -SecurityDescriptor $descriptor -Sid $targetSid
        } 'O:SYG:SYD:' $sid
        $empty.HasDacl | Should -BeFalse
    }

    It 'requires read permission for the installing SID and rejects a matching read deny' {
        $module = Get-Module -Name OpenMargin.Setup -ErrorAction Stop
        $sid = 'S-1-5-21-1-2-3-1001'
        $inspect = {
            param($descriptor, $targetSid)
            Test-OmDaclSecurityDescriptor -SecurityDescriptor $descriptor -Sid $targetSid
        }
        $allowed = & $module $inspect ('D:(A;;FR;;;' + $sid + ')') $sid
        $allowed.HasDacl | Should -BeTrue
        $allowed.HasUserRead | Should -BeTrue
        $writeOnly = & $module $inspect ('D:(A;;FW;;;' + $sid + ')') $sid
        $writeOnly.HasDacl | Should -BeTrue
        $writeOnly.HasUserRead | Should -BeFalse
        $denied = & $module $inspect ('D:(A;;FR;;;' + $sid + ')(D;;FR;;;' + $sid + ')') $sid
        $denied.HasUserRead | Should -BeFalse
        $denied.HasUserReadDeny | Should -BeTrue
    }
}