#Requires -Version 5.1
<#
.SYNOPSIS
    Real-install acceptance tests for the Windows setup wizard.

.DESCRIPTION
    These tests exercise the failure modes observed during a real Windows
    install. They never touch D:\OpenMargin\app, the real state root, the
    installer, or a real browser:

      * a temp copy of the setup engine for the progress writer,
      * fake tailscale.exe / netsh.exe commands in a temp PATH,
      * a real wizard server on loopback ports 18790+ with a temp state root,
      * a fake late-handoff server in a temp app layout for Launch-Setup.

    Every process started here is tracked and stopped in AfterEach/AfterAll.
    Nothing is killed unless this file started it.

    The assertions encode the behavior a real install needs; a failing test is
    a product bug report, not a flaky harness. Run with:
        scripts\test-real-install-acceptance.ps1
#>

BeforeAll {
    $script:setupRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
    $script:repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
    $script:modulePath = Join-Path $script:setupRoot 'OpenMargin.Setup.psd1'
    $script:winPs = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    Import-Module $script:modulePath -Force -DisableNameChecking

    $script:acceptanceTemp = Join-Path $TestDrive 'acceptance'
    New-Item -ItemType Directory -Force -Path $script:acceptanceTemp | Out-Null
    $script:trackedProcesses = [System.Collections.Generic.List[object]]::new()

    function Add-TrackedProcess {
        param([System.Diagnostics.Process]$Process)
        if ($null -eq $Process) { return }
        $startTime = $null
        try { $startTime = $Process.StartTime } catch { }
        $script:trackedProcesses.Add([pscustomobject]@{ Pid = [int]$Process.Id; StartTime = $startTime })
    }

    function Add-TrackedPid {
        param([int]$ProcessId)
        try {
            $process = Get-Process -Id $ProcessId -ErrorAction Stop
            Add-TrackedProcess -Process $process
        } catch { }
    }

    function Stop-TrackedProcesses {
        foreach ($entry in @($script:trackedProcesses)) {
            try {
                $process = Get-Process -Id $entry.Pid -ErrorAction Stop
                $sameProcess = $true
                if ($null -ne $entry.StartTime) {
                    try { $sameProcess = ($process.StartTime -eq $entry.StartTime) } catch { $sameProcess = $false }
                }
                if ($sameProcess) {
                    Stop-Process -Id $entry.Pid -Force -ErrorAction Stop
                    try { $null = $process.WaitForExit(5000) } catch { }
                }
            } catch { }
        }
        $script:trackedProcesses.Clear()
    }

    function Get-FreeAcceptancePort {
        param([int]$Start = 18790)
        for ($port = $Start; $port -lt ($Start + 60); $port++) {
            $listener = $null
            try {
                $listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, $port)
                $listener.Start()
                return $port
            } catch {
            } finally {
                if ($listener) { try { $listener.Stop() } catch { } }
            }
        }
        throw ('no free loopback port in ' + $Start + '..' + ($Start + 59))
    }

    function ConvertTo-QuotedArgument {
        param([string]$Value)
        if ($Value -match '\s' -and -not ($Value.StartsWith('"') -and $Value.EndsWith('"'))) {
            return ('"' + $Value + '"')
        }
        return $Value
    }

    function New-TestStateRoot {
        param([string]$Name)
        $path = Join-Path $script:acceptanceTemp $Name
        New-Item -ItemType Directory -Force -Path $path | Out-Null
        return $path
    }

    function Invoke-ChildProcess {
        param(
            [Parameter(Mandatory = $true)][string]$FilePath,
            [string[]]$ArgumentList = @(),
            [string]$WorkingDirectory = '',
            [int]$TimeoutSec = 180
        )
        $id = [guid]::NewGuid().ToString('N')
        $stdout = Join-Path $script:acceptanceTemp ('child-' + $id + '.out')
        $stderr = Join-Path $script:acceptanceTemp ('child-' + $id + '.err')
        $params = @{
            FilePath               = $FilePath
            ArgumentList           = $ArgumentList
            PassThru               = $true
            WindowStyle            = 'Hidden'
            RedirectStandardOutput = $stdout
            RedirectStandardError  = $stderr
        }
        if ($WorkingDirectory) { $params['WorkingDirectory'] = $WorkingDirectory }
        $process = Start-Process @params
        if (-not $process.WaitForExit($TimeoutSec * 1000)) {
            try { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue } catch { }
            throw ('child process timed out after ' + $TimeoutSec + 's: ' + $FilePath)
        }
        $process.Refresh()
        return [pscustomobject]@{
            ExitCode = $process.ExitCode
            StdOut   = (Get-Content -Path $stdout -Raw -ErrorAction SilentlyContinue)
            StdErr   = (Get-Content -Path $stderr -Raw -ErrorAction SilentlyContinue)
        }
    }

    function Start-AcceptanceWizard {
        param(
            [int]$Port,
            [Parameter(Mandatory = $true)][string]$StateRoot
        )
        New-Item -ItemType Directory -Force -Path $StateRoot | Out-Null
        $setupScript = Join-Path $script:setupRoot 'Start-OpenMarginSetupWeb.ps1'
        $stdout = Join-Path $StateRoot 'server.stdout.log'
        $stderr = Join-Path $StateRoot 'server.stderr.log'
        $arguments = @(
            '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (ConvertTo-QuotedArgument $setupScript),
            '-Port', [string]$Port,
            '-StateRoot', (ConvertTo-QuotedArgument $StateRoot),
            '-NoBrowser'
        )
        $process = Start-Process -FilePath $script:winPs -ArgumentList $arguments -PassThru -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr
        Add-TrackedProcess -Process $process

        $statePath = Join-Path $StateRoot 'setup-web.json'
        $urlPath = Join-Path $StateRoot 'setup-web-url.txt'
        $deadline = (Get-Date).AddSeconds(25)
        while ((Get-Date) -lt $deadline) {
            if ($process.HasExited) { break }
            if ((Test-Path $statePath) -and (Test-Path $urlPath)) { break }
            Start-Sleep -Milliseconds 200
        }
        if ($process.HasExited) {
            $errorText = Get-Content -Path $stderr -Raw -ErrorAction SilentlyContinue
            throw ('wizard exited with code ' + $process.ExitCode + ': ' + $errorText)
        }
        if (-not (Test-Path $urlPath)) {
            $errorText = Get-Content -Path $stderr -Raw -ErrorAction SilentlyContinue
            throw ('wizard did not write setup-web-url.txt: ' + $errorText)
        }
        $url = (Get-Content -Path $urlPath -Raw -Encoding UTF8).Trim()
        $state = Get-Content -Path $statePath -Raw -Encoding UTF8 | ConvertFrom-Json
        return [pscustomobject]@{
            Process   = $process
            Pid       = [int]$state.pid
            Port      = [int]$state.port
            Url       = $url
            StateRoot = $StateRoot
            State     = $state
        }
    }
}

AfterAll {
    Stop-TrackedProcesses
}

Describe 'Progress writer with a locked progress file' -Tag 'Acceptance' {
    BeforeAll {
        $script:engineCopy = Join-Path $script:acceptanceTemp 'engine-copy'
        if (Test-Path $script:engineCopy) { Remove-Item -Recurse -Force $script:engineCopy -ErrorAction SilentlyContinue }
        Copy-Item -Path $script:setupRoot -Destination $script:engineCopy -Recurse -Force
        $script:copiedModule = Join-Path $script:engineCopy 'OpenMargin.Setup.psd1'

        function New-ProgressWriterCommand {
            param(
                [Parameter(Mandatory = $true)][string]$ProgressPath,
                [Parameter(Mandatory = $true)][string]$Stage
            )
            $module = $script:copiedModule.Replace("'", "''")
            $path = $ProgressPath.Replace("'", "''")
            return ("Import-Module '" + $module + "' -Force -DisableNameChecking; Write-OmProgressFile -Path '" + $path + "' -Stage '" + $Stage + "' -Percent 42 -Message 'acceptance'; exit 0")
        }
    }

    It 'updates the progress payload when the target is not locked' {
        $state = New-TestStateRoot 'progress-ok'
        $progress = Join-Path $state 'job.progress.json'
        Set-Content -Path $progress -Value '{"stage":"old","percent":1,"message":"old"}' -Encoding UTF8
        $command = "`$ErrorActionPreference='Stop'; " + (New-ProgressWriterCommand -ProgressPath $progress -Stage 'updated')
        $result = Invoke-ChildProcess -FilePath $script:winPs -ArgumentList @(
            '-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', $command
        )
        $result.ExitCode | Should -Be 0
        $payload = Get-Content -Path $progress -Raw -Encoding UTF8 | ConvertFrom-Json
        $payload.stage | Should -Be 'updated'
        $payload.percent | Should -Be 42
        $payload.message | Should -Be 'acceptance'
    }

    It 'exits 0 and keeps the previous payload when the target is locked (installer ErrorActionPreference)' {
        $state = New-TestStateRoot 'progress-locked-stop'
        $progress = Join-Path $state 'job.progress.json'
        Set-Content -Path $progress -Value '{"stage":"old","percent":1,"message":"old"}' -Encoding UTF8
        $lock = [System.IO.File]::Open($progress, [System.IO.FileMode]::Open, [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
        try {
            $command = "`$ErrorActionPreference='Stop'; " + (New-ProgressWriterCommand -ProgressPath $progress -Stage 'locked')
            $result = Invoke-ChildProcess -FilePath $script:winPs -ArgumentList @(
                '-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', $command
            )
            $result.ExitCode | Should -Be 0
            ($result.StdOut + $result.StdErr) | Should -Not -Match 'Move-Item|IOException'
        } finally {
            $lock.Dispose()
        }
        $payload = Get-Content -Path $progress -Raw -Encoding UTF8 | ConvertFrom-Json
        $payload.stage | Should -Be 'old'
        $payload.percent | Should -Be 1
    }

    It 'does not leak a non-terminating Move-Item/IOException under the default error preference' {
        # PRODUCT GAP: Move-Item failures are non-terminating, so the empty
        # catch only catches them when the caller set ErrorActionPreference
        # to Stop. Under the default preference the error record escapes.
        $state = New-TestStateRoot 'progress-locked-continue'
        $progress = Join-Path $state 'job.progress.json'
        Set-Content -Path $progress -Value '{"stage":"old","percent":1}' -Encoding UTF8
        $lock = [System.IO.File]::Open($progress, [System.IO.FileMode]::Open, [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
        try {
            $command = New-ProgressWriterCommand -ProgressPath $progress -Stage 'locked'
            $result = Invoke-ChildProcess -FilePath $script:winPs -ArgumentList @(
                '-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', $command
            )
            $result.ExitCode | Should -Be 0
            ($result.StdOut + $result.StdErr) | Should -Not -Match 'Move-Item'
            ($result.StdOut + $result.StdErr) | Should -Not -Match 'IOException'
        } finally {
            $lock.Dispose()
        }
    }
}

Describe 'Tailscale operator flag support' -Tag 'Acceptance' {
    BeforeAll {
        $script:fakeTailscaleDir = Join-Path $script:acceptanceTemp 'fake-tailscale-bin'
        New-Item -ItemType Directory -Force -Path $script:fakeTailscaleDir | Out-Null
        $script:fakeTailscaleLog = Join-Path $script:acceptanceTemp 'tailscale-calls.log'
        $script:originalPathForTailscale = $env:PATH
        $env:PATH = $script:fakeTailscaleDir + ';' + $env:PATH
        $env:FAKE_TAILSCALE_LOG = $script:fakeTailscaleLog

        $fake = @'
@echo off
echo %*>>"%FAKE_TAILSCALE_LOG%"
if "%FAKE_TAILSCALE_MODE%"=="ok" (
  if "%~1"=="set" if "%~2"=="--help" echo   --operator string
  exit /b 0
)
echo flag provided but not defined: -operator 1>&2
exit /b 1
'@
        Set-Content -Path (Join-Path $script:fakeTailscaleDir 'tailscale.cmd') -Value $fake -Encoding ASCII
    }

    BeforeEach {
        Remove-Item -Force $script:fakeTailscaleLog -ErrorAction SilentlyContinue
        $env:FAKE_TAILSCALE_MODE = 'flag-error'
    }

    AfterAll {
        if ($script:originalPathForTailscale) { $env:PATH = $script:originalPathForTailscale }
        Remove-Item -Path 'Env:FAKE_TAILSCALE_LOG' -ErrorAction SilentlyContinue
        Remove-Item -Path 'Env:FAKE_TAILSCALE_MODE' -ErrorAction SilentlyContinue
    }

    It 'returns a warning instead of throwing when the CLI rejects --operator' {
        $result = Set-OmTailscaleOperator
        # Final contract: operator configuration is best effort. A skipped
        # operator is ok=true with warning=true so the install is not failed.
        $result.ok | Should -BeTrue
        $result.warning | Should -BeTrue
        $result.code | Should -Match 'OPERATOR'
        $result.message | Should -Match 'operator|跳'
        $calls = Get-Content -Path $script:fakeTailscaleLog -Raw -Encoding UTF8
        $calls | Should -Match 'set '
    }

    It 'does not fail the dependency install when the operator step is unsupported' {
        Mock -ModuleName OpenMargin.Setup Test-OmAdmin { $true }
        Mock -ModuleName OpenMargin.Setup Test-OmRebootPending { $false }
        Mock -ModuleName OpenMargin.Setup Install-OmWsl {
            [pscustomobject]@{ ok = $true; code = 'WSL_READY'; message = 'ready' }
        }
        Mock -ModuleName OpenMargin.Setup Install-OmDockerDesktop {
            [pscustomobject]@{ ok = $true; code = 'DOCKER_PRESENT'; message = 'present' }
        }
        Mock -ModuleName OpenMargin.Setup Install-OmTailscale {
            [pscustomobject]@{ ok = $true; code = 'TAILSCALE_PRESENT'; message = 'present' }
        }
        Mock -ModuleName OpenMargin.Setup Add-OmUrlAcl {
            [pscustomobject]@{ ok = $true; code = 'URLACL'; message = 'ok' }
        }

        $result = Install-OmDependencies -SetupPort 18799
        $result.ok | Should -BeTrue
        $operator = @($result.steps | Where-Object { $_.code -like 'OPERATOR*' })[0]
        $operator | Should -Not -BeNullOrEmpty
        $operator.ok | Should -BeTrue
        $operator.warning | Should -BeTrue
        @($result.steps | Where-Object { $_.code -eq 'URLACL' }).Count | Should -Be 1
    }

    It 'still succeeds when the CLI accepts --operator' {
        $env:FAKE_TAILSCALE_MODE = 'ok'
        $result = Set-OmTailscaleOperator
        $result.ok | Should -BeTrue
        $result.code | Should -Be 'OPERATOR'
    }
}

Describe 'URL ACL existing reservation' -Tag 'Acceptance' {
    BeforeAll {
        $script:fakeNetshDir = Join-Path $script:acceptanceTemp 'fake-netsh-bin'
        New-Item -ItemType Directory -Force -Path $script:fakeNetshDir | Out-Null
        $script:fakeNetshLog = Join-Path $script:acceptanceTemp 'netsh-calls.log'
        $script:originalPathForNetsh = $env:PATH

        $sourcePath = Join-Path $script:acceptanceTemp 'FakeNetsh.cs'
        $source = @'
using System;
using System.IO;

public class FakeNetsh {
    public static int Main(string[] args) {
        string log = Environment.GetEnvironmentVariable("FAKE_NETSH_LOG");
        if (!string.IsNullOrEmpty(log)) {
            try { File.AppendAllText(log, string.Join(" ", args) + Environment.NewLine); } catch { }
        }
        string mode = Environment.GetEnvironmentVariable("FAKE_NETSH_MODE");
        if (mode == null) { mode = "ok"; }
        bool isDelete = Array.IndexOf(args, "delete") >= 0;
        if (isDelete) {
            Console.Error.WriteLine("Url reservation delete failed, Error: 183");
            return 183;
        }
        bool isAdd = Array.IndexOf(args, "add") >= 0;
        if (isAdd && mode == "exists183") {
            Console.Error.WriteLine("Url reservation add failed, Error: 183");
            Console.Error.WriteLine("Cannot create a file when that file already exists.");
            return 183;
        }
        return 0;
    }
}
'@
        Set-Content -Path $sourcePath -Value $source -Encoding UTF8

        $builderPath = Join-Path $script:acceptanceTemp 'build-fake-netsh.ps1'
        $builder = @'
param([string]$Source, [string]$Output)
Add-Type -Path $Source -OutputType ConsoleApplication -OutputAssembly $Output -ErrorAction Stop
'@
        Set-Content -Path $builderPath -Value $builder -Encoding UTF8

        $exePath = Join-Path $script:fakeNetshDir 'netsh.exe'
        Remove-Item -Force $exePath -ErrorAction SilentlyContinue
        $compile = Invoke-ChildProcess -FilePath $script:winPs -ArgumentList @(
            '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (ConvertTo-QuotedArgument $builderPath),
            '-Source', (ConvertTo-QuotedArgument $sourcePath),
            '-Output', (ConvertTo-QuotedArgument $exePath)
        )
        if (-not (Test-Path $exePath)) {
            throw ('fake netsh.exe compile failed: ' + $compile.StdOut + $compile.StdErr)
        }
        $env:PATH = $script:fakeNetshDir + ';' + $env:PATH
        $env:FAKE_NETSH_LOG = $script:fakeNetshLog
    }

    BeforeEach {
        Remove-Item -Force $script:fakeNetshLog -ErrorAction SilentlyContinue
        $env:FAKE_NETSH_MODE = 'ok'
    }

    AfterAll {
        if ($script:originalPathForNetsh) { $env:PATH = $script:originalPathForNetsh }
        Remove-Item -Path 'Env:FAKE_NETSH_LOG' -ErrorAction SilentlyContinue
        Remove-Item -Path 'Env:FAKE_NETSH_MODE' -ErrorAction SilentlyContinue
    }

    It 'adds the reservation and does not delete an existing one' {
        $result = Add-OmUrlAcl -Port 18798
        $result.ok | Should -BeTrue
        $result.code | Should -Be 'URLACL'
        $calls = Get-Content -Path $script:fakeNetshLog -Raw -Encoding UTF8
        $calls | Should -Match 'http add urlacl'
        $calls | Should -Match 'url=http://127\.0\.0\.1:18798/'
        $calls | Should -Not -Match 'delete'
    }

    It 'treats error 183 (existing reservation) as success without deleting it' {
        # PRODUCT GAP: Add-OmUrlAcl returns ok=$false for exit code 183, which
        # an idempotent install must treat as "already reserved".
        $env:FAKE_NETSH_MODE = 'exists183'
        $result = Add-OmUrlAcl -Port 18798
        $result.ok | Should -BeTrue
        $result.code | Should -Match 'URLACL'
        $result.message | Should -Match '183|exists'
        $calls = Get-Content -Path $script:fakeNetshLog -Raw -Encoding UTF8
        $calls | Should -Match 'http add urlacl'
        $calls | Should -Not -Match 'delete'
    }
}

Describe 'Reboot detection after dependency installation' -Tag 'Acceptance' {
    It 'does not force a reboot when WSL and Tailscale are already ready' {
        Mock -ModuleName OpenMargin.Setup Test-OmAdmin { $true }
        Mock -ModuleName OpenMargin.Setup Test-OmRebootPending { $false }
        Mock -ModuleName OpenMargin.Setup Install-OmWsl {
            [pscustomobject]@{ ok = $true; code = 'WSL_READY'; message = 'ready' }
        }
        Mock -ModuleName OpenMargin.Setup Install-OmDockerDesktop {
            [pscustomobject]@{ ok = $true; code = 'DOCKER_PRESENT'; message = 'present' }
        }
        Mock -ModuleName OpenMargin.Setup Install-OmTailscale {
            [pscustomobject]@{ ok = $true; code = 'TAILSCALE_PRESENT'; message = 'present' }
        }
        Mock -ModuleName OpenMargin.Setup Set-OmTailscaleOperator {
            [pscustomobject]@{ ok = $true; code = 'OPERATOR'; message = 'ok' }
        }
        Mock -ModuleName OpenMargin.Setup Add-OmUrlAcl {
            [pscustomobject]@{ ok = $true; code = 'URLACL'; message = 'ok' }
        }

        $result = Install-OmDependencies -SetupPort 18799
        $result.ok | Should -BeTrue
        $result.pendingReboot | Should -BeFalse
    }

    It 'requires a reboot after a fresh WSL install even when the registry is not flagged yet' {
        # PRODUCT GAP: Install-OmDependencies only calls Test-OmRebootPending;
        # it ignores WSL_INSTALLED / WSL_INSTALLED_DISM from the step results.
        Mock -ModuleName OpenMargin.Setup Test-OmAdmin { $true }
        Mock -ModuleName OpenMargin.Setup Test-OmRebootPending { $false }
        Mock -ModuleName OpenMargin.Setup Install-OmWsl {
            [pscustomobject]@{ ok = $true; code = 'WSL_INSTALLED'; message = 'installed, reboot may be required' }
        }
        Mock -ModuleName OpenMargin.Setup Install-OmDockerDesktop {
            [pscustomobject]@{ ok = $true; code = 'DOCKER_PRESENT'; message = 'present' }
        }
        Mock -ModuleName OpenMargin.Setup Install-OmTailscale {
            [pscustomobject]@{ ok = $true; code = 'TAILSCALE_PRESENT'; message = 'present' }
        }
        Mock -ModuleName OpenMargin.Setup Set-OmTailscaleOperator {
            [pscustomobject]@{ ok = $true; code = 'OPERATOR'; message = 'ok' }
        }
        Mock -ModuleName OpenMargin.Setup Add-OmUrlAcl {
            [pscustomobject]@{ ok = $true; code = 'URLACL'; message = 'ok' }
        }

        $result = Install-OmDependencies -SetupPort 18799
        $result.ok | Should -BeTrue
        $result.pendingReboot | Should -BeTrue
    }

    It 'requires a reboot when WSL was enabled through DISM' {
        Mock -ModuleName OpenMargin.Setup Test-OmAdmin { $true }
        Mock -ModuleName OpenMargin.Setup Test-OmRebootPending { $false }
        Mock -ModuleName OpenMargin.Setup Install-OmWsl {
            [pscustomobject]@{ ok = $true; code = 'WSL_INSTALLED_DISM'; message = 'DISM enabled, reboot required' }
        }
        Mock -ModuleName OpenMargin.Setup Install-OmDockerDesktop {
            [pscustomobject]@{ ok = $true; code = 'DOCKER_PRESENT'; message = 'present' }
        }
        Mock -ModuleName OpenMargin.Setup Install-OmTailscale {
            [pscustomobject]@{ ok = $true; code = 'TAILSCALE_PRESENT'; message = 'present' }
        }
        Mock -ModuleName OpenMargin.Setup Set-OmTailscaleOperator {
            [pscustomobject]@{ ok = $true; code = 'OPERATOR'; message = 'ok' }
        }
        Mock -ModuleName OpenMargin.Setup Add-OmUrlAcl {
            [pscustomobject]@{ ok = $true; code = 'URLACL'; message = 'ok' }
        }

        $result = Install-OmDependencies -SetupPort 18799
        $result.pendingReboot | Should -BeTrue
    }

    It 'requires a reboot when the Tailscale MSI returns 3010' {
        # PRODUCT GAP: the step result carries exitCode=3010 but
        # Install-OmDependencies never aggregates it.
        Mock -ModuleName OpenMargin.Setup Test-OmAdmin { $true }
        Mock -ModuleName OpenMargin.Setup Test-OmRebootPending { $false }
        Mock -ModuleName OpenMargin.Setup Install-OmWsl {
            [pscustomobject]@{ ok = $true; code = 'WSL_READY'; message = 'ready' }
        }
        Mock -ModuleName OpenMargin.Setup Install-OmDockerDesktop {
            [pscustomobject]@{ ok = $true; code = 'DOCKER_PRESENT'; message = 'present' }
        }
        Mock -ModuleName OpenMargin.Setup Install-OmTailscale {
            [pscustomobject]@{ ok = $true; code = 'TAILSCALE_INSTALLED'; message = 'installed'; exitCode = 3010 }
        }
        Mock -ModuleName OpenMargin.Setup Set-OmTailscaleOperator {
            [pscustomobject]@{ ok = $true; code = 'OPERATOR'; message = 'ok' }
        }
        Mock -ModuleName OpenMargin.Setup Add-OmUrlAcl {
            [pscustomobject]@{ ok = $true; code = 'URLACL'; message = 'ok' }
        }

        $result = Install-OmDependencies -SetupPort 18799
        $result.ok | Should -BeTrue
        $result.pendingReboot | Should -BeTrue
    }

    It 'ignores a generic pending-reboot registry flag when components are ready' {
        Mock -ModuleName OpenMargin.Setup Test-OmAdmin { $true }
        Mock -ModuleName OpenMargin.Setup Test-OmRebootPending { $true }
        Mock -ModuleName OpenMargin.Setup Install-OmWsl {
            [pscustomobject]@{ ok = $true; code = 'WSL_READY'; message = 'ready' }
        }
        Mock -ModuleName OpenMargin.Setup Install-OmDockerDesktop {
            [pscustomobject]@{ ok = $true; code = 'DOCKER_PRESENT'; message = 'present' }
        }
        Mock -ModuleName OpenMargin.Setup Install-OmTailscale {
            [pscustomobject]@{ ok = $true; code = 'TAILSCALE_PRESENT'; message = 'present' }
        }
        Mock -ModuleName OpenMargin.Setup Set-OmTailscaleOperator {
            [pscustomobject]@{ ok = $true; code = 'OPERATOR'; message = 'ok' }
        }
        Mock -ModuleName OpenMargin.Setup Add-OmUrlAcl {
            [pscustomobject]@{ ok = $true; code = 'URLACL'; message = 'ok' }
        }

        $result = Install-OmDependencies -SetupPort 18799
        # Diagnostic-only registry flag: ready components must not trap the
        # wizard in WAITING_REBOOT.
        $result.pendingReboot | Should -BeFalse
    }
}

Describe 'Wizard browser handoff' -Tag 'Acceptance' {
    It 'writes setup-web-url.txt with a one-time token URL and serves the wizard' {
        $state = New-TestStateRoot 'handoff-real'
        $port = Get-FreeAcceptancePort -Start 18790
        $wizard = Start-AcceptanceWizard -Port $port -StateRoot $state
        try {
            $wizard.Url | Should -Match ([regex]::Escape('http://127.0.0.1:' + $wizard.Port + '/?token='))
            $wizard.Url | Should -Match '\?token=[A-Za-z0-9_-]{40,}$'
            (Test-Path (Join-Path $state 'setup-web-url.txt')) | Should -BeTrue

            $response = Invoke-WebRequest -Uri $wizard.Url -UseBasicParsing -TimeoutSec 15
            $response.StatusCode | Should -Be 200
            $response.Content | Should -Match 'btn-home'

            $health = Invoke-WebRequest -Uri ('http://127.0.0.1:' + $wizard.Port + '/api/health') -UseBasicParsing -TimeoutSec 10
            ($health.Content | ConvertFrom-Json).service | Should -Be 'open-margin-setup'
        } finally {
            Stop-TrackedProcesses
        }
    }

    It 'waits for a late setup-web-url.txt and shows a visible fallback URL' {
        $appRoot = Join-Path $script:acceptanceTemp 'late-app'
        $setupDir = Join-Path $appRoot 'deploy\windows\setup'
        $installerDir = Join-Path $setupDir 'installer'
        New-Item -ItemType Directory -Force -Path $installerDir | Out-Null
        foreach ($name in @('Launch-Setup.ps1', 'Stop-OpenMargin.ps1', 'Lifecycle.Common.ps1')) {
            Copy-Item -Path (Join-Path $script:setupRoot ('installer\' + $name)) -Destination $installerDir -Force
        }

        $fakeServer = @'
param(
    [int]$Port,
    [string]$StateRoot = '',
    [switch]$NoBrowser,
    [switch]$Dev,
    [switch]$DryRun,
    [switch]$Resume
)
New-Item -ItemType Directory -Force -Path $StateRoot | Out-Null
Start-Sleep -Milliseconds 2500
$url = 'http://127.0.0.1:' + $Port + '/?token=LATETOKENabcdefghijklmnopqrstuvwxyz'
[System.IO.File]::WriteAllText((Join-Path $StateRoot 'setup-web-url.txt'), $url)
Set-Content -Path (Join-Path $StateRoot 'fake-server.pid') -Value $PID
Start-Sleep -Seconds 60
'@
        Set-Content -Path (Join-Path $setupDir 'Start-OpenMarginSetupWeb.ps1') -Value $fakeServer -Encoding UTF8

        $harness = @'
param(
    [string]$LaunchPath,
    [int]$Port,
    [string]$StateRoot,
    [string]$AppRoot
)
$ErrorActionPreference = 'Continue'

function global:Start-Process {
    [CmdletBinding()]
    param(
        [Parameter(Position = 0)][string]$FilePath,
        [Parameter(Position = 1)][object]$ArgumentList,
        [switch]$PassThru,
        [string]$WorkingDirectory,
        [string]$WindowStyle,
        [switch]$Wait,
        [string]$Verb
    )
    if ($FilePath -match '^(?i)https?://') {
        throw ('simulated browser launch failure: ' + $FilePath)
    }
    $params = @{ FilePath = $FilePath }
    if ($PSBoundParameters.ContainsKey('ArgumentList')) { $params['ArgumentList'] = $ArgumentList }
    if ($PSBoundParameters.ContainsKey('WorkingDirectory')) { $params['WorkingDirectory'] = $WorkingDirectory }
    if ($PSBoundParameters.ContainsKey('WindowStyle')) { $params['WindowStyle'] = $WindowStyle }
    if ($PassThru) { $params['PassThru'] = $true }
    if ($Wait) { $params['Wait'] = $true }
    if ($PSBoundParameters.ContainsKey('Verb')) { $params['Verb'] = $Verb }
    Microsoft.PowerShell.Management\Start-Process @params
}

function global:Add-Type {
    [CmdletBinding()]
    param(
        [string]$AssemblyName,
        [string]$TypeDefinition,
        [string]$Path,
        [string]$Name,
        [string]$Namespace,
        [string]$OutputAssembly,
        [switch]$PassThru,
        [switch]$IgnoreWarnings
    )
    throw 'Add-Type is blocked in the acceptance harness'
}

& $LaunchPath -Ports $Port -StateRoot $StateRoot -AppRoot $AppRoot -NoElevate
Write-Output 'LAUNCH-RETURNED'
'@
        $harnessPath = Join-Path $script:acceptanceTemp 'launch-late-harness.ps1'
        Set-Content -Path $harnessPath -Value $harness -Encoding UTF8

        $state = New-TestStateRoot 'handoff-late'
        $port = Get-FreeAcceptancePort -Start 18800
        $expectedUrl = 'http://127.0.0.1:' + $port + '/?token=LATETOKENabcdefghijklmnopqrstuvwxyz'
        try {
            $result = Invoke-ChildProcess -FilePath $script:winPs -ArgumentList @(
                '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (ConvertTo-QuotedArgument $harnessPath),
                '-LaunchPath', (ConvertTo-QuotedArgument (Join-Path $installerDir 'Launch-Setup.ps1')),
                '-Port', [string]$port,
                '-StateRoot', (ConvertTo-QuotedArgument $state),
                '-AppRoot', (ConvertTo-QuotedArgument $appRoot)
            ) -TimeoutSec 90
            $result.ExitCode | Should -Be 0
            $result.StdOut | Should -Match 'LAUNCH-RETURNED'
            $result.StdOut | Should -Match ([regex]::Escape($expectedUrl))

            $logFile = Get-ChildItem -Path (Join-Path $state 'logs') -Filter 'launch-*.log' -ErrorAction SilentlyContinue |
                Sort-Object LastWriteTime | Select-Object -Last 1
            $logFile | Should -Not -BeNullOrEmpty
            $logText = Get-Content -Path $logFile.FullName -Raw -Encoding UTF8
            $logText | Should -Match 'browser open failed'
            $logText | Should -Match ([regex]::Escape($expectedUrl))
        } finally {
            $pidPath = Join-Path $state 'fake-server.pid'
            $deadline = (Get-Date).AddSeconds(5)
            while (-not (Test-Path $pidPath) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 100 }
            if (Test-Path $pidPath) {
                $fakePid = 0
                if ([int]::TryParse((Get-Content -Path $pidPath -Raw).Trim(), [ref]$fakePid)) {
                    Add-TrackedPid -ProcessId $fakePid
                }
            }
            Stop-TrackedProcesses
        }
    }
}

Describe 'CLI check and the wizard setup port' -Tag 'Acceptance' {
    It 'does not report the running wizard setup port as PORT_CONFLICT' {
        # PRODUCT GAP: the CLI `check` path builds checks from state.ports
        # without ignoring the wizard's live setup port, so a healthy wizard
        # makes its own check fail with PORT_CONFLICT.
        $state = New-TestStateRoot 'cli-self-port'
        $wizard = Start-AcceptanceWizard -Port (Get-FreeAcceptancePort -Start 18790) -StateRoot $state
        try {
            $apiPort = Get-FreeAcceptancePort -Start 18820
            $wallosPort = Get-FreeAcceptancePort -Start 18840
            $gatewayPort = Get-FreeAcceptancePort -Start 18860
            $cli = Join-Path $script:setupRoot 'open-margin-setup.ps1'
            $result = Invoke-ChildProcess -FilePath $script:winPs -ArgumentList @(
                '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (ConvertTo-QuotedArgument $cli),
                'check', '-Json',
                '-StateRoot', (ConvertTo-QuotedArgument $state),
                '-RepoRoot', (ConvertTo-QuotedArgument $script:repoRoot),
                '-ApiPort', [string]$apiPort,
                '-WallosPort', [string]$wallosPort,
                '-GatewayPort', [string]$gatewayPort,
                '-SetupPort', [string]$wizard.Port
            ) -TimeoutSec 180

            $json = $null
            try { $json = $result.StdOut | ConvertFrom-Json } catch { }
            $json | Should -Not -BeNullOrEmpty
            @($json.checks).Count | Should -BeGreaterThan 0
            $portsCheck = @($json.checks | Where-Object { $_.id -eq 'ports' })[0]
            $portsCheck | Should -Not -BeNullOrEmpty
            $portsCheck.status | Should -Be 'ok'
            $portsCheck.detail | Should -Not -Match ('setup:' + $wizard.Port)
        } finally {
            Stop-TrackedProcesses
        }
    }
}

Describe 'Wizard single-instance handling' -Tag 'Acceptance' {
    It 'reuses the live wizard instead of starting a second instance' {
        $state = New-TestStateRoot 'single-live'
        $wizard = Start-AcceptanceWizard -Port (Get-FreeAcceptancePort -Start 18900) -StateRoot $state
        $beforePid = $wizard.Pid
        try {
            $launch = Join-Path $script:setupRoot 'installer\Launch-Setup.ps1'
            $result = Invoke-ChildProcess -FilePath $script:winPs -ArgumentList @(
                '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (ConvertTo-QuotedArgument $launch),
                '-Ports', [string]$wizard.Port,
                '-StateRoot', (ConvertTo-QuotedArgument $state),
                '-AppRoot', (ConvertTo-QuotedArgument $script:repoRoot),
                '-NoElevate', '-NoBrowser'
            ) -TimeoutSec 90

            $result.ExitCode | Should -Be 0
            $result.StdOut | Should -Match 'already running'
            $result.StdOut | Should -Match ([regex]::Escape('?token='))
            (Get-Process -Id $beforePid -ErrorAction SilentlyContinue) | Should -Not -BeNullOrEmpty
            $stateAfter = Get-Content -Path (Join-Path $state 'setup-web.json') -Raw -Encoding UTF8 | ConvertFrom-Json
            [int]$stateAfter.pid | Should -Be $beforePid
        } finally {
            Stop-TrackedProcesses
        }
    }

    It 'clears a stale recorded PID and starts exactly one new wizard' {
        $state = New-TestStateRoot 'single-stale'
        $port = Get-FreeAcceptancePort -Start 18920
        $dummy = Start-Process -FilePath $script:winPs -ArgumentList @('-NoProfile', '-Command', 'Start-Sleep -Seconds 120') -PassThru -WindowStyle Hidden
        Add-TrackedProcess -Process $dummy
        try {
            Set-Content -Path (Join-Path $state 'setup-web.json') -Encoding UTF8 -Value (
                '{"port":18770,"pid":' + $dummy.Id + ',"url":"http://127.0.0.1:18770/"}'
            )

            $launch = Join-Path $script:setupRoot 'installer\Launch-Setup.ps1'
            $result = Invoke-ChildProcess -FilePath $script:winPs -ArgumentList @(
                '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (ConvertTo-QuotedArgument $launch),
                '-Ports', [string]$port,
                '-StateRoot', (ConvertTo-QuotedArgument $state),
                '-AppRoot', (ConvertTo-QuotedArgument $script:repoRoot),
                '-NoElevate', '-NoBrowser'
            ) -TimeoutSec 90

            $result.ExitCode | Should -Be 0
            (Get-Process -Id $dummy.Id -ErrorAction SilentlyContinue) | Should -Not -BeNullOrEmpty

            $statePath = Join-Path $state 'setup-web.json'
            $deadline = (Get-Date).AddSeconds(25)
            $stateAfter = $null
            while ((Get-Date) -lt $deadline) {
                if (Test-Path $statePath) {
                    try { $candidate = Get-Content -Path $statePath -Raw -Encoding UTF8 | ConvertFrom-Json } catch { $candidate = $null }
                    if ($candidate -and [int]$candidate.pid -ne $dummy.Id) {
                        $stateAfter = $candidate
                        break
                    }
                }
                Start-Sleep -Milliseconds 200
            }
            $stateAfter | Should -Not -BeNullOrEmpty
            Add-TrackedPid -ProcessId ([int]$stateAfter.pid)
            (Get-Process -Id $stateAfter.pid -ErrorAction SilentlyContinue) | Should -Not -BeNullOrEmpty

            $logFile = Get-ChildItem -Path (Join-Path $state 'logs') -Filter 'launch-*.log' -ErrorAction SilentlyContinue |
                Sort-Object LastWriteTime | Select-Object -Last 1
            $logFile | Should -Not -BeNullOrEmpty
            $logText = Get-Content -Path $logFile.FullName -Raw -Encoding UTF8
            $logText | Should -Match 'clearing stale setup-web.json'
            $logText | Should -Match ('starting setup wizard on port ' + $port)
        } finally {
            Stop-TrackedProcesses
        }
    }
}
