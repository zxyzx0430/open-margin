BeforeAll {
    Import-Module (Join-Path $PSScriptRoot '..\OpenMargin.Setup.psd1') -Force -DisableNameChecking
    $script:repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
}

Describe 'Watchdog installation artifacts' {
    It 'installs hidden launchers into a state directory without registering a task in dry-run' {
        $stateRoot = Join-Path $TestDrive 'watchdog'
        Initialize-OmPaths -StateRoot $stateRoot -RepoRoot $script:repoRoot
        $result = Install-OmWatchdog -StateRoot $stateRoot -RepoRoot $script:repoRoot -DryRun
        $result.ok | Should -BeTrue
        $result.code | Should -Be 'DRY_RUN'
        $dir = Join-Path $stateRoot 'watchdog'
        Test-Path (Join-Path $dir 'Watchdog.ps1') | Should -BeTrue
        Test-Path (Join-Path $dir 'Watchdog.vbs') | Should -BeTrue
        Test-Path (Join-Path $dir 'watchdog-config.json') | Should -BeTrue
        $config = Get-Content -Path (Join-Path $dir 'watchdog-config.json') -Raw -Encoding UTF8 | ConvertFrom-Json
        $config.workingDirectory | Should -Be $script:repoRoot
        $vbs = Get-Content -Path (Join-Path $dir 'Watchdog.vbs') -Raw -Encoding UTF8
        $vbs | Should -Match 'CurrentDirectory'
    }

    It 'does not contain volume-destroying compose commands' {
        $script = Get-Content -Path (Join-Path $script:repoRoot 'deploy\windows\setup\Watchdog.ps1') -Raw -Encoding UTF8
        $script | Should -Not -Match 'compose\s+down\s+-v'
        $script | Should -Match 'NeedsLogin'
        $script | Should -Match 'AllowFunnel'
    }

    It 'skips all repairs while paused in one-shot mode' {
        $stateRoot = Join-Path $TestDrive 'watchdog-paused'
        Initialize-OmPaths -StateRoot $stateRoot -RepoRoot $script:repoRoot
        $null = Install-OmWatchdog -StateRoot $stateRoot -RepoRoot $script:repoRoot -DryRun
        $null = Set-OmWatchdogPaused -Paused $true -Minutes 60
        $watchdog = Join-Path (Join-Path $stateRoot 'watchdog') 'Watchdog.ps1'
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $watchdog -StateRoot $stateRoot -Once -DryRun | Out-Null
        $LASTEXITCODE | Should -Be 0
        $log = Get-Content -Path (Join-Path $stateRoot 'logs\watchdog.log') -Raw -Encoding UTF8
        $log | Should -Match 'paused'
    }
}

Describe 'Watchdog pause state' {
    It 'records and clears the pause window' {
        $stateRoot = Join-Path $TestDrive 'watchdog-state'
        Initialize-OmPaths -StateRoot $stateRoot -RepoRoot $script:repoRoot
        $pause = Set-OmWatchdogPaused -Paused $true -Minutes 60
        $pause.paused | Should -BeTrue
        (Read-OmState).watchdogPaused | Should -BeTrue
        $resume = Set-OmWatchdogPaused -Paused $false
        $resume.paused | Should -BeFalse
        (Read-OmState).watchdogPausedUntil | Should -BeNullOrEmpty
    }
}
