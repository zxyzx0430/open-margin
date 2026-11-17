BeforeAll {
    $script:modulePath = Join-Path $PSScriptRoot '..\OpenMargin.Setup.psd1'
    Import-Module $script:modulePath -Force -DisableNameChecking
    $script:stateRoot = Join-Path $TestDrive 'integration-state'
    Initialize-OmPaths -StateRoot $script:stateRoot -RepoRoot (Join-Path $TestDrive 'integration-app')
    $script:ports = [pscustomobject]@{ api = 8767; wallos = 18082; gateway = 18768; setup = 18770 }
}

Describe 'Web check ignores the wizard setup ports' {
    It 'passes the configured and live setup ports to the port probe' {
        Mock -ModuleName OpenMargin.Setup Get-OmEnvironmentChecks {
            param($SkipNetwork, $SkipPorts, $Ports, $RepoRoot, $IgnorePorts = @())
            return @([pscustomobject]@{ id = 'ports'; title = '端口'; status = 'ok'; detail = ('ignored: ' + (@($IgnorePorts) -join ',')); remediation = ''; code = '' })
        }
        $result = Invoke-OmWebAction -Action 'check' -Params ([pscustomobject]@{ setupPort = 18791 }) -Ports $script:ports -StateRoot $script:stateRoot -RepoRoot (Join-Path $TestDrive 'integration-app')
        $result.checks[0].detail | Should -Match '18770'
        $result.checks[0].detail | Should -Match '18791'
    }
}

Describe 'CLI check ignores the wizard setup port' {
    BeforeAll {
        $script:cliPath = Join-Path $PSScriptRoot '..\open-margin-setup.ps1'
        $script:listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, 0)
        $script:listener.Start()
        $script:wizardPort = ([System.Net.IPEndPoint]$script:listener.LocalEndpoint).Port
    }
    AfterAll {
        try { $script:listener.Stop() } catch { }
    }

    It 'does not report the listening wizard port as PORT_CONFLICT' {
        $cliState = Join-Path $TestDrive ('cli-state-' + [guid]::NewGuid().ToString('N'))
        $repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
        $output = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $script:cliPath check -Json -StateRoot $cliState -RepoRoot $repoRoot -SetupPort $script:wizardPort 2>$null | Out-String
        $json = $output | ConvertFrom-Json
        $portsCheck = @($json.checks) | Where-Object { $_.id -eq 'ports' }
        $portsCheck | Should -Not -BeNullOrEmpty
        $portsCheck.code | Should -Not -Be 'PORT_CONFLICT'
        $portsCheck.detail | Should -Not -Match (':' + $script:wizardPort)
    }
}
