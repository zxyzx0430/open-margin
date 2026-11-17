#Requires -Version 5.1
# Lifecycle, upgrade-safety and ACL tests for the Windows installer.
# These tests never touch a real install: every live uninstall/stop run uses
# -SkipWatchdogTask/-SkipUrlAcl plus fake docker/tailscale executables, and
# every process stop targets a process the test started itself.

BeforeAll {
    $script:setupRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
    $script:repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
    $script:installerDir = Join-Path $script:setupRoot 'installer'
    $script:stopScript = Join-Path $script:installerDir 'Stop-OpenMargin.ps1'
    $script:uninstallScript = Join-Path $script:installerDir 'Uninstall-OpenMargin.ps1'
    $script:setAclScript = Join-Path $script:installerDir 'Set-AppAcl.ps1'
    $script:urlAclScript = Join-Path $script:installerDir 'Install-UrlAcl.ps1'
    $script:commonScript = Join-Path $script:installerDir 'Lifecycle.Common.ps1'
    $script:issPath = Join-Path $script:installerDir 'OpenMargin.iss'
    $script:psExe = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'

    $script:fakeDir = Join-Path $TestDrive 'fakebin'
    New-Item -ItemType Directory -Force -Path $script:fakeDir | Out-Null
    $script:fakeDockerLog = Join-Path $TestDrive 'docker-calls.log'
    $script:fakeTailscaleLog = Join-Path $TestDrive 'tailscale-calls.log'
    $script:fakeFunnelStatus = Join-Path $TestDrive 'funnel-status.json'
    $script:fakeServeStatus = Join-Path $TestDrive 'serve-status.json'

    $dockerCmd = @'
@echo off
echo %*>>"%FAKE_DOCKER_LOG%"
exit /b 0
'@
    $tailscaleCmd = @'
@echo off
echo %*>>"%FAKE_TS_LOG%"
if "%1"=="funnel" if "%2"=="status" goto funnelstatus
if "%1"=="serve" if "%2"=="status" goto servestatus
exit /b 0
:funnelstatus
if exist "%FAKE_FUNNEL_STATUS%" type "%FAKE_FUNNEL_STATUS%"
exit /b 0
:servestatus
if exist "%FAKE_SERVE_STATUS%" type "%FAKE_SERVE_STATUS%"
exit /b 0
'@
    Set-Content -Path (Join-Path $script:fakeDir 'docker.cmd') -Value $dockerCmd -Encoding ASCII
    Set-Content -Path (Join-Path $script:fakeDir 'tailscale.cmd') -Value $tailscaleCmd -Encoding ASCII

    $env:FAKE_DOCKER_LOG = $script:fakeDockerLog
    $env:FAKE_TS_LOG = $script:fakeTailscaleLog
    $env:FAKE_FUNNEL_STATUS = $script:fakeFunnelStatus
    $env:FAKE_SERVE_STATUS = $script:fakeServeStatus
    $script:originalPath = $env:PATH
    $env:PATH = $script:fakeDir + ';' + $env:PATH

    $script:serveOurs = '{"TCP":{},"Web":{"pc.tailnet.ts.net:443":{"Handlers":{"/":{"Proxy":"http://127.0.0.1:18768"}}}}}'
    $script:funnelOurs = '{"TCP":{},"AllowFunnel":{"pc.tailnet.ts.net:443":true},"Web":{"pc.tailnet.ts.net:443":{"Handlers":{"/":{"Proxy":"http://127.0.0.1:18768"}}}}}'
    $script:serveForeign = '{"TCP":{},"Web":{"pc.tailnet.ts.net:443":{"Handlers":{"/":{"Proxy":"http://127.0.0.1:19999"}}}}}'
    $script:funnelForeign = '{"TCP":{},"AllowFunnel":{"pc.tailnet.ts.net:443":true},"Web":{"pc.tailnet.ts.net:443":{"Handlers":{"/":{"Proxy":"http://127.0.0.1:19999"}}}}}'

    function Invoke-PsFile {
        param([string]$Path, [string[]]$Arguments)
        $output = & $script:psExe -NoProfile -ExecutionPolicy Bypass -File $Path @Arguments 2>&1 | Out-String
        return [pscustomobject]@{ Output = $output; ExitCode = $LASTEXITCODE }
    }

    function New-TestApp {
        param([string]$Name)
        $root = Join-Path $TestDrive $Name
        foreach ($dir in @('deploy\windows\setup\installer', 'backend', 'docs', 'secrets')) {
            New-Item -ItemType Directory -Force -Path (Join-Path $root $dir) | Out-Null
        }
        Set-Content -Path (Join-Path $root 'compose.yaml') -Value 'services: {}' -Encoding ASCII
        Set-Content -Path (Join-Path $root '.env') -Value 'FINANCE_API_PORT=8767' -Encoding ASCII
        Set-Content -Path (Join-Path $root 'secrets\backend.env') -Value 'FINANCE_DEVICE_TOKEN=test-token' -Encoding ASCII
        return $root
    }

    function Get-JsonPlan {
        param([string]$Output, [string]$Prefix)
        $line = @($Output -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) }) | Select-Object -Last 1
        if (-not $line) { return $null }
        return ($line.Substring($Prefix.Length) | ConvertFrom-Json)
    }

    function Invoke-EnvHarness {
        param(
            [string]$Name,
            [string[]]$ScriptArguments,
            [hashtable]$Environment
        )
        $harnessPath = Join-Path $TestDrive ($Name + '-harness.ps1')
        $lines = New-Object System.Collections.Generic.List[string]
        $lines.Add('$ErrorActionPreference = ''Continue''')
        foreach ($key in $Environment.Keys) {
            $value = ([string]$Environment[$key]).Replace("'", "''")
            if ($key -eq 'PATH') {
                $lines.Add('$env:PATH = ''' + $value + '''')
            } elseif ($key -eq 'ProgramFiles(x86)') {
                $lines.Add("[Environment]::SetEnvironmentVariable('ProgramFiles(x86)', '" + $value + "', 'Process')")
            } else {
                $lines.Add('$env:' + $key + ' = ''' + $value + '''')
            }
        }
        $scriptPath = $script:uninstallScript.Replace("'", "''")
        # Parameter names must stay unquoted so the child binder recognizes
        # them; values are single-quoted. Quoting '-AppRoot' would pass it as a
        # positional string instead of a parameter name.
        $quotedArgs = @($ScriptArguments | ForEach-Object {
            $value = [string]$_
            if ($value.StartsWith('-')) {
                $value
            } else {
                "'" + $value.Replace("'", "''") + "'"
            }
        }) -join ' '
        $lines.Add(('& ''' + $scriptPath + ''' ' + $quotedArgs))
        $lines.Add('exit $LASTEXITCODE')
        Set-Content -Path $harnessPath -Value ($lines -join "`r`n") -Encoding ASCII
        $output = & $script:psExe -NoProfile -ExecutionPolicy Bypass -File $harnessPath 2>&1 | Out-String
        return [pscustomobject]@{ Output = $output; ExitCode = $LASTEXITCODE }
    }
}

AfterAll {
    if ($script:originalPath) { $env:PATH = $script:originalPath }
    foreach ($name in @('FAKE_DOCKER_LOG', 'FAKE_TS_LOG', 'FAKE_FUNNEL_STATUS', 'FAKE_SERVE_STATUS')) {
        Remove-Item -Path ('Env:' + $name) -ErrorAction SilentlyContinue
    }
}

Describe 'Lifecycle script safety invariants' {
    BeforeAll {
        $script:stopText = Get-Content -Path $script:stopScript -Raw -Encoding UTF8
        $script:uninstallText = Get-Content -Path $script:uninstallScript -Raw -Encoding UTF8
        $script:commonText = Get-Content -Path $script:commonScript -Raw -Encoding UTF8
        $script:issText = Get-Content -Path $script:issPath -Raw -Encoding UTF8
    }

    It 'never passes -v or --volumes to compose down' {
        foreach ($text in @($script:stopText, $script:uninstallText, $script:commonText)) {
            $text | Should -Not -Match '(?i)compose[^\r\n]*\sdown\s+(-v|--volumes)(\s|$)'
        }
        $script:commonText | Should -Match "'down'"
    }

    It 'keeps the lifecycle scripts ASCII-only for {tmp} extraction' {
        foreach ($name in @('Lifecycle.Common.ps1', 'Stop-OpenMargin.ps1', 'Uninstall-OpenMargin.ps1')) {
            $bytes = [System.IO.File]::ReadAllBytes((Join-Path $script:installerDir $name))
            $nonAscii = 0
            foreach ($byte in $bytes) { if ($byte -gt 127) { $nonAscii++ } }
            $nonAscii | Should -Be 0
        }
    }

    It 'writes a preserved-items summary with manual removal steps' {
        $script:uninstallText | Should -Match 'Preserved'
        $script:uninstallText | Should -Match 'Manual cleanup'
        $script:uninstallText | Should -Match 'docker volume ls --filter label=com\.docker\.compose\.project='
        $script:uninstallText | Should -Match 'docker volume rm'
    }
}

Describe 'OpenMargin.iss lifecycle wiring' {
    BeforeAll {
        $script:issText = Get-Content -Path $script:issPath -Raw -Encoding UTF8
        $match = [regex]::Match($script:issText, '(?ms)^\[UninstallRun\]\s*(.*?)(?=^\[)')
        $script:uninstallRunBlock = $(if ($match.Success) { $match.Groups[1].Value } else { '' })
    }

    It 'runs Uninstall-OpenMargin.ps1 as the first uninstall step and waits' {
        $script:uninstallRunBlock | Should -Match 'Uninstall-OpenMargin\.ps1'
        $script:uninstallRunBlock | Should -Match 'waituntilterminated'
        $script:uninstallRunBlock | Should -Match 'RunOnceId'
        $script:uninstallRunBlock | Should -Match 'skipifdoesntexist'
        $script:uninstallRunBlock | Should -Match '-AppRoot'
        $script:uninstallRunBlock | Should -Match '-StateRoot'
    }

    It 'stops the previous install before files are replaced' {
        $script:issText | Should -Match 'PrepareToInstall'
        $script:issText | Should -Match 'Stop-OpenMargin\.ps1'
        $script:issText | Should -Match 'ExtractTemporaryFile'
        $script:issText | Should -Match 'dontcopy'
        $script:issText | Should -Match 'CloseApplications=yes'
        $script:issText | Should -Match 'RestartApplications=no'
    }

    It 'does not delete preserved user data on uninstall' {
        $deleteMatch = [regex]::Match($script:issText, '(?ms)^\[UninstallDelete\]\s*(.*?)(?=^\[|\z)')
        if ($deleteMatch.Success) {
            $deleteMatch.Groups[1].Value | Should -Not -Match '(?i)secrets|\.env'
        }
        $script:issText | Should -Match 'uninsneveruninstall'
        $script:issText | Should -Match 'Name: "\{app\}\\secrets"'
    }
}

Describe 'Stop-OpenMargin.ps1 dry-run and single-instance status' {
    It 'plans compose down without -v and makes no external calls in dry-run' {
        $app = New-TestApp 'stop-dry'
        $state = Join-Path $TestDrive 'stop-dry-state'
        Remove-Item -Force $script:fakeDockerLog -ErrorAction SilentlyContinue
        $result = Invoke-PsFile $script:stopScript @(
            '-AppRoot', $app, '-StateRoot', $state, '-DryRun', '-SkipWatchdogTask', '-Json'
        )
        $result.ExitCode | Should -Be 0
        $plan = $result.Output | ConvertFrom-Json
        $compose = @($plan.actions | Where-Object { $_.name -eq 'compose down' })[0]
        $compose.status | Should -Be 'planned'
        $compose.detail | Should -Match 'compose --project-name personal-finance-system'
        $compose.detail | Should -Match '\sdown$'
        $compose.detail | Should -Not -Match '(\s-v|\s--volumes)(\s|$)'
        Test-Path $script:fakeDockerLog | Should -BeFalse
    }

    It 'reports a recorded setup-web.json PID without treating an unrelated process as the wizard' {
        $app = New-TestApp 'stop-status'
        $state = Join-Path $TestDrive 'stop-status-state'
        New-Item -ItemType Directory -Force -Path $state | Out-Null
        $dummy = Start-Process -FilePath $script:psExe -ArgumentList @('-NoProfile', '-Command', 'Start-Sleep', '-Seconds', '60') -PassThru -WindowStyle Hidden
        try {
            Set-Content -Path (Join-Path $state 'setup-web.json') -Encoding ASCII -Value (
                '{"pid":' + $dummy.Id + ',"port":18770,"url":"http://127.0.0.1:18770/"}'
            )
            $result = Invoke-PsFile $script:stopScript @(
                '-AppRoot', $app, '-StateRoot', $state, '-Status', '-Json'
            )
            $result.ExitCode | Should -Be 0
            $status = $result.Output | ConvertFrom-Json
            $status.wizard.running | Should -BeFalse
            $status.wizard.statePid | Should -Be $dummy.Id
            $status.wizard.statePidAlive | Should -BeTrue
            $status.wizard.stateMatches | Should -BeFalse
            (Get-Process -Id $dummy.Id -ErrorAction SilentlyContinue) | Should -Not -BeNullOrEmpty
        } finally {
            Stop-Process -Id $dummy.Id -Force -ErrorAction SilentlyContinue
        }
    }

    It 'stops only the hidden wizard whose command line points at this app root' {
        $app = New-TestApp 'stop-wizard'
        $state = Join-Path $TestDrive 'stop-wizard-state'
        $wizardScript = Join-Path $app 'deploy\windows\setup\Start-OpenMarginSetupWeb.ps1'
        Set-Content -Path $wizardScript -Value 'Start-Sleep -Seconds 120' -Encoding ASCII
        $wizard = Start-Process -FilePath $script:psExe -ArgumentList @(
            '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $wizardScript + '"')
        ) -PassThru -WindowStyle Hidden
        try {
            $deadline = (Get-Date).AddSeconds(20)
            $running = $false
            do {
                Start-Sleep -Milliseconds 500
                $statusResult = Invoke-PsFile $script:stopScript @(
                    '-AppRoot', $app, '-StateRoot', $state, '-Status', '-Json'
                )
                $status = $statusResult.Output | ConvertFrom-Json
                $running = [bool]$status.wizard.running
            } until ($running -or (Get-Date) -gt $deadline)
            $running | Should -BeTrue

            $stopResult = Invoke-PsFile $script:stopScript @(
                '-AppRoot', $app, '-StateRoot', $state, '-WizardOnly', '-Json'
            )
            $stopResult.ExitCode | Should -Be 0
            $stopPlan = $stopResult.Output | ConvertFrom-Json
            @($stopPlan.actions | Where-Object { $_.name -like 'wizard pid *' -and $_.status -eq 'done' }).Count | Should -Be 1

            $deadline = (Get-Date).AddSeconds(10)
            while (-not $wizard.HasExited -and (Get-Date) -lt $deadline) {
                Start-Sleep -Milliseconds 250
                $wizard.Refresh()
            }
            $wizard.HasExited | Should -BeTrue
        } finally {
            if (-not $wizard.HasExited) { Stop-Process -Id $wizard.Id -Force -ErrorAction SilentlyContinue }
        }
    }

    It 'stops compose down without -v in a live fake run' {
        $app = New-TestApp 'stop-live'
        $state = Join-Path $TestDrive 'stop-live-state'
        Remove-Item -Force $script:fakeDockerLog -ErrorAction SilentlyContinue
        $result = Invoke-PsFile $script:stopScript @(
            '-AppRoot', $app, '-StateRoot', $state, '-SkipWatchdogTask', '-Json'
        )
        $result.ExitCode | Should -Be 0
        $plan = $result.Output | ConvertFrom-Json
        $compose = @($plan.actions | Where-Object { $_.name -eq 'compose down' })[0]
        $compose.status | Should -Be 'done'
        $calls = Get-Content -Path $script:fakeDockerLog -Raw -Encoding UTF8
        $calls | Should -Match 'compose --project-name personal-finance-system'
        $calls | Should -Match '\sdown'
        $calls | Should -Not -Match '(\s-v|\s--volumes)(\s|$)'
    }
}

Describe 'Uninstall-OpenMargin.ps1' {
    It 'dry-run makes no docker or tailscale calls and preserves secrets' {
        $app = New-TestApp 'uninstall-dry'
        $state = Join-Path $TestDrive 'uninstall-dry-state'
        $summary = Join-Path $TestDrive 'uninstall-dry-summary.txt'
        Remove-Item -Force $script:fakeDockerLog -ErrorAction SilentlyContinue
        Remove-Item -Force $script:fakeTailscaleLog -ErrorAction SilentlyContinue
        $result = Invoke-PsFile $script:uninstallScript @(
            '-AppRoot', $app, '-StateRoot', $state, '-DryRun',
            '-SkipWatchdogTask', '-SkipUrlAcl', '-SkipFunnel',
            '-SummaryPath', $summary, '-Json'
        )
        $result.ExitCode | Should -Be 0
        $plan = $result.Output | ConvertFrom-Json
        $plan.ok | Should -BeTrue
        $plan.dryRun | Should -BeTrue
        Test-Path $script:fakeDockerLog | Should -BeFalse
        Test-Path $script:fakeTailscaleLog | Should -BeFalse
        Test-Path $summary | Should -BeTrue
        (Get-Content -Path $summary -Raw -Encoding UTF8) | Should -Match 'Preserved'
        (Get-Content -Path $summary -Raw -Encoding UTF8) | Should -Match 'secrets'
        Test-Path (Join-Path $app 'secrets\backend.env') | Should -BeTrue
    }

    It 'stops compose without -v, preserves data and removes stale wizard state' {
        $app = New-TestApp 'uninstall-live'
        $state = Join-Path $TestDrive 'uninstall-live-state'
        $summary = Join-Path $TestDrive 'uninstall-live-summary.txt'
        New-Item -ItemType Directory -Force -Path $state | Out-Null
        $stateFile = Join-Path $state 'setup-web.json'
        Set-Content -Path $stateFile -Encoding ASCII -Value '{"pid":99999999,"port":18770,"url":"http://127.0.0.1:18770/"}'
        Remove-Item -Force $script:fakeDockerLog -ErrorAction SilentlyContinue
        $result = Invoke-PsFile $script:uninstallScript @(
            '-AppRoot', $app, '-StateRoot', $state,
            '-SkipWatchdogTask', '-SkipUrlAcl', '-SkipFunnel',
            '-SummaryPath', $summary, '-Json'
        )
        $result.ExitCode | Should -Be 0
        $plan = $result.Output | ConvertFrom-Json
        $compose = @($plan.actions | Where-Object { $_.name -eq 'compose down' })[0]
        $compose.status | Should -Be 'done'
        $calls = Get-Content -Path $script:fakeDockerLog -Raw -Encoding UTF8
        $calls | Should -Match 'compose --project-name personal-finance-system'
        $calls | Should -Match '\sdown'
        $calls | Should -Not -Match '(\s-v|\s--volumes)(\s|$)'
        Test-Path (Join-Path $app 'secrets\backend.env') | Should -BeTrue
        Test-Path (Join-Path $app '.env') | Should -BeTrue
        Test-Path $stateFile | Should -BeFalse
        $summaryText = Get-Content -Path $summary -Raw -Encoding UTF8
        $summaryText | Should -Match 'docker volume ls'
        $summaryText | Should -Match 'DESTROYS ledger data'
    }

    It 'removes only our Tailscale Funnel and Serve routes' {
        $app = New-TestApp 'uninstall-funnel'
        $state = Join-Path $TestDrive 'uninstall-funnel-state'
        $summary = Join-Path $TestDrive 'uninstall-funnel-summary.txt'
        Set-Content -Path $script:fakeFunnelStatus -Value $script:funnelOurs -Encoding ASCII
        Set-Content -Path $script:fakeServeStatus -Value $script:serveOurs -Encoding ASCII
        Remove-Item -Force $script:fakeTailscaleLog -ErrorAction SilentlyContinue
        $result = Invoke-PsFile $script:uninstallScript @(
            '-AppRoot', $app, '-StateRoot', $state,
            '-SkipWatchdogTask', '-SkipUrlAcl', '-SkipCompose',
            '-SummaryPath', $summary, '-Json'
        )
        $result.ExitCode | Should -Be 0
        $plan = $result.Output | ConvertFrom-Json
        @($plan.actions | Where-Object { $_.name -eq 'funnel' -and $_.status -eq 'done' }).Count | Should -Be 1
        @($plan.actions | Where-Object { $_.name -eq 'serve' -and $_.status -eq 'done' }).Count | Should -Be 1
        $calls = Get-Content -Path $script:fakeTailscaleLog -Raw -Encoding UTF8
        $calls | Should -Match 'funnel status --json'
        $calls | Should -Match 'funnel --https=443 off'
        $calls | Should -Match 'serve status --json'
        $calls | Should -Match 'serve --https=443 off'
    }

    It 'refuses to touch a foreign Tailscale route' {
        $app = New-TestApp 'uninstall-foreign'
        $state = Join-Path $TestDrive 'uninstall-foreign-state'
        $summary = Join-Path $TestDrive 'uninstall-foreign-summary.txt'
        Set-Content -Path $script:fakeFunnelStatus -Value $script:funnelForeign -Encoding ASCII
        Set-Content -Path $script:fakeServeStatus -Value $script:serveForeign -Encoding ASCII
        Remove-Item -Force $script:fakeTailscaleLog -ErrorAction SilentlyContinue
        $result = Invoke-PsFile $script:uninstallScript @(
            '-AppRoot', $app, '-StateRoot', $state,
            '-SkipWatchdogTask', '-SkipUrlAcl', '-SkipCompose',
            '-SummaryPath', $summary, '-Json'
        )
        $result.ExitCode | Should -Be 0
        $plan = $result.Output | ConvertFrom-Json
        @($plan.actions | Where-Object { $_.name -eq 'funnel' -and $_.status -eq 'skipped' }).Count | Should -Be 1
        @($plan.actions | Where-Object { $_.name -eq 'serve' -and $_.status -eq 'skipped' }).Count | Should -Be 1
        $calls = Get-Content -Path $script:fakeTailscaleLog -Raw -Encoding UTF8
        $calls | Should -Not -Match '\soff(\s|$)'
    }

    It 'continues and reports when docker is missing' {
        $app = New-TestApp 'uninstall-nodocker'
        $state = Join-Path $TestDrive 'uninstall-nodocker-state'
        $summary = Join-Path $TestDrive 'uninstall-nodocker-summary.txt'
        $result = Invoke-EnvHarness -Name 'nodocker' -Environment @{
            PATH = ($env:SystemRoot + '\System32;' + $env:SystemRoot)
        } -ScriptArguments @(
            '-AppRoot', $app, '-StateRoot', $state,
            '-SkipWatchdogTask', '-SkipUrlAcl', '-SkipFunnel',
            '-SummaryPath', $summary, '-Json'
        )
        $result.ExitCode | Should -Be 0
        $plan = $result.Output | ConvertFrom-Json
        $compose = @($plan.actions | Where-Object { $_.name -eq 'compose down' })[0]
        $compose.status | Should -Be 'skipped'
        $compose.detail | Should -Match 'docker not found'
    }

    It 'continues and reports when Tailscale is missing' {
        $app = New-TestApp 'uninstall-nots'
        $state = Join-Path $TestDrive 'uninstall-nots-state'
        $summary = Join-Path $TestDrive 'uninstall-nots-summary.txt'
        $emptyProgramFiles = Join-Path $TestDrive 'empty-program-files'
        New-Item -ItemType Directory -Force -Path $emptyProgramFiles | Out-Null
        $result = Invoke-EnvHarness -Name 'nots' -Environment @{
            PATH = ($env:SystemRoot + '\System32;' + $env:SystemRoot)
            ProgramFiles = $emptyProgramFiles
            'ProgramFiles(x86)' = $emptyProgramFiles
        } -ScriptArguments @(
            '-AppRoot', $app, '-StateRoot', $state,
            '-SkipWatchdogTask', '-SkipUrlAcl', '-SkipCompose',
            '-SummaryPath', $summary, '-Json'
        )
        $result.ExitCode | Should -Be 0
        $plan = $result.Output | ConvertFrom-Json
        $funnel = @($plan.actions | Where-Object { $_.name -eq 'funnel' })[0]
        $funnel.status | Should -Be 'skipped'
        $funnel.detail | Should -Match 'Tailscale not installed'
    }
}

Describe 'ACL hardening' {
    BeforeAll {
        $script:testSid = 'S-1-5-21-1111111111-2222222222-3333333333-1001'
        $script:aclApp = New-TestApp 'acl-app'
    }

    It 'grants the app root write only to the installing SID, Administrators and SYSTEM' {
        $result = Invoke-PsFile $script:setAclScript @(
            '-AppRoot', $script:aclApp, '-OriginalUserSid', $script:testSid, '-DryRun'
        )
        $result.ExitCode | Should -Be 0
        $plan = Get-JsonPlan -Output $result.Output -Prefix 'OPENMARGIN_ACL_PLAN='
        $plan | Should -Not -BeNullOrEmpty
        $plan.originalUserSid | Should -Be $script:testSid
        $commands = @($plan.commands)

        $rootGrant = @($commands | Where-Object {
            $_.arguments -contains $script:aclApp -and $_.arguments -contains '/grant:r'
        })[0]
        $rootGrant | Should -Not -BeNullOrEmpty
        $rootGrant.arguments | Should -Contain ('*' + $script:testSid + ':(OI)(CI)M')
        $rootGrant.arguments | Should -Contain '*S-1-5-32-544:(OI)(CI)F'
        $rootGrant.arguments | Should -Contain '*S-1-5-18:(OI)(CI)F'

        foreach ($command in $commands) {
            $joined = $command.arguments -join ' '
            $joined | Should -Not -Match '\*S-1-5-32-545:\(OI\)\(CI\)M'
            $joined | Should -Not -Match '\*S-1-1-0:\(OI\)\(CI\)M'
        }

        $backendGrant = @($commands | Where-Object {
            ($_.arguments -join ' ') -match '\\backend' -and $_.arguments -contains '/grant:r'
        })[0]
        $backendGrant.arguments | Should -Contain '*S-1-5-32-545:(OI)(CI)RX'

        $secretGrant = @($commands | Where-Object {
            ($_.arguments -join ' ') -match '\\secrets' -and $_.arguments -contains '/grant:r'
        })[0]
        $secretGrant.arguments | Should -Contain ('*' + $script:testSid + ':(OI)(CI)F')
        $secretGrant.arguments | Should -Contain '/T'

        $legacyCleanup = @($commands | Where-Object {
            $_.arguments -contains '/remove:g' -and $_.arguments -contains '*S-1-5-32-545'
        })
        $legacyCleanup.Count | Should -BeGreaterThan 0
    }

    It 'accepts -OriginalUser and resolves a SID without falling back to Users' {
        $result = Invoke-PsFile $script:setAclScript @(
            '-AppRoot', $script:aclApp, '-OriginalUser', $env:USERNAME, '-DryRun'
        )
        $result.ExitCode | Should -Be 0
        $plan = Get-JsonPlan -Output $result.Output -Prefix 'OPENMARGIN_ACL_PLAN='
        $plan.fallbackToUsers | Should -BeFalse
        $plan.originalUserSid | Should -Match '^S-1-5-'
    }

    It 'rejects an invalid SID instead of silently using Users' {
        $result = Invoke-PsFile $script:setAclScript @(
            '-AppRoot', $script:aclApp, '-OriginalUserSid', 'not-a-sid', '-DryRun'
        )
        $result.ExitCode | Should -Be 2
        $result.Output | Should -Match 'invalid'
    }

    It 'produces an SDDL URL ACL for the installing user plus Administrators only' {
        $result = Invoke-PsFile $script:urlAclScript @(
            '-Ports', '18770', '-OriginalUserSid', $script:testSid, '-DryRun'
        )
        $result.ExitCode | Should -Be 0
        $plan = Get-JsonPlan -Output $result.Output -Prefix 'OPENMARGIN_URLACL_PLAN='
        $plan | Should -Not -BeNullOrEmpty
        $plan.originalUserSid | Should -Be $script:testSid
        $commands = @($plan.commands)
        $add = @($commands | Where-Object { $_.arguments -contains 'add' })[0]
        $add | Should -Not -BeNullOrEmpty
        ($add.arguments -join ' ') | Should -Match ('sddl=D:\(A;;GX;;;' + [regex]::Escape($script:testSid) + '\)\(A;;GX;;;S-1-5-32-544\)')
        foreach ($command in $commands) {
            ($command.arguments -join ' ') | Should -Not -Match 'S-1-5-32-545'
            ($command.arguments -join ' ') | Should -Not -Match 'S-1-1-0'
        }
        $fallback = @($plan.fallbackCommands)[0]
        ($fallback.arguments -join ' ') | Should -Match ('user=\*' + [regex]::Escape($script:testSid))
    }

    It 'removes URL ACLs without adding new grants' {
        $result = Invoke-PsFile $script:urlAclScript @(
            '-Ports', '18770', '-Remove', '-OriginalUserSid', $script:testSid, '-DryRun'
        )
        $result.ExitCode | Should -Be 0
        $plan = Get-JsonPlan -Output $result.Output -Prefix 'OPENMARGIN_URLACL_PLAN='
        $commands = @($plan.commands)
        @($commands | Where-Object { $_.arguments -contains 'add' }).Count | Should -Be 0
        @($commands | Where-Object { $_.arguments -contains 'delete' }).Count | Should -Be 1
    }

    It 'rejects an invalid SID for URL ACLs' {
        $result = Invoke-PsFile $script:urlAclScript @(
            '-Ports', '18770', '-OriginalUserSid', 'not-a-sid', '-DryRun'
        )
        $result.ExitCode | Should -Be 2
        $result.Output | Should -Match 'invalid'
    }
}
