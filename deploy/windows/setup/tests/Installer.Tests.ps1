BeforeAll {
    $script:setupRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
    $script:issPath = Join-Path $script:setupRoot 'installer\OpenMargin.iss'
    $script:iss = Get-Content -Path $script:issPath -Raw -Encoding UTF8
}

Describe 'Installer custom path and autostart' {
    It 'shows the directory page and defaults to D:, E:, then the system drive' {
        $script:iss | Should -Match '(?m)^DisableDirPage=no\s*$'
        $script:iss | Should -Match '(?m)^DefaultDirName=\{code:GetDefaultInstallDir\}\s*$'
        $script:iss | Should -Match "D:\\OpenMargin\\app"
        $script:iss | Should -Match "E:\\OpenMargin\\app"
        $script:iss | Should -Match "\{sd\}\\OpenMargin\\app"
        $script:iss | Should -Not -Match '\{commonappdata\}\\OpenMargin\\app'
        $script:iss | Should -Not -Match '(?m)^UsePreviousAppDir=no\s*$'
    }

    It 'uses {app} for files, icons, run entries and uninstall helpers' {
        $script:iss | Should -Not -Match 'C:\\ProgramData'
        foreach ($line in ($script:iss -split "`r?`n")) {
            if ($line -match '^\s*(Source|DestDir|Name|Filename|Parameters):') {
                if ($line -match '\.iss' -or $line -match '^\s*Source:') { continue }
                $line | Should -Not -Match 'C:\\'
            }
        }
        $script:iss | Should -Match '\{app\}\\deploy\\windows\\setup\\installer\\Launch-Setup\.ps1'
        $script:iss | Should -Match '\{app\}\\deploy\\windows\\setup\\installer\\Launch-Ledger\.ps1'
        $script:iss | Should -Match '\{app\}\\deploy\\windows\\setup\\installer\\Set-AppAcl\.ps1'
        $script:iss | Should -Match '\{app\}\\deploy\\windows\\setup\\installer\\Install-UrlAcl\.ps1'
    }

    It 'starts the wizard after a normal or silent install as the original user' {
        $runLines = @(($script:iss -split "`r?`n") | Where-Object {
            $_ -match 'postinstall' -and $_ -match 'Launch-Setup\.ps1'
        })
        $runLines.Count | Should -Be 2
        $elevated = $runLines | Where-Object { $_ -match 'ShouldRunElevatedWizard' } | Select-Object -First 1
        $unelevated = $runLines | Where-Object { $_ -match 'ShouldRunUnelevatedWizard' } | Select-Object -First 1
        $elevated | Should -Not -BeNullOrEmpty
        $unelevated | Should -Not -BeNullOrEmpty
        foreach ($line in $runLines) {
            $line | Should -Not -Match 'skipifsilent'
            $line | Should -Match 'postinstall'
            $line | Should -Match 'runasoriginaluser'
            $line | Should -Match 'nowait'
        }
        $elevated | Should -Match 'Check:\s*ShouldRunElevatedWizard'
        $elevated | Should -Match 'ProvisionOriginalUser'
        $unelevated | Should -Match 'Check:\s*ShouldRunUnelevatedWizard'
        $unelevated | Should -Match '\-NoElevate'
        $script:iss | Should -Match "HasCmdLineParam\('/NOAUTOSTART'\)"
        $script:iss | Should -Match "HasCmdLineParam\('/NOELEVATE'\)"
    }

    It 'hands the token URL to an unelevated browser' {
        $launch = Get-Content -Path (Join-Path $script:setupRoot 'installer\Launch-Setup.ps1') -Raw -Encoding UTF8
        $launch | Should -Match 'setup-web-url\.txt'
        $launch | Should -Match 'explorer\.exe'
        $launch | Should -Match 'IsInRole'
        $launch | Should -Match 'NoElevate'
        $server = Get-Content -Path (Join-Path $script:setupRoot 'Start-OpenMarginSetupWeb.ps1') -Raw -Encoding UTF8
        $server | Should -Match 'setup-web-url\.txt'
        $server | Should -Match 'icacls'
        $server | Should -Match "currentUser \+ ':M'"
        $script:iss | Should -Not -Match ' -Dev\b'
    }

    It 'does not hardcode a C: path in installer helper scripts' {
        foreach ($name in @('Launch-Setup.ps1', 'Launch-Ledger.ps1', 'Set-AppAcl.ps1', 'Install-UrlAcl.ps1')) {
            $text = Get-Content -Path (Join-Path $script:setupRoot ('installer\' + $name)) -Raw -Encoding UTF8
            $text | Should -Not -Match 'C:\\ProgramData'
            $text | Should -Not -Match '%ProgramData%'
        }
    }
}

Describe 'Installer compliance and lifecycle hooks' {
    It 'requires Windows 10 21H2 and ships the Chinese language first' {
        $script:iss | Should -Match '(?m)^MinVersion=10\.0\.19044\s*$'
        $script:iss | Should -Match '(?m)^Name:\s*"chinesesimplified";\s*MessagesFile:.*ChineseSimplified\.isl'
        $chinese = $script:iss.IndexOf('Name: "chinesesimplified"')
        $english = $script:iss.IndexOf('Name: "english"')
        $chinese | Should -BeGreaterThan 0
        $english | Should -BeGreaterThan $chinese
        $script:iss | Should -Match 'InfoBeforeFile=.*REQUIREMENTS\.txt'
    }

    It 'shows the Docker license consent page and writes the ack marker only after consent' {
        $script:iss | Should -Match 'DockerNoticePage'
        $script:iss | Should -Match 'DockerNoticeCheck\.Checked'
        $script:iss | Should -Match 'docker-license-ack\.txt'
        $script:iss | Should -Match 'DockerDesktopNotice\.txt'
        $script:iss | Should -Match 'DockerNoticeAccepted'
    }

    It 'wires the standalone uninstaller with waituntilterminated and RunOnceId' {
        $uninstall = Get-Content -Path (Join-Path $script:setupRoot 'installer\Uninstall-OpenMargin.ps1') -Raw -Encoding UTF8
        $uninstall | Should -Not -BeNullOrEmpty
        $script:iss | Should -Match '(?m)^\[UninstallRun\]'
        $script:iss | Should -Match 'Uninstall-OpenMargin\.ps1'
        $script:iss | Should -Match 'waituntilterminated'
        $script:iss | Should -Match 'RunOnceId:\s*"OpenMarginLifecycleUninstall"'
        $script:iss | Should -Match 'CloseApplications=yes'
        $script:iss | Should -Match 'RestartApplications=no'
    }

    It 'extracts the stop helper before replacing files' {
        $script:iss | Should -Match 'Stop-OpenMargin\.ps1"; Flags: dontcopy noencryption'
        $script:iss | Should -Match 'Lifecycle\.Common\.ps1"; Flags: dontcopy noencryption'
        $script:iss | Should -Match 'function PrepareToInstall'
        $script:iss | Should -Match 'StopPreviousInstall\(\)'
    }

    It 'passes the original user to the ACL helpers' {
        $script:iss | Should -Match 'Set-AppAcl\.ps1.*-OriginalUser ""\{username\}""'
        $script:iss | Should -Match 'Install-UrlAcl\.ps1.*-OriginalUser ""\{username\}""'
    }
}

Describe 'Browser fallback and wizard single-instance' {
    It 'self-elevates a Start Menu launch unless -NoElevate is passed' {
        $launch = Get-Content -Path (Join-Path $script:setupRoot 'installer\Launch-Setup.ps1') -Raw -Encoding UTF8
        $launch | Should -Match 'if \(-not \$isElevated -and -not \$NoElevate\)'
        $launch | Should -Match '-Verb RunAs'
        $launch | Should -Match 'elevation was cancelled or failed'
    }

    It 'keeps the token handoff file while the wizard runs' {
        $launch = Get-Content -Path (Join-Path $script:setupRoot 'installer\Launch-Setup.ps1') -Raw -Encoding UTF8
        $launch | Should -Not -Match 'Remove-Item -Force \$handoffPath'
        $launch | Should -Match 'handoff file kept until wizard exit'
        $launch | Should -Match 'Show-SetupUrlFallback'
        $launch | Should -Match 'Wait-LaunchBrowserStarted'
        # Both the reuse path and the fresh-start path must fall back to a
        # visible MessageBox containing the full token URL.
        ([regex]::Matches($launch, 'Show-SetupUrlFallback')).Count | Should -BeGreaterOrEqual 2
        $launch | Should -Match '含一次性登录 token'
        $launch | Should -Match '地址也保存在'
    }

    It 'reuses a matching wizard and clears stale state without stopping other wizards' {
        $launch = Get-Content -Path (Join-Path $script:setupRoot 'installer\Launch-Setup.ps1') -Raw -Encoding UTF8
        $launch | Should -Match 'Stop-OpenMargin\.ps1'
        $launch | Should -Match '-Status'
        $launch | Should -Match 'wizard.stateMatches'
        $launch | Should -Not -Match '-WizardOnly'
        $launch | Should -Match 'Remove-Item -LiteralPath'
        $launch | Should -Match 'setup-web-url\.txt'
        $launch | Should -Match 'Show-SetupUrlFallback'
        $launch | Should -Match 'Open-SetupUrl'
        # The hidden server must not open a second browser of its own.
        $launch | Should -Match '-NoBrowser'
    }

    It 'server keeps the token URL and reports launch failure instead of swallowing it' {
        $server = Get-Content -Path (Join-Path $script:setupRoot 'Start-OpenMarginSetupWeb.ps1') -Raw -Encoding UTF8
        $server | Should -Match 'browser open failed'
        $server | Should -Match 'Open this URL manually'
        $server | Should -Not -Match 'try \{ Start-Process \$url \| Out-Null \} catch \{ \}'
        $server | Should -Match 'dockerLicenseAcknowledged'
        $server | Should -Match 'elevated = \[bool\]\(Test-OmAdmin\)'
        $server | Should -Match 'setup server elevated='
        # Job status/result writes must not use Move-Item -Force on an existing
        # Windows PowerShell 5.1 target.
        $server | Should -Not -Match 'Move-Item -Force \$tmp'
        $server | Should -Match 'function Write-OmJsonFileAtomic'
        $server | Should -Match 'function Write-JobFile'
        $server | Should -Match '\[IO.File\]::Replace'
    }
}

Describe 'Wizard home button' {    It 'is present in the persistent header on every screen' {
        $html = Get-Content -Path (Join-Path $script:setupRoot 'web\index.html') -Raw -Encoding UTF8
        $html | Should -Match 'id="btn-home"'
        $html | Should -Match '返回主界面'
    }

    It 'returns to the welcome screen without stopping job polling' {
        $script = Get-Content -Path (Join-Path $script:setupRoot 'web\app.js') -Raw -Encoding UTF8
        $script | Should -Match "btn-home'\)\.addEventListener\('click', goHome\)"
        $script | Should -Match 'function goHome\(\)'
        $script | Should -Match 'homeRequested = true'
        $script | Should -Match 'if \(homeRequested\)'
        $script | Should -Not -Match 'stopPolling\(\).*goHome'
    }
}
