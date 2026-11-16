BeforeAll {
    $script:modulePath = Join-Path $PSScriptRoot '..\OpenMargin.Setup.psd1'
    Import-Module $script:modulePath -Force -DisableNameChecking
    $script:repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
    $script:stateRoot = Join-Path $TestDrive 'state-machine'
    Initialize-OmPaths -StateRoot $script:stateRoot -RepoRoot $script:repoRoot
}

Describe 'State machine transitions' {
    It 'allows DISABLED -> READY (re-enable)' {
        Test-OmStepTransition -From 'DISABLED' -To 'READY' | Should -BeTrue
    }

    It 'allows SWITCHING -> READY' {
        Test-OmStepTransition -From 'SWITCHING' -To 'READY' | Should -BeTrue
    }

    It 'allows FAILED -> any recovery step' {
        Test-OmStepTransition -From 'FAILED' -To 'CHECKING' | Should -BeTrue
        Test-OmStepTransition -From 'FAILED' -To 'READY' | Should -BeTrue
        Test-OmStepTransition -From 'FAILED' -To 'DEPLOYING' | Should -BeTrue
    }

    It 'rejects READY -> COMPLETE and COMPLETE -> DISABLED' {
        Test-OmStepTransition -From 'READY' -To 'COMPLETE' | Should -BeFalse
        Test-OmStepTransition -From 'COMPLETE' -To 'DISABLED' | Should -BeFalse
    }

    It 'allows idempotent same-state writes' {
        Test-OmStepTransition -From 'COMPLETE' -To 'COMPLETE' | Should -BeTrue
    }

    It 'walks the full deploy -> disable -> re-enable path' {
        $null = Set-OmStep 'READY' -Force
        $null = Set-OmStep 'DEPLOYING'
        $null = Set-OmStep 'VERIFYING'
        $null = Set-OmStep 'COMPLETE'
        $null = Set-OmStep 'DISABLING'
        $null = Set-OmStep 'DISABLED'
        $null = Set-OmStep 'SWITCHING'
        $null = Set-OmStep 'READY'
        (Read-OmState).step | Should -Be 'READY'
    }

    It 'throws on an invalid transition unless -Force is used' {
        $null = Set-OmStep 'READY' -Force
        { Set-OmStep 'COMPLETE' } | Should -Throw '*invalid state transition*'
        { Set-OmStep 'COMPLETE' -Force } | Should -Not -Throw
        (Read-OmState).step | Should -Be 'COMPLETE'
    }
}

Describe 'State file' {
    It 'writes atomically without a leftover temp file' {
        $state = Read-OmState
        $state.publicSyncUrl = 'https://pc.tailnet.ts.net/finance'
        Save-OmState $state
        Test-Path (Get-OmStatePath) | Should -BeTrue
        Test-Path ((Get-OmStatePath) + '.tmp') | Should -BeFalse
    }

    It 'recovers from a corrupt state file and keeps a backup' {
        Set-Content -Path (Get-OmStatePath) -Value 'this is not json' -Encoding UTF8
        $state = Read-OmState
        $state.step | Should -Be 'UNINITIALIZED'
        @(Get-ChildItem -Path $script:stateRoot -Filter 'setup-state.json.corrupt-*' -File).Count | Should -BeGreaterThan 0
    }

    It 'stores progress fields' {
        $progress = Set-OmProgress -Stage 'compose' -Percent 20 -Message 'starting'
        $progress.stage | Should -Be 'compose'
        $progress.percent | Should -Be 20
        (Read-OmState).progress.stage | Should -Be 'compose'
    }

    It 'never adds token or password fields to the state file' {
        $raw = Get-Content -Path (Get-OmStatePath) -Raw -Encoding UTF8
        $raw | Should -Not -Match 'FINANCE_DEVICE_TOKEN'
        $raw | Should -Not -Match 'FINANCE_ADMIN_TOKEN'
        $raw | Should -Not -Match 'password'
    }
}
