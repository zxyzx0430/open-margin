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
