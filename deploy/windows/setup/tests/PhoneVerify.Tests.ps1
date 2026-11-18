BeforeAll {
    $script:modulePath = Join-Path $PSScriptRoot '..\OpenMargin.Setup.psd1'
    Import-Module $script:modulePath -Force -DisableNameChecking
    $script:repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path

    function Start-OmTestHttpServer {
    param(
        [int]$Status = 200,
        [string]$Body = '{}',
        [string]$ExpectToken = ''
    )
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $port = $listener.LocalEndpoint.Port
    $listener.Stop()
    $job = Start-Job -ArgumentList $port, $Status, $Body, $ExpectToken -ScriptBlock {
        param($port, $status, $body, $expectToken)
        $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $port)
        $listener.Start()
        try {
            while ($true) {
                if (-not $listener.Pending()) { Start-Sleep -Milliseconds 50; continue }
                    $client = $listener.AcceptTcpClient()
                try {
                    $stream = $client.GetStream()
                    $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::ASCII)
                    $token = ''
                    while ($true) {
                        $line = $reader.ReadLine()
                        if ($null -eq $line -or $line -eq '') { break }
                        if ($line -match '^(?i)x-admin-token:\s*(.+)$') { $token = $matches[1].Trim() }
                    }
                    $code = $status
                    $responseBody = $body
                    if ($expectToken -and $token -ne $expectToken) { $code = 401; $responseBody = '{"detail":"Unauthorized"}' }
                    $bytes = [System.Text.Encoding]::UTF8.GetBytes($responseBody)
                    $header = "HTTP/1.1 $code OK`r`nContent-Type: application/json`r`nContent-Length: $($bytes.Length)`r`nConnection: close`r`n`r`n"
                    $headerBytes = [System.Text.Encoding]::ASCII.GetBytes($header)
                    $stream.Write($headerBytes, 0, $headerBytes.Length)
                    $stream.Write($bytes, 0, $bytes.Length)
                    $stream.Flush()
                } catch { }
                finally { try { $client.Close() } catch { } }
            }
        } finally { $listener.Stop() }
    }
    $deadline = (Get-Date).AddSeconds(10)
    do {
        try {
            $client = New-Object System.Net.Sockets.TcpClient
            $client.Connect('127.0.0.1', $port)
            $client.Close()
            break
        } catch { Start-Sleep -Milliseconds 200 }
    } while ((Get-Date) -lt $deadline)
    return [pscustomobject]@{ Port = $port; Job = $job }
    }
}

Describe 'Phone verification detection' {
    BeforeEach {
        $script:stateRoot = Join-Path $TestDrive ('phone-' + [guid]::NewGuid().ToString('N'))
        $fixtureRepo = Join-Path $script:stateRoot 'repo'
        $fixtureSecrets = Join-Path $fixtureRepo 'secrets'
        New-Item -ItemType Directory -Force -Path $fixtureSecrets | Out-Null
        Initialize-OmPaths -StateRoot $script:stateRoot -RepoRoot $fixtureRepo
        $state = Read-OmState
        $state.step = 'COMPLETE'
        $state.phoneVerified = $false
        Save-OmState $state
        $fixtureEnv = Join-Path $fixtureSecrets 'backend.env'
        Set-Content -LiteralPath $fixtureEnv -Value 'FINANCE_ADMIN_TOKEN=test-admin-token' -Encoding UTF8
        $script:servers = @()
    }

    AfterEach {
        foreach ($server in $script:servers) {
            Stop-Job $server.Job -ErrorAction SilentlyContinue
            Remove-Job $server.Job -Force -ErrorAction SilentlyContinue
        }
    }

    It 'allows COMPLETE to PHONE_VERIFIED' {
        Test-OmStepTransition -From 'COMPLETE' -To 'PHONE_VERIFIED' | Should -BeTrue
    }

    It 'marks the phone verified and moves COMPLETE to PHONE_VERIFIED' {
        $server = Start-OmTestHttpServer -Status 200 -Body '{"ok":true,"seen":true,"at":"2026-11-18T12:00:00+00:00"}'
        $script:servers += $server
        $result = Update-OmPhoneVerified -BaseUrl ('http://127.0.0.1:' + $server.Port)
        $result.ok | Should -BeTrue
        $result.seen | Should -BeTrue
        (Read-OmState).phoneVerified | Should -BeTrue
        (Read-OmState).step | Should -Be 'PHONE_VERIFIED'
    }

    It 'leaves state untouched when the endpoint is unauthorized' {
        $server = Start-OmTestHttpServer -Status 401 -Body '{"detail":"Unauthorized"}'
        $script:servers += $server
        $result = Update-OmPhoneVerified -BaseUrl ('http://127.0.0.1:' + $server.Port)
        $result.ok | Should -BeFalse
        (Read-OmState).phoneVerified | Should -BeFalse
        (Read-OmState).step | Should -Be 'COMPLETE'
    }

    It 'leaves state untouched when no phone has synced yet' {
        $server = Start-OmTestHttpServer -Status 200 -Body '{"ok":true,"seen":false,"at":null}'
        $script:servers += $server
        $result = Update-OmPhoneVerified -BaseUrl ('http://127.0.0.1:' + $server.Port)
        $result.ok | Should -BeFalse
        (Read-OmState).phoneVerified | Should -BeFalse
        (Read-OmState).step | Should -Be 'COMPLETE'
    }

    It 'does not change a disabled deployment' {
        $state = Read-OmState
        $state.step = 'DISABLED'
        Save-OmState $state
        $server = Start-OmTestHttpServer -Status 200 -Body '{"ok":true,"seen":true,"at":"2026-11-18T12:00:00+00:00"}'
        $script:servers += $server
        $result = Update-OmPhoneVerified -BaseUrl ('http://127.0.0.1:' + $server.Port)
        $result.code | Should -Be 'DISABLED'
        (Read-OmState).step | Should -Be 'DISABLED'
    }

    It 'reads FINANCE_ADMIN_TOKEN from secrets/backend.env' {
        $tempRepo = Join-Path $TestDrive ('repo-' + [guid]::NewGuid().ToString('N'))
        $secrets = Join-Path $tempRepo 'secrets'
        New-Item -ItemType Directory -Force -Path $secrets | Out-Null
        Set-Content -Path (Join-Path $secrets 'backend.env') -Value @(
            'FINANCE_DEVICE_TOKEN=device-token',
            'FINANCE_ADMIN_TOKEN=file-admin-token'
        ) -Encoding UTF8
        $server = Start-OmTestHttpServer -Status 200 -Body '{"ok":true,"seen":true,"at":"2026-11-18T12:00:00+00:00"}' -ExpectToken 'file-admin-token'
        $script:servers += $server
        $originalRepo = (Get-OmRepoRoot)
        try {
            Initialize-OmPaths -StateRoot $script:stateRoot -RepoRoot $tempRepo
            $result = Update-OmPhoneVerified -BaseUrl ('http://127.0.0.1:' + $server.Port)
            $result.ok | Should -BeTrue -Because ('device status returned ' + $result.code)
        } finally {
            Initialize-OmPaths -StateRoot $script:stateRoot -RepoRoot $originalRepo
        }
    }

    It 'returns a dry-run result without touching state' {
        $result = Update-OmPhoneVerified -BaseUrl 'http://127.0.0.1:1' -DryRun
        $result.dryRun | Should -BeTrue
        (Read-OmState).phoneVerified | Should -BeFalse
    }
}

Describe 'Phone verification in web actions and UI' {
    BeforeAll {
        $script:stateRoot = Join-Path $TestDrive 'phone-web'
        Initialize-OmPaths -StateRoot $script:stateRoot -RepoRoot $script:repoRoot
        $script:ports = [pscustomobject]@{ api = 8767; wallos = 18082; gateway = 18768; setup = 18770 }
    }

    It 'exposes a dry-run phone-status action' {
        $result = Invoke-OmWebAction -Action 'phone-status' -Params ([pscustomobject]@{ dryRun = $true }) -Ports $script:ports -StateRoot $script:stateRoot -RepoRoot $script:repoRoot
        $result.ok | Should -BeTrue
        $result.code | Should -Be 'DRY_RUN'
    }

    It 'shows the phone state on the status screen and the verified badge' {
        $js = Get-Content -Path (Join-Path $PSScriptRoot '..\web\app.js') -Raw -Encoding UTF8
        $js | Should -Match "手机已验证"
        $js | Should -Match "postAction\('phone-status'\)"
        $js | Should -Match "phoneVerified \? '已验证' : '未验证'"
        $html = Get-Content -Path (Join-Path $PSScriptRoot '..\web\index.html') -Raw -Encoding UTF8
        $html | Should -Match 'status-qr-card'
        $html | Should -Match '重新显示二维码'
    }
}
