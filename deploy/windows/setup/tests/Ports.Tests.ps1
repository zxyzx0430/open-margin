BeforeAll {
    Import-Module (Join-Path $PSScriptRoot '..\OpenMargin.Setup.psd1') -Force -DisableNameChecking
}

Describe 'Port helpers' {
    It 'detects an occupied loopback port' {
        $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
        $listener.Start()
        try {
            $port = $listener.LocalEndpoint.Port
            Test-OmPortAvailable -Port $port | Should -BeFalse
        } finally {
            $listener.Stop()
        }
    }

    It 'treats a released port as free' {
        $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
        $listener.Start()
        $port = $listener.LocalEndpoint.Port
        $listener.Stop()
        Start-Sleep -Milliseconds 100
        Test-OmPortAvailable -Port $port | Should -BeTrue
    }

    It 'excludes the wizard own setup port from conflicts' {
        $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
        $listener.Start()
        try {
            $port = $listener.LocalEndpoint.Port
            $ports = [pscustomobject]@{ api = $port; wallos = $port; gateway = $port; setup = $port }
            $resolved = Resolve-OmPorts -Ports $ports -IgnorePorts @($port)
            $resolved.conflicts.Count | Should -Be 0
        } finally {
            $listener.Stop()
        }
    }

    It 'reports a conflict when the occupied port is not ignored' {
        $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
        $listener.Start()
        try {
            $port = $listener.LocalEndpoint.Port
            $ports = [pscustomobject]@{ api = $port; wallos = 18082; gateway = 18768; setup = 18770 }
            $resolved = Resolve-OmPorts -Ports $ports
            @($resolved.conflicts | Where-Object { $_.name -eq 'api' -and $_.port -eq $port }).Count | Should -Be 1
        } finally {
            $listener.Stop()
        }
    }

    It 'auto-advances to the next free port' {
        $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
        $listener.Start()
        try {
            $port = $listener.LocalEndpoint.Port
            if ($port -gt 65500) { Set-ItResult -Skipped -Because 'ephemeral port too high for range test' }
            $ports = [pscustomobject]@{ api = $port; wallos = 18082; gateway = 18768; setup = 18770 }
            $resolved = Resolve-OmPorts -Ports $ports -AutoAdvance -SearchSpan 50
            $resolved.ports.api | Should -BeGreaterThan $port
            @($resolved.changes | Where-Object { $_.name -eq 'api' }).Count | Should -Be 1
        } finally {
            $listener.Stop()
        }
    }

    It 'returns documented defaults' {
        $defaults = Get-OmDefaultPorts
        $defaults.api | Should -Be 8767
        $defaults.wallos | Should -Be 18082
        $defaults.gateway | Should -Be 18768
        $defaults.setup | Should -Be 18770
    }
}
