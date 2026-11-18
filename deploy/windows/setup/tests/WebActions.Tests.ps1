BeforeAll {
    $script:modulePath = Join-Path $PSScriptRoot '..\OpenMargin.Setup.psd1'
    Import-Module $script:modulePath -Force -DisableNameChecking
    $script:repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
    $script:stateRoot = Join-Path $TestDrive 'web-actions'
    Initialize-OmPaths -StateRoot $script:stateRoot -RepoRoot $script:repoRoot
    $script:ports = [pscustomobject]@{ api = 8767; wallos = 18082; gateway = 18768; setup = 18770 }
}

Describe 'Web action contract' {
    BeforeAll {
        Mock -ModuleName OpenMargin.Setup Get-OmEnvironmentChecks {
            param($SkipNetwork, $SkipPorts, $Ports, $RepoRoot, $IgnorePorts = @())
            return @(
                [pscustomobject]@{ id = 'docker'; title = 'Docker'; status = 'ok'; detail = 'ok'; remediation = ''; code = '' },
                [pscustomobject]@{ id = 'tailscale'; title = 'Tailscale'; status = 'fail'; detail = 'missing'; remediation = 'install'; code = 'TAILSCALE_MISSING' }
            )
        }
    }

    It 'returns structured checks for check' {
        $result = Invoke-OmWebAction -Action 'check' -Params ([pscustomobject]@{ setupPort = 18770 }) -Ports $script:ports -StateRoot $script:stateRoot -RepoRoot $script:repoRoot
        $result.ok | Should -BeFalse
        @($result.checks).Count | Should -Be 2
        $result.summary.total | Should -Be 2
        (Read-OmState).lastCheck | Should -Not -BeNullOrEmpty
    }

    It 'rejects deploy without an administrator or public acknowledgement' {
        $missing = Invoke-OmWebAction -Action 'deploy' -Params ([pscustomobject]@{}) -Ports $script:ports -StateRoot $script:stateRoot -RepoRoot $script:repoRoot
        $missing.code | Should -Be 'MISSING_ADMIN'

        $noAck = Invoke-OmWebAction -Action 'deploy' -Params ([pscustomobject]@{ adminUser = 'alex'; adminPassword = 'password123' }) -Ports $script:ports -StateRoot $script:stateRoot -RepoRoot $script:repoRoot
        $noAck.code | Should -Be 'ACK_REQUIRED'
    }

    It 'runs a complete dry-run deploy and returns verify checks' {
        $result = Invoke-OmWebAction -Action 'deploy' -Params ([pscustomobject]@{
            adminUser = 'alex'; adminPassword = 'password123'; ackPublic = $true; dryRun = $true
        }) -Ports $script:ports -StateRoot $script:stateRoot -RepoRoot $script:repoRoot
        $result.ok | Should -BeTrue
        $result.dryRun | Should -BeTrue
        @($result.checks).Count | Should -Be 6
        $result.pairingPayload | Should -BeNullOrEmpty
    }

    It 'returns NOT_FOUND style codes for unknown actions' {
        $result = Invoke-OmWebAction -Action 'run-anything' -Params ([pscustomobject]@{}) -Ports $script:ports -StateRoot $script:stateRoot -RepoRoot $script:repoRoot
        $result.ok | Should -BeFalse
        $result.code | Should -Be 'UNKNOWN_ACTION'
    }

    It 'refuses to generate a pairing QR without a public URL' {
        $result = Invoke-OmWebAction -Action 'pair-qr' -Params ([pscustomobject]@{}) -Ports $script:ports -StateRoot $script:stateRoot -RepoRoot $script:repoRoot
        $result.ok | Should -BeFalse
        $result.code | Should -Be 'NO_PUBLIC_URL'
    }

    It 'pauses and resumes the watchdog through state' {
        $pause = Invoke-OmWebAction -Action 'pause-watchdog' -Params ([pscustomobject]@{ minutes = 15 }) -Ports $script:ports -StateRoot $script:stateRoot -RepoRoot $script:repoRoot
        $pause.ok | Should -BeTrue
        (Read-OmState).watchdogPaused | Should -BeTrue

        $resume = Invoke-OmWebAction -Action 'resume-watchdog' -Params ([pscustomobject]@{}) -Ports $script:ports -StateRoot $script:stateRoot -RepoRoot $script:repoRoot
        $resume.ok | Should -BeTrue
        (Read-OmState).watchdogPaused | Should -BeFalse
    }

    It 'reports watchdog status without touching the scheduler' {
        $result = Invoke-OmWebAction -Action 'watchdog-status' -Params ([pscustomobject]@{}) -Ports $script:ports -StateRoot $script:stateRoot -RepoRoot $script:repoRoot
        $result.ok | Should -BeTrue
        $result.state | Should -BeIn @('running', 'paused', 'not_installed')
    }
}

Describe 'Public probe mode action and UI' {
    It 'persists every valid mode through the fixed action' {
        foreach ($mode in @('direct', 'system', 'auto')) {
            $result = Invoke-OmWebAction -Action 'set-public-probe-mode' -Params ([pscustomobject]@{ mode = $mode }) -Ports $script:ports -StateRoot $script:stateRoot -RepoRoot $script:repoRoot
            $result.ok | Should -BeTrue
            $result.mode | Should -Be $mode
            (Read-OmState).publicProbeMode | Should -Be $mode
        }
    }

    It 'rejects an unknown mode without changing state' {
        $null = Set-OmPublicProbeMode -Mode 'direct'
        $result = Invoke-OmWebAction -Action 'set-public-probe-mode' -Params ([pscustomobject]@{ mode = 'vpn' }) -Ports $script:ports -StateRoot $script:stateRoot -RepoRoot $script:repoRoot
        $result.ok | Should -BeFalse
        $result.code | Should -Be 'INVALID_PROBE_MODE'
        (Read-OmState).publicProbeMode | Should -Be 'direct'
        $null = Set-OmPublicProbeMode -Mode 'auto'
    }

    It 'does not persist during a dry run' {
        $null = Set-OmPublicProbeMode -Mode 'auto'
        $result = Invoke-OmWebAction -Action 'set-public-probe-mode' -Params ([pscustomobject]@{ mode = 'system'; dryRun = $true }) -Ports $script:ports -StateRoot $script:stateRoot -RepoRoot $script:repoRoot
        $result.ok | Should -BeTrue
        $result.code | Should -Be 'DRY_RUN'
        (Read-OmState).publicProbeMode | Should -Be 'auto'
    }

    It 'offers the three choices on the deploy and status screens' {
        $html = Get-Content -Path (Join-Path $PSScriptRoot '..\web\index.html') -Raw -Encoding UTF8
        foreach ($mode in @('auto', 'direct', 'system')) {
            ([regex]::Matches($html, 'data-probe-mode="' + $mode + '"')).Count | Should -BeGreaterOrEqual 2
        }
        $html | Should -Match '公网自测网络'
        $js = Get-Content -Path (Join-Path $PSScriptRoot '..\web\app.js') -Raw -Encoding UTF8
        $js | Should -Match 'set-public-probe-mode'
        $js | Should -Match '已切换，下次自测生效'
    }
}
