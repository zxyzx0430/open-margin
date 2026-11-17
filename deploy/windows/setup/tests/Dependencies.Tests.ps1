BeforeAll {
    $script:modulePath = Join-Path $PSScriptRoot '..\OpenMargin.Setup.psd1'
    Import-Module $script:modulePath -Force -DisableNameChecking
    $script:stateRoot = Join-Path $TestDrive 'license-state'
    Initialize-OmPaths -StateRoot $script:stateRoot -RepoRoot (Join-Path $TestDrive 'license-app')
    $script:ports = [pscustomobject]@{ api = 8767; wallos = 18082; gateway = 18768; setup = 18770 }
}

Describe 'Docker license consent gate' {
    BeforeEach {
        $script:appRoot = Join-Path $TestDrive ('app-' + [guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Force -Path $script:appRoot | Out-Null
        Mock -ModuleName OpenMargin.Setup Get-OmDockerInfo {
            return [pscustomobject]@{ installed = $false; version = $null }
        }
        Mock -ModuleName OpenMargin.Setup Test-OmAdmin { return $true }
    }

    It 'returns DOCKER_LICENSE_REQUIRED when no installer marker or web consent exists' {
        $result = Install-OmDockerDesktop -AppRoot $script:appRoot
        $result.ok | Should -BeFalse
        $result.code | Should -Be 'DOCKER_LICENSE_REQUIRED'
        $result.action | Should -Be 'acknowledge_docker_license'
        $result.retryable | Should -BeTrue
        $result.message | Should -Match 'Docker'
    }

    It 'accepts the installer marker written after the consent page' {
        $ackPath = Join-Path $script:appRoot 'docker-license-ack.txt'
        Set-Content -Path $ackPath -Value 'acknowledgedAt=2026-11-17' -Encoding UTF8
        Test-OmDockerLicenseAcknowledged -AppRoot $script:appRoot | Should -BeTrue
        $result = Install-OmDockerDesktop -AppRoot $script:appRoot -DryRun
        $result.ok | Should -BeTrue
        $result.code | Should -Be 'DRY_RUN'
        $result.licenseAcknowledged | Should -BeTrue
    }

    It 'accepts an explicit per-request acknowledgement without the marker' {
        Test-OmDockerLicenseAcknowledged -AppRoot $script:appRoot | Should -BeFalse
        $result = Install-OmDockerDesktop -AppRoot $script:appRoot -DryRun -LicenseAcknowledged
        $result.ok | Should -BeTrue
        $result.licenseAcknowledged | Should -BeTrue
    }

    It 'does not require consent when Docker is already installed' {
        Mock -ModuleName OpenMargin.Setup Get-OmDockerInfo {
            return [pscustomobject]@{ installed = $true; version = '4.30.0' }
        }
        $result = Install-OmDockerDesktop -AppRoot $script:appRoot
        $result.ok | Should -BeTrue
        $result.code | Should -Be 'DOCKER_PRESENT'
    }

    It 'stops install-deps before touching Docker when consent is missing' {
        Mock -ModuleName OpenMargin.Setup Install-OmWsl {
            return [pscustomobject]@{ ok = $true; code = 'WSL_READY'; message = 'ok' }
        }
        $result = Install-OmDependencies -LicenseAcknowledged:$false
        $result.ok | Should -BeFalse
        $result.code | Should -Be 'DOCKER_LICENSE_REQUIRED'
        Should -Invoke -ModuleName OpenMargin.Setup Get-OmDockerInfo -Times 1
    }
}

Describe 'Web install-deps license pass-through' {
    BeforeAll {
        Mock -ModuleName OpenMargin.Setup Test-OmAdmin { return $true }
        Mock -ModuleName OpenMargin.Setup Install-OmDependencies {
            param($SetupPort, $DryRun, $LicenseAcknowledged, $ProgressPath)
            return [pscustomobject]@{ ok = $true; code = 'OK'; steps = @(); licenseAcknowledged = [bool]$LicenseAcknowledged }
        }
    }

    It 'passes licenseAcknowledged=true from the web request' {
        $result = Invoke-OmWebAction -Action 'install-deps' -Params ([pscustomobject]@{ licenseAcknowledged = $true }) -Ports $script:ports -StateRoot $script:stateRoot -RepoRoot (Join-Path $TestDrive 'license-app')
        $result.ok | Should -BeTrue
        Should -Invoke -ModuleName OpenMargin.Setup Install-OmDependencies -Times 1 -ParameterFilter { [bool]$LicenseAcknowledged }
    }

    It 'defaults to false when the web request omits consent' {
        $result = Invoke-OmWebAction -Action 'install-deps' -Params ([pscustomobject]@{}) -Ports $script:ports -StateRoot $script:stateRoot -RepoRoot (Join-Path $TestDrive 'license-app')
        $result.ok | Should -BeTrue
        Should -Invoke -ModuleName OpenMargin.Setup Install-OmDependencies -Times 1 -ParameterFilter { -not [bool]$LicenseAcknowledged }
    }
}

Describe 'Wizard surfaces the consent state' {
    It 'exposes the installer marker through /api/config source' {
        $server = Get-Content -Path (Join-Path $PSScriptRoot '..\Start-OpenMarginSetupWeb.ps1') -Raw -Encoding UTF8
        $server | Should -Match 'dockerLicenseAcknowledged\s*=\s*\(Test-OmDockerLicenseAcknowledged\)'
    }

    It 'renders a consent checkbox and sends it with install-deps' {
        $html = Get-Content -Path (Join-Path $PSScriptRoot '..\web\index.html') -Raw -Encoding UTF8
        $html | Should -Match 'id="ack-docker-license"'
        $html | Should -Match 'Docker 订阅服务协议'
        $script = Get-Content -Path (Join-Path $PSScriptRoot '..\web\app.js') -Raw -Encoding UTF8
        $script | Should -Match "postAction\('install-deps', \{ licenseAcknowledged:"
        $script | Should -Match 'DOCKER_LICENSE_REQUIRED'
        $script | Should -Match 'applyDockerLicenseInfo'
    }
}

Describe 'Progress file writes never abort the install' {
    BeforeEach {
        $script:progressDir = Join-Path $TestDrive ('jobs-' + [guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Force -Path $script:progressDir | Out-Null
        $script:progressPath = Join-Path $script:progressDir 'job.progress.json'
    }

    It 'replaces a progress file that already exists' {
        Set-Content -Path $script:progressPath -Value '{"stage":"old","percent":1}' -Encoding UTF8
        { Write-OmProgressFile -Path $script:progressPath -Stage 'docker' -Percent 40 -Message '安装 Docker Desktop' } | Should -Not -Throw
        $payload = Get-Content -Path $script:progressPath -Raw -Encoding UTF8 | ConvertFrom-Json
        $payload.stage | Should -Be 'docker'
        $payload.percent | Should -Be 40
        @(Get-ChildItem -Path $script:progressDir -Filter '*.tmp').Count | Should -Be 0
    }

    It 'keeps the old content, throws nothing and cleans up when the target is locked' {
        Set-Content -Path $script:progressPath -Value '{"stage":"locked","percent":85}' -Encoding UTF8
        $lock = [System.IO.File]::Open(
            $script:progressPath,
            [System.IO.FileMode]::Open,
            [System.IO.FileAccess]::ReadWrite,
            [System.IO.FileShare]::None
        )
        try {
            { Write-OmProgressFile -Path $script:progressPath -Stage 'tailscale' -Percent 70 } | Should -Not -Throw
        } finally {
            $lock.Dispose()
        }
        $payload = Get-Content -Path $script:progressPath -Raw -Encoding UTF8 | ConvertFrom-Json
        $payload.stage | Should -Be 'locked'
        @(Get-ChildItem -Path $script:progressDir -Filter '*.tmp').Count | Should -Be 0
    }

    It 'creates the parent directory for a first write' {
        $nested = Join-Path $script:progressDir ('nested\' + [guid]::NewGuid().ToString('N') + '\job.progress.json')
        { Write-OmProgressFile -Path $nested -Stage 'wsl' -Percent 10 } | Should -Not -Throw
        $payload = Get-Content -Path $nested -Raw -Encoding UTF8 | ConvertFrom-Json
        $payload.stage | Should -Be 'wsl'
    }
}

Describe 'URL ACL configuration is idempotent' {
    It 'treats an existing reservation from show urlacl as success without deleting it' {
        Mock -ModuleName OpenMargin.Setup Invoke-OmDependencyCommand {
            [pscustomobject]@{
                exitCode = 0
                output   = ("Reserved URL            : http://127.0.0.1:18770/" + "`r`n" + '    User: DOMAIN\user')
            }
        } -ParameterFilter { ($Arguments -join ' ') -like 'http show urlacl*' }
        Mock -ModuleName OpenMargin.Setup Invoke-OmDependencyCommand {
            [pscustomobject]@{ exitCode = 0; output = 'should not be called' }
        } -ParameterFilter { ($Arguments -join ' ') -like 'http add urlacl*' }

        $result = Add-OmUrlAcl -Port 18770

        $result.ok | Should -BeTrue
        $result.code | Should -Be 'URLACL_PRESENT'
        Should -Invoke -ModuleName OpenMargin.Setup Invoke-OmDependencyCommand -Times 1 -Exactly -ParameterFilter { ($Arguments -join ' ') -like 'http show urlacl*' }
        Should -Invoke -ModuleName OpenMargin.Setup Invoke-OmDependencyCommand -Times 0 -Exactly -ParameterFilter { ($Arguments -join ' ') -like 'http add urlacl*' }
        Should -Invoke -ModuleName OpenMargin.Setup Invoke-OmDependencyCommand -Times 0 -Exactly -ParameterFilter { ($Arguments -join ' ') -like 'http delete urlacl*' }
    }

    It 'treats netsh exit code 183 from add as an existing reservation' {
        Mock -ModuleName OpenMargin.Setup Invoke-OmDependencyCommand {
            [pscustomobject]@{ exitCode = 1; output = '没有与指定条件相匹配的 URL 预留。' }
        } -ParameterFilter { ($Arguments -join ' ') -like 'http show urlacl*' }
        Mock -ModuleName OpenMargin.Setup Invoke-OmDependencyCommand {
            [pscustomobject]@{ exitCode = 183; output = '' }
        } -ParameterFilter { ($Arguments -join ' ') -like 'http add urlacl*' }

        $result = Add-OmUrlAcl -Port 18770

        $result.ok | Should -BeTrue
        $result.code | Should -Be 'URLACL_PRESENT'
    }

    It 'treats the localized 183 message from add as an existing reservation' {
        Mock -ModuleName OpenMargin.Setup Invoke-OmDependencyCommand {
            [pscustomobject]@{ exitCode = 1; output = '没有与指定条件相匹配的 URL 预留。' }
        } -ParameterFilter { ($Arguments -join ' ') -like 'http show urlacl*' }
        Mock -ModuleName OpenMargin.Setup Invoke-OmDependencyCommand {
            [pscustomobject]@{ exitCode = 1; output = '当文件已存在时，无法创建该文件。' }
        } -ParameterFilter { ($Arguments -join ' ') -like 'http add urlacl*' }

        $result = Add-OmUrlAcl -Port 18770

        $result.ok | Should -BeTrue
        $result.code | Should -Be 'URLACL_PRESENT'
    }

    It 'still reports a genuine netsh failure' {
        Mock -ModuleName OpenMargin.Setup Invoke-OmDependencyCommand {
            [pscustomobject]@{ exitCode = 1; output = '没有与指定条件相匹配的 URL 预留。' }
        } -ParameterFilter { ($Arguments -join ' ') -like 'http show urlacl*' }
        Mock -ModuleName OpenMargin.Setup Invoke-OmDependencyCommand {
            [pscustomobject]@{ exitCode = 5; output = 'Access is denied.' }
        } -ParameterFilter { ($Arguments -join ' ') -like 'http add urlacl*' }

        $result = Add-OmUrlAcl -Port 18770

        $result.ok | Should -BeFalse
        $result.code | Should -Be 'URLACL_FAILED'
    }

    It 'does not touch netsh in a dry run' {
        Mock -ModuleName OpenMargin.Setup Invoke-OmDependencyCommand {
            [pscustomobject]@{ exitCode = 0; output = 'should not be called' }
        }

        $result = Add-OmUrlAcl -Port 18770 -DryRun

        $result.ok | Should -BeTrue
        $result.code | Should -Be 'DRY_RUN'
        Should -Invoke -ModuleName OpenMargin.Setup Invoke-OmDependencyCommand -Times 0 -Exactly
    }
}

Describe 'Tailscale operator configuration is best effort' {
    It 'uses tailscale set --operator when the CLI supports it' {
        Mock -ModuleName OpenMargin.Setup Find-OmTailscale { 'C:\fake\tailscale.exe' }
        Mock -ModuleName OpenMargin.Setup Get-OmTailscaleInfo { [pscustomobject]@{ installed = $true; backendState = 'Running' } }
        Mock -ModuleName OpenMargin.Setup Invoke-OmTailscale {
            [pscustomobject]@{ exitCode = 0; output = ('Usage: tailscale set [flags]' + "`n" + '  --operator string'); dryRun = $false }
        } -ParameterFilter { ($Arguments -join ' ') -eq 'set --help' }
        Mock -ModuleName OpenMargin.Setup Invoke-OmTailscale {
            [pscustomobject]@{ exitCode = 0; output = ''; dryRun = $false }
        } -ParameterFilter { ($Arguments -join ' ') -like 'set --operator=*' }
        Mock -ModuleName OpenMargin.Setup Invoke-OmTailscale {
            [pscustomobject]@{ exitCode = 0; output = ''; dryRun = $false }
        } -ParameterFilter { ($Arguments -join ' ') -like 'up --operator=*' }

        $result = Set-OmTailscaleOperator

        $result.ok | Should -BeTrue
        $result.code | Should -Be 'OPERATOR'
        Should -Invoke -ModuleName OpenMargin.Setup Invoke-OmTailscale -Times 1 -Exactly -ParameterFilter { ($Arguments -join ' ') -like 'set --operator=*' }
        Should -Invoke -ModuleName OpenMargin.Setup Invoke-OmTailscale -Times 0 -Exactly -ParameterFilter { ($Arguments -join ' ') -like 'up --operator=*' }
    }

    It 'falls back to tailscale up when tailscale set does not support --operator (1.104.x)' {
        Mock -ModuleName OpenMargin.Setup Find-OmTailscale { 'C:\fake\tailscale.exe' }
        Mock -ModuleName OpenMargin.Setup Get-OmTailscaleInfo { [pscustomobject]@{ installed = $true; backendState = 'Running' } }
        Mock -ModuleName OpenMargin.Setup Invoke-OmTailscale {
            [pscustomobject]@{ exitCode = 0; output = 'Usage: tailscale set [flags]'; dryRun = $false }
        } -ParameterFilter { ($Arguments -join ' ') -eq 'set --help' }
        Mock -ModuleName OpenMargin.Setup Invoke-OmTailscale {
            [pscustomobject]@{ exitCode = 0; output = ''; dryRun = $false }
        } -ParameterFilter { ($Arguments -join ' ') -like 'up --operator=*' }

        $result = Set-OmTailscaleOperator

        $result.ok | Should -BeTrue
        $result.code | Should -Be 'OPERATOR_VIA_UP'
        Should -Invoke -ModuleName OpenMargin.Setup Invoke-OmTailscale -Times 0 -Exactly -ParameterFilter { ($Arguments -join ' ') -like 'set --operator=*' }
        Should -Invoke -ModuleName OpenMargin.Setup Invoke-OmTailscale -Times 1 -Exactly -ParameterFilter { ($Arguments -join ' ') -like 'up --operator=*' }
    }

    It 'falls back to tailscale up when set rejects --operator at runtime' {
        Mock -ModuleName OpenMargin.Setup Find-OmTailscale { 'C:\fake\tailscale.exe' }
        Mock -ModuleName OpenMargin.Setup Get-OmTailscaleInfo { [pscustomobject]@{ installed = $true; backendState = 'Running' } }
        Mock -ModuleName OpenMargin.Setup Invoke-OmTailscale {
            [pscustomobject]@{ exitCode = 0; output = ('Usage: tailscale set [flags]' + "`n" + '  --operator string'); dryRun = $false }
        } -ParameterFilter { ($Arguments -join ' ') -eq 'set --help' }
        Mock -ModuleName OpenMargin.Setup Invoke-OmTailscale {
            [pscustomobject]@{ exitCode = 1; output = 'flag provided but not defined: -operator'; dryRun = $false }
        } -ParameterFilter { ($Arguments -join ' ') -like 'set --operator=*' }
        Mock -ModuleName OpenMargin.Setup Invoke-OmTailscale {
            [pscustomobject]@{ exitCode = 0; output = ''; dryRun = $false }
        } -ParameterFilter { ($Arguments -join ' ') -like 'up --operator=*' }

        $result = Set-OmTailscaleOperator

        $result.ok | Should -BeTrue
        $result.code | Should -Be 'OPERATOR_VIA_UP'
        Should -Invoke -ModuleName OpenMargin.Setup Invoke-OmTailscale -Times 1 -Exactly -ParameterFilter { ($Arguments -join ' ') -like 'up --operator=*' }
    }

    It 'skips with a warning instead of forcing a login when the node is not Running' {
        Mock -ModuleName OpenMargin.Setup Find-OmTailscale { 'C:\fake\tailscale.exe' }
        Mock -ModuleName OpenMargin.Setup Get-OmTailscaleInfo { [pscustomobject]@{ installed = $true; backendState = 'NeedsLogin' } }
        Mock -ModuleName OpenMargin.Setup Invoke-OmTailscale {
            [pscustomobject]@{ exitCode = 0; output = 'Usage: tailscale set [flags]'; dryRun = $false }
        } -ParameterFilter { ($Arguments -join ' ') -eq 'set --help' }
        Mock -ModuleName OpenMargin.Setup Invoke-OmTailscale {
            [pscustomobject]@{ exitCode = 0; output = ''; dryRun = $false }
        } -ParameterFilter { ($Arguments -join ' ') -like 'up --operator=*' }

        $result = Set-OmTailscaleOperator

        $result.ok | Should -BeTrue
        $result.code | Should -Be 'OPERATOR_SKIPPED'
        $result.warning | Should -BeTrue
        Should -Invoke -ModuleName OpenMargin.Setup Invoke-OmTailscale -Times 0 -Exactly -ParameterFilter { ($Arguments -join ' ') -like 'up --operator=*' }
    }

    It 'returns a non-fatal warning when the fallback also fails' {
        Mock -ModuleName OpenMargin.Setup Find-OmTailscale { 'C:\fake\tailscale.exe' }
        Mock -ModuleName OpenMargin.Setup Get-OmTailscaleInfo { [pscustomobject]@{ installed = $true; backendState = 'Running' } }
        Mock -ModuleName OpenMargin.Setup Invoke-OmTailscale {
            [pscustomobject]@{ exitCode = 0; output = 'Usage: tailscale set [flags]'; dryRun = $false }
        } -ParameterFilter { ($Arguments -join ' ') -eq 'set --help' }
        Mock -ModuleName OpenMargin.Setup Invoke-OmTailscale {
            [pscustomobject]@{ exitCode = 1; output = 'Access is denied.'; dryRun = $false }
        } -ParameterFilter { ($Arguments -join ' ') -like 'up --operator=*' }

        $result = Set-OmTailscaleOperator

        $result.ok | Should -BeTrue
        $result.code | Should -Be 'OPERATOR_WARNING'
        $result.warning | Should -BeTrue
    }

    It 'does not run any tailscale command in a dry run' {
        Mock -ModuleName OpenMargin.Setup Find-OmTailscale { 'C:\fake\tailscale.exe' }
        Mock -ModuleName OpenMargin.Setup Invoke-OmTailscale {
            [pscustomobject]@{ exitCode = 0; output = ''; dryRun = $false }
        }

        $result = Set-OmTailscaleOperator -DryRun

        $result.ok | Should -BeTrue
        $result.code | Should -Be 'DRY_RUN'
        Should -Invoke -ModuleName OpenMargin.Setup Invoke-OmTailscale -Times 0 -Exactly
    }
}

Describe 'Reboot semantics follow what was actually installed' {
    BeforeEach {
        Mock -ModuleName OpenMargin.Setup Test-OmAdmin { return $true }
        # The old implementation consulted this registry probe directly; it must
        # no longer influence the wizard's reboot decision.
        Mock -ModuleName OpenMargin.Setup Test-OmRebootPending { return $true }
    }

    It 'does not require a reboot when WSL was already ready and only Tailscale was installed' {
        Mock -ModuleName OpenMargin.Setup Install-OmWsl { [pscustomobject]@{ ok = $true; code = 'WSL_READY'; message = 'ready' } }
        Mock -ModuleName OpenMargin.Setup Install-OmDockerDesktop { [pscustomobject]@{ ok = $true; code = 'DOCKER_PRESENT'; message = 'present' } }
        Mock -ModuleName OpenMargin.Setup Install-OmTailscale { [pscustomobject]@{ ok = $true; code = 'TAILSCALE_PRESENT'; message = 'present' } }
        Mock -ModuleName OpenMargin.Setup Set-OmTailscaleOperator { [pscustomobject]@{ ok = $true; code = 'OPERATOR'; message = 'ok' } }
        Mock -ModuleName OpenMargin.Setup Add-OmUrlAcl { [pscustomobject]@{ ok = $true; code = 'URLACL_PRESENT'; message = 'ok' } }

        $result = Install-OmDependencies

        $result.ok | Should -BeTrue
        $result.pendingReboot | Should -BeFalse
        @($result.rebootReasons).Count | Should -Be 0
    }

    It 'requires a reboot when WSL was newly installed' {
        Mock -ModuleName OpenMargin.Setup Install-OmWsl { [pscustomobject]@{ ok = $true; code = 'WSL_INSTALLED'; message = 'installed'; needsReboot = $true } }
        Mock -ModuleName OpenMargin.Setup Install-OmDockerDesktop { [pscustomobject]@{ ok = $true; code = 'DOCKER_PRESENT'; message = 'present' } }
        Mock -ModuleName OpenMargin.Setup Install-OmTailscale { [pscustomobject]@{ ok = $true; code = 'TAILSCALE_PRESENT'; message = 'present' } }
        Mock -ModuleName OpenMargin.Setup Set-OmTailscaleOperator { [pscustomobject]@{ ok = $true; code = 'OPERATOR'; message = 'ok' } }
        Mock -ModuleName OpenMargin.Setup Add-OmUrlAcl { [pscustomobject]@{ ok = $true; code = 'URLACL_PRESENT'; message = 'ok' } }

        $result = Install-OmDependencies

        $result.ok | Should -BeTrue
        $result.pendingReboot | Should -BeTrue
        $result.rebootReasons | Should -Contain 'wsl'
    }

    It 'requires a reboot when an installer returns 3010' {
        Mock -ModuleName OpenMargin.Setup Install-OmWsl { [pscustomobject]@{ ok = $true; code = 'WSL_READY'; message = 'ready' } }
        Mock -ModuleName OpenMargin.Setup Install-OmDockerDesktop { [pscustomobject]@{ ok = $true; code = 'DOCKER_PRESENT'; message = 'present' } }
        Mock -ModuleName OpenMargin.Setup Install-OmTailscale { [pscustomobject]@{ ok = $true; code = 'TAILSCALE_INSTALLED'; message = 'installed'; exitCode = 3010 } }
        Mock -ModuleName OpenMargin.Setup Set-OmTailscaleOperator { [pscustomobject]@{ ok = $true; code = 'OPERATOR'; message = 'ok' } }
        Mock -ModuleName OpenMargin.Setup Add-OmUrlAcl { [pscustomobject]@{ ok = $true; code = 'URLACL_PRESENT'; message = 'ok' } }

        $result = Install-OmDependencies

        $result.ok | Should -BeTrue
        $result.pendingReboot | Should -BeTrue
        $result.rebootReasons | Should -Contain 'TAILSCALE_INSTALLED:3010'
    }
}

Describe 'Docker installer exit codes' {
    It 'treats winget exit code 3010 as installed with a reboot pending' {
        Mock -ModuleName OpenMargin.Setup Get-OmDockerInfo { [pscustomobject]@{ installed = $false; version = $null } }
        Mock -ModuleName OpenMargin.Setup Get-OmCommandPath { 'C:\fake\winget.exe' }
        Mock -ModuleName OpenMargin.Setup Invoke-OmDependencyCommand {
            [pscustomobject]@{ exitCode = 3010; output = 'success, restart required' }
        } -ParameterFilter { $FilePath -eq 'C:\fake\winget.exe' }

        $result = Install-OmDockerDesktop -LicenseAcknowledged -AppRoot (Join-Path $TestDrive 'docker-app')

        $result.ok | Should -BeTrue
        $result.code | Should -Be 'DOCKER_INSTALLED'
        $result.exitCode | Should -Be 3010
    }
}
