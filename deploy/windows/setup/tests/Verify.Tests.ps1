BeforeAll {
    $script:modulePath = Join-Path $PSScriptRoot '..\OpenMargin.Setup.psd1'
    Import-Module $script:modulePath -Force -DisableNameChecking
}

Describe 'Invoke-OmVerify' {
    BeforeAll {
        Mock -ModuleName OpenMargin.Setup Get-OmHttpStatus {
            param($Url, $Headers = @{}, $TimeoutSec = 15)
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
            param($Url, $Headers = @{}, $TimeoutSec = 15)
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
