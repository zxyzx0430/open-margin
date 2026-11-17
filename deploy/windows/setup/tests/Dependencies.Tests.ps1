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
