BeforeAll {
    $script:modulePath = Join-Path $PSScriptRoot '..\OpenMargin.Setup.psd1'
    Import-Module $script:modulePath -Force -DisableNameChecking
    $script:repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
    $script:ports = [pscustomobject]@{ api = 8767; wallos = 18082; gateway = 18768; setup = 18770 }
}

Describe 'Invoke-OmApproveFunnel watchdog install' {
    BeforeEach {
        $script:stateRoot = Join-Path $TestDrive ('approve-' + [guid]::NewGuid().ToString('N'))
        Initialize-OmPaths -StateRoot $script:stateRoot -RepoRoot $script:repoRoot
        $state = Read-OmState
        $state.step = 'WAITING_USER'
        $state.publicSyncUrl = 'https://pc.tailnet.ts.net/finance'
        Save-OmState $state

        Mock -ModuleName OpenMargin.Setup Wait-OmFunnelApproval {
            param($GatewayPort, $TimeoutSec, [switch]$DryRun)
            return [pscustomobject]@{ ok = $true; url = 'https://pc.tailnet.ts.net/finance' }
        }
        Mock -ModuleName OpenMargin.Setup Set-OmEnvValueInFile { }
        Mock -ModuleName OpenMargin.Setup Wait-OmPublicDns { return [pscustomobject]@{ ok = $true } }
        Mock -ModuleName OpenMargin.Setup Get-OmDeviceTokenFromFile { return 'device-token' }
        Mock -ModuleName OpenMargin.Setup Invoke-OmVerify {
            param($BaseUrl, $Token, $ProjectName, $RepoRoot, [switch]$SkipWallosOwner, [switch]$DryRun)
            return [pscustomobject]@{ ok = $true; checks = @(); serverId = '0123456789abcdef' }
        }
    }

    It 'installs the watchdog and records it before marking COMPLETE' {
        Mock -ModuleName OpenMargin.Setup Install-OmWatchdog {
            param($StateRoot, $RepoRoot, $Ports, $ProjectName)
            return [pscustomobject]@{ ok = $true; code = 'WATCHDOG_INSTALLED' }
        }
        $result = Invoke-OmApproveFunnel -Ports $script:ports -ProjectName 'test' -RepoRoot $script:repoRoot
        $result.ok | Should -BeTrue
        (Read-OmState).watchdogInstalled | Should -BeTrue
        (Read-OmState).step | Should -Be 'COMPLETE'
        Should -Invoke Install-OmWatchdog -ModuleName OpenMargin.Setup -Times 1
    }

    It 'still completes when the watchdog install fails' {
        Mock -ModuleName OpenMargin.Setup Install-OmWatchdog {
            param($StateRoot, $RepoRoot, $Ports, $ProjectName)
            return [pscustomobject]@{ ok = $false; code = 'WATCHDOG_INSTALL_FAILED' }
        }
        $result = Invoke-OmApproveFunnel -Ports $script:ports -ProjectName 'test' -RepoRoot $script:repoRoot
        $result.ok | Should -BeTrue
        (Read-OmState).watchdogInstalled | Should -BeFalse
        (Read-OmState).step | Should -Be 'COMPLETE'
    }

    It 'skips the watchdog during a dry run' {
        Mock -ModuleName OpenMargin.Setup Install-OmWatchdog {
            param($StateRoot, $RepoRoot, $Ports, $ProjectName)
            return [pscustomobject]@{ ok = $true; code = 'WATCHDOG_INSTALLED' }
        }
        $result = Invoke-OmApproveFunnel -Ports $script:ports -ProjectName 'test' -RepoRoot $script:repoRoot -DryRun
        $result.ok | Should -BeTrue
        Should -Invoke Install-OmWatchdog -ModuleName OpenMargin.Setup -Times 0
    }
}
