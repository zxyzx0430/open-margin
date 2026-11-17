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
        $elevated = $runLines | Where-Object { $_ -notmatch 'runasoriginaluser' } | Select-Object -First 1
        $unelevated = $runLines | Where-Object { $_ -match 'runasoriginaluser' } | Select-Object -First 1
        $elevated | Should -Not -BeNullOrEmpty
        $unelevated | Should -Not -BeNullOrEmpty
        foreach ($line in $runLines) {
            $line | Should -Not -Match 'skipifsilent'
            $line | Should -Match 'postinstall'
            $line | Should -Match 'nowait'
        }
        $elevated | Should -Match 'Check:\s*ShouldRunElevatedWizard'
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

Describe 'Wizard home button' {
    It 'is present in the persistent header on every screen' {
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
