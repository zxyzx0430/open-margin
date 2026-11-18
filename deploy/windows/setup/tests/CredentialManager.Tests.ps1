BeforeAll {
    $script:modulePath = Join-Path $PSScriptRoot '..\OpenMargin.Setup.psd1'
    Import-Module $script:modulePath -Force -DisableNameChecking
    $script:bridgePath = Join-Path $PSScriptRoot '..\lib\CredentialVaultBridge.ps1'
    $script:windowsPowerShell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
}

Describe 'Wallos credential storage' {
    It 'roundtrips credentials through the current Windows Credential Manager entry' {
        $target = 'OpenMargin.Test.' + [guid]::NewGuid().ToString('N')
        InModuleScope OpenMargin.Setup -Parameters @{ credentialTarget = $target } {
            param($credentialTarget)
            $previousTarget = $script:OmWallosCredentialTarget
            $script:OmWallosCredentialTarget = $credentialTarget
            $password = 'Test-' + [guid]::NewGuid().ToString('N')
            try {
                $saved = Save-OmCredentialDirect -Username '清衡 user' -Password $password
                $loaded = Get-OmCredentialDirect
                $saved.ok | Should -BeTrue
                $loaded.ok | Should -BeTrue
                $loaded.username | Should -Be '清衡 user'
                $loaded.password | Should -BeExactly $password
            } finally {
                $null = Remove-OmCredentialDirect
                $script:OmWallosCredentialTarget = $previousTarget
            }
        }
    }

    It 'refuses to use a different administrator account credential store without the original-user bridge' {
        InModuleScope OpenMargin.Setup {
            $currentSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
            $foreignSid = if ($currentSid -eq 'S-1-5-18') { 'S-1-5-32-544' } else { 'S-1-5-18' }
            Mock Save-OmCredentialDirect { throw 'native credential store must not be called' }
            $result = Invoke-OmCredentialManager -Command 'set' -Username 'owner' -Password 'test-password' -OriginalUserSid $foreignSid
            $result.code | Should -Be 'ORIGINAL_USER_CREDENTIAL_CONTEXT_REQUIRED'
            Should -Invoke Save-OmCredentialDirect -Times 0 -Exactly
        }
    }

    It 'rejects invalid pipe names before opening a local connection' {
        $result = Invoke-OmCredentialManager -Command 'get' -PipeName 'invalid pipe name'
        $result.code | Should -Be 'CREDENTIAL_PIPE_INVALID'
    }

    It 'builds a no-store friendly POST form with escaped fields and no credential in the URL' {
        $username = 'owner"<&'
        $password = 'pass"<&'
        $page = New-OmLedgerLoginPagePayload -Username $username -Password $password -WallosPort 18123 -Nonce 'nonce-for-test'
        $page.html | Should -Match 'method="post" action="http://127\.0\.0\.1:18123/login\.php"'
        $page.html | Should -Match 'name="remember" value="on"'
        $page.html | Should -Match 'value="owner&quot;&lt;&amp;"'
        $page.html | Should -Match 'value="pass&quot;&lt;&amp;"'
        $page.html | Should -Not -Match ([regex]::Escape($password))
        $page.html | Should -Not -Match 'pass.*login\.php'
        $page.contentSecurityPolicy | Should -Match "script-src 'nonce-nonce-for-test'"
        $page.contentSecurityPolicy | Should -Match 'form-action http://127\.0\.0\.1:18123'
    }

    It 'provides a one-time local credential capture page when an older install has no saved entry' {
        $page = New-OmCredentialBootstrapPagePayload -Nonce 'bootstrap-nonce'
        $page.html | Should -Match 'autocomplete="username"'
        $page.html | Should -Match 'autocomplete="current-password"'
        $page.html | Should -Match "fetch\('/api/credentials'"
        $page.html | Should -Match "window\.location\.replace\('/api/ledger-login'\)"
        $page.html | Should -Match '保存到当前 Windows 用户的凭据管理器'
        $page.contentSecurityPolicy | Should -Match "script-src 'nonce-bootstrap-nonce'"
        $page.contentSecurityPolicy | Should -Match "connect-src 'self'"
        $page.contentSecurityPolicy | Should -Match "form-action 'self'"
    }

    It 'uses a random, ACL-protected bridge under Windows PowerShell 5.1 for the original user' {
        $pipe = [guid]::NewGuid().ToString('N')
        $target = 'OpenMargin.Test.' + [guid]::NewGuid().ToString('N')
        $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
        $arguments = '-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "' + $script:bridgePath + '" -PipeName ' + $pipe + ' -OriginalUserSid ' + $sid + ' -CredentialTarget ' + $target
        $errorPath = Join-Path $TestDrive ('credential-bridge-' + $pipe + '.err')
        $worker = Start-Process -FilePath $script:windowsPowerShell -ArgumentList $arguments -PassThru -WindowStyle Hidden -RedirectStandardError $errorPath
        $stored = $false
        try {
            $attached = $null
            for ($attempt = 0; $attempt -lt 20; $attempt++) {
                $attached = Invoke-OmCredentialManager -Command 'attach' -ServerPid $PID -OriginalUserSid $sid -PipeName $pipe -TimeoutMilliseconds 250
                if ($attached.ok) { break }
                Start-Sleep -Milliseconds 100
            }
            $attached.ok | Should -BeTrue
            $password = 'Bridge-' + [guid]::NewGuid().ToString('N')
            $saved = Invoke-OmCredentialManager -Command 'set' -Username '域 account' -Password $password -OriginalUserSid $sid -PipeName $pipe
            $loaded = Invoke-OmCredentialManager -Command 'get' -OriginalUserSid $sid -PipeName $pipe
            $saved.ok | Should -BeTrue
            $loaded.ok | Should -BeTrue
            $loaded.username | Should -Be '域 account'
            $loaded.password | Should -BeExactly $password
            $stored = $true
            $removed = Invoke-OmCredentialManager -Command 'delete' -OriginalUserSid $sid -PipeName $pipe
            $removed.ok | Should -BeTrue
        } finally {
            if ($stored) { $null = Invoke-OmCredentialManager -Command 'delete' -OriginalUserSid $sid -PipeName $pipe }
            $null = Invoke-OmCredentialManager -Command 'stop' -OriginalUserSid $sid -PipeName $pipe -TimeoutMilliseconds 500
            if (-not $worker.HasExited) {
                $worker.WaitForExit(3000) | Out-Null
                if (-not $worker.HasExited) { Stop-Process -Id $worker.Id -Force }
            }
        }
        $bridge = Get-Content -Path $script:bridgePath -Raw -Encoding UTF8
        $bridge | Should -Match 'BuiltinAdministratorsSid'
        $bridge | Should -Match 'SetAccessRuleProtection\(\$true, \$false\)'
    }
}

Describe 'Automatic ledger login handoff' {
    It 'keeps the credential bridge and browser login route behind the setup token' {
        $server = Get-Content -Path (Join-Path $PSScriptRoot '..\Start-OpenMarginSetupWeb.ps1') -Raw -Encoding UTF8
        $server | Should -Match "Invoke-OmCredentialManager -Command 'get'"
        $server | Should -Match "Invoke-OmCredentialManager -Command 'set'"
        $server | Should -Match "\^/api/ledger-login\$"
        $server | Should -Match "\^/api/credentials\$"
        $server | Should -Match 'Cache-Control'
        $server | Should -Match 'Content-Security-Policy'
        $server | Should -Match 'credential\.password = \$null'
        $server | Should -Match 'New-OmCredentialBootstrapPagePayload'
        $server | Should -Match "'Cache-Control' = 'no-store, no-cache, must-revalidate'"
        $launch = Get-Content -Path (Join-Path $PSScriptRoot '..\installer\Launch-Setup.ps1') -Raw -Encoding UTF8
        $launch | Should -Match 'CredentialVaultBridge\.ps1'
        $launch | Should -Match 'OriginalUserSid'
        $launch | Should -Match 'CredentialPipeName'
    }

    It 'opens the ledger after deploy verification and both user-triggered repair paths' {
        $js = Get-Content -Path (Join-Path $PSScriptRoot '..\web\app.js') -Raw -Encoding UTF8
        $js | Should -Match 'completeDeploymentLedgerHandoff'
        $js | Should -Match 'completeRepairLedgerHandoff'
        $js | Should -Match "window\.open\('about:blank', '_blank'\)"
        $js | Should -Match "'/api/ledger-login'"
        $js | Should -Match 'autoLedgerError'
        $js | Should -Match 'closeLedgerHandoff\(\)'
    }

    It 'gates post-upgrade ledger handoff on a same-path prior install' {
        $installer = Get-Content -Path (Join-Path $PSScriptRoot '..\installer\OpenMargin.iss') -Raw -Encoding UTF8
        $installer | Should -Match 'WasInPlaceUpgrade'
        $installer | Should -Match 'GetUpgradeLedgerArgument'
        $installer | Should -Match '\-AutoOpenLedgerAfterInstall'
        $launch = Get-Content -Path (Join-Path $PSScriptRoot '..\installer\Launch-Setup.ps1') -Raw -Encoding UTF8
        $launch | Should -Match 'Wait-UpgradeServices'
        $launch | Should -Match 'Start-ScheduledTask -TaskName ''OpenMarginWatchdog'''
        $launch | Should -Match 'Add-AutoLedgerFlag'
    }
}
