BeforeAll {
    $script:modulePath = Join-Path $PSScriptRoot '..\OpenMargin.Setup.psd1'
    Import-Module $script:modulePath -Force -DisableNameChecking
}

Describe 'Invoke-OmVerify' {
    BeforeAll {
        Mock -ModuleName OpenMargin.Setup Get-OmHttpStatus {
            param($Url, $Headers = @{}, $TimeoutSec = 15, $ProxyMode = 'auto')
            if ($Url -like '*/health') { return [pscustomobject]@{ status = 200; body = '{"ok":true}'; error = $null } }
            if ($Url -like '*/api/v2/sync/changes*') {
                if ($Headers -and $Headers.ContainsKey('X-Device-Token')) {
                    return [pscustomobject]@{ status = 200; body = '{"server_id":"0123456789abcdef","items":[]}'; error = $null }
                }
                return [pscustomobject]@{ status = 401; body = $null; error = 'unauthorized' }
            }
            if ($Url -like '*/wallos/*') { return [pscustomobject]@{ status = 404; body = $null; error = $null } }
            if ($Url -like '*/mcp/*') { return [pscustomobject]@{ status = 404; body = $null; error = $null } }
            return [pscustomobject]@{ status = 0; body = $null; error = 'unexpected url' }
        }
        Mock -ModuleName OpenMargin.Setup Test-OmWallosOwner {
            return [pscustomobject]@{ ok = $true; count = 1; output = $null }
        }
    }

    It 'returns structured checks for a healthy deployment' {
        $result = Invoke-OmVerify -BaseUrl 'https://pc.tailnet.ts.net/finance' -Token 'device-token'
        $result.ok | Should -BeTrue
        @($result.checks).Count | Should -Be 6
        $result.serverId | Should -Be '0123456789abcdef'
        @($result.checks | Where-Object { $_.status -ne 'ok' }).Count | Should -Be 0
        ($result.checks | Where-Object { $_.id -eq 'sync-auth' }).detail | Should -Be 'HTTP 401'
    }

    It 'skips the owner check on request' {
        $result = Invoke-OmVerify -BaseUrl 'https://pc.tailnet.ts.net/finance' -Token 'device-token' -SkipWallosOwner
        @($result.checks).Count | Should -Be 5
    }

    It 'fails when the sync endpoint returns the wrong status' {
        Mock -ModuleName OpenMargin.Setup Get-OmHttpStatus {
            param($Url, $Headers = @{}, $TimeoutSec = 15, $ProxyMode = 'auto')
            if ($Url -like '*/health') { return [pscustomobject]@{ status = 200; body = '{"ok":true}'; error = $null } }
            if ($Url -like '*/api/v2/sync/changes*') {
                if ($Headers -and $Headers.ContainsKey('X-Device-Token')) {
                    return [pscustomobject]@{ status = 500; body = $null; error = 'boom' }
                }
                return [pscustomobject]@{ status = 401; body = $null; error = 'unauthorized' }
            }
            return [pscustomobject]@{ status = 200; body = 'leaked'; error = $null }
        }
        $result = Invoke-OmVerify -BaseUrl 'https://pc.tailnet.ts.net/finance' -Token 'device-token' -SkipWallosOwner
        $result.ok | Should -BeFalse
        @($result.checks | Where-Object { $_.status -eq 'fail' }).Count | Should -BeGreaterThan 0
        ($result.checks | Where-Object { $_.id -eq 'wallos-blocked' }).code | Should -Be 'WALLOS_EXPOSED'
    }

    It 'reports a missing owner' {
        Mock -ModuleName OpenMargin.Setup Test-OmWallosOwner {
            return [pscustomobject]@{ ok = $false; count = 0; output = $null }
        }
        $result = Invoke-OmVerify -BaseUrl 'https://pc.tailnet.ts.net/finance' -Token 'device-token'
        $result.ok | Should -BeFalse
        ($result.checks | Where-Object { $_.id -eq 'owner' }).code | Should -Be 'OWNER_MISSING'
    }

    It 'supports a dry-run without touching the network' {
        $result = Invoke-OmVerify -BaseUrl 'https://example.ts.net/finance' -Token 'x' -DryRun
        $result.ok | Should -BeTrue
        $result.dryRun | Should -BeTrue
        @($result.checks).Count | Should -Be 6
    }
}

Describe 'Get-OmHttpStatus proxy modes' {
    BeforeAll {
        $serverBlock = {
            param($port)
            $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $port)
            $listener.Start()
            try {
                while ($true) {
                    $client = $listener.AcceptTcpClient()
                    try {
                        $stream = $client.GetStream()
                        $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::ASCII)
                        while ($true) {
                            $line = $reader.ReadLine()
                            if ($null -eq $line -or $line -eq '') { break }
                        }
                        $body = '{"ok":true}'
                        $bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
                        $header = "HTTP/1.1 200 OK`r`nContent-Type: application/json`r`nContent-Length: $($bytes.Length)`r`nConnection: close`r`n`r`n"
                        $headerBytes = [System.Text.Encoding]::ASCII.GetBytes($header)
                        $stream.Write($headerBytes, 0, $headerBytes.Length)
                        $stream.Write($bytes, 0, $bytes.Length)
                        $stream.Flush()
                    } catch { }
                    finally { try { $client.Close() } catch { } }
                }
            } finally { $listener.Stop() }
        }

        $probe = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
        $probe.Start()
        $script:probePort = $probe.LocalEndpoint.Port
        $probe.Stop()
        $script:probeUrl = 'http://127.0.0.1:' + $script:probePort + '/health'
        $script:probeServer = Start-Job -ArgumentList $script:probePort -ScriptBlock $serverBlock

        $deadline = (Get-Date).AddSeconds(10)
        do {
            try {
                $client = New-Object System.Net.Sockets.TcpClient
                $client.Connect('127.0.0.1', $script:probePort)
                $client.Close()
                break
            } catch { Start-Sleep -Milliseconds 200 }
        } while ((Get-Date) -lt $deadline)

        $script:savedProxy = [System.Net.WebRequest]::DefaultWebProxy
        [System.Net.WebRequest]::DefaultWebProxy = New-Object System.Net.WebProxy('http://127.0.0.1:1')
    }

    AfterAll {
        [System.Net.WebRequest]::DefaultWebProxy = $script:savedProxy
        Stop-Job $script:probeServer -ErrorAction SilentlyContinue
        Remove-Job $script:probeServer -Force -ErrorAction SilentlyContinue
    }

    It 'direct reaches a loopback target without the proxy' {
        $result = Get-OmHttpStatus -Url $script:probeUrl -TimeoutSec 5 -ProxyMode 'direct'
        $result.status | Should -Be 200
        $result.via | Should -Be 'direct'
    }

    It 'system asks the probe to use the system proxy' {
        Mock -ModuleName OpenMargin.Setup Invoke-OmHttpProbe {
            param($Url, $Headers, $TimeoutSec, [switch]$UseSystemProxy)
            if ($UseSystemProxy) { return [pscustomobject]@{ status = 200; body = '{"ok":true}'; error = $null } }
            return [pscustomobject]@{ status = 0; body = $null; error = 'direct blocked' }
        }
        $result = Get-OmHttpStatus -Url 'https://pc.tailnet.ts.net/finance/health' -ProxyMode 'system'
        $result.status | Should -Be 200
        $result.via | Should -Be 'proxy'
        Should -Invoke Invoke-OmHttpProbe -ModuleName OpenMargin.Setup -Times 1 -ParameterFilter { $UseSystemProxy }
    }

    It 'auto falls back to the system proxy after a direct transport failure' {
        Mock -ModuleName OpenMargin.Setup Invoke-OmHttpProbe {
            param($Url, $Headers, $TimeoutSec, [switch]$UseSystemProxy)
            if ($UseSystemProxy) { return [pscustomobject]@{ status = 200; body = '{"ok":true}'; error = $null } }
            return [pscustomobject]@{ status = 0; body = $null; error = 'direct blocked' }
        }
        $result = Get-OmHttpStatus -Url 'https://pc.tailnet.ts.net/finance/health' -ProxyMode 'auto'
        $result.status | Should -Be 200
        $result.via | Should -Be 'proxy'
        Should -Invoke Invoke-OmHttpProbe -ModuleName OpenMargin.Setup -Times 2
    }
}
