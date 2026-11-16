BeforeAll {
    Import-Module (Join-Path $PSScriptRoot '..\OpenMargin.Setup.psd1') -Force -DisableNameChecking
    function Read-Fixture([string]$Name) {
        return (Get-Content -Path (Join-Path $PSScriptRoot ('Fixtures\' + $Name)) -Raw -Encoding UTF8 | ConvertFrom-Json)
    }
    $script:serveEmpty = Read-Fixture 'tailscale-serve-empty.json'
    $script:serveOurs = Read-Fixture 'tailscale-serve-ours.json'
    $script:serveForeign = Read-Fixture 'tailscale-serve-foreign.json'
    $script:funnelOurs = Read-Fixture 'tailscale-funnel-ours.json'
    $script:funnelForeign = Read-Fixture 'tailscale-funnel-foreign.json'
    $script:funnelUnapproved = Read-Fixture 'tailscale-funnel-unapproved.json'
}

Describe 'Tailscale route ownership' {
    It 'reads no handlers from an empty status' {
        @(Get-OmRouteHandlers $script:serveEmpty).Count | Should -Be 0
    }

    It 'recognizes our Serve route and no foreign routes' {
        $routes = @(Get-OmRouteHandlers $script:serveOurs)
        $routes.Count | Should -Be 1
        $routes[0].path | Should -Be '/'
        Test-OmOurProxy -Proxy $routes[0].proxy -GatewayPort 18768 | Should -BeTrue
        (Test-OmForeignRoutes -Status $script:serveOurs -GatewayPort 18768).foreign | Should -BeFalse
        Test-OmServeOwnedByUs -Status $script:serveOurs -GatewayPort 18768 | Should -BeTrue
    }

    It 'refuses a foreign Serve route' {
        (Test-OmForeignRoutes -Status $script:serveForeign -GatewayPort 18768).foreign | Should -BeTrue
        Test-OmServeOwnedByUs -Status $script:serveForeign -GatewayPort 18768 | Should -BeFalse
    }

    It 'recognizes an approved funnel route as ready' {
        Test-OmFunnelApproved -Status $script:funnelOurs | Should -BeTrue
        Test-OmFunnelReady -Status $script:funnelOurs -GatewayPort 18768 | Should -BeTrue
    }

    It 'does not treat an unapproved funnel as ready' {
        Test-OmFunnelApproved -Status $script:funnelUnapproved | Should -BeFalse
        Test-OmFunnelReady -Status $script:funnelUnapproved -GatewayPort 18768 | Should -BeFalse
    }

    It 'refuses a foreign funnel route before touching it' {
        $result = Enable-OmFunnel -GatewayPort 18768 -UseInjectedStatus -ServeStatus $script:serveEmpty -FunnelStatus $script:funnelForeign
        $result.ok | Should -BeFalse
        $result.code | Should -Be 'FOREIGN_FUNNEL_ROUTE'
        $result.retryable | Should -BeFalse

        $disable = Disable-OmFunnel -GatewayPort 18768 -UseInjectedStatus -FunnelStatus $script:funnelForeign
        $disable.ok | Should -BeFalse
        $disable.code | Should -Be 'FOREIGN_FUNNEL_ROUTE'
    }

    It 'refuses a foreign Serve route before enabling funnel' {
        $result = Enable-OmFunnel -GatewayPort 18768 -UseInjectedStatus -ServeStatus $script:serveForeign -FunnelStatus $script:serveEmpty
        $result.ok | Should -BeFalse
        $result.code | Should -Be 'FOREIGN_SERVE_ROUTE'
    }

    It 'returns the public funnel URL' {
        Get-OmFunnelUrl -Status $script:funnelOurs | Should -Be 'https://pc.tailnet.ts.net/finance'
    }

    It 'accepts localhost as our proxy' {
        Test-OmOurProxy -Proxy 'http://localhost:18768/' -GatewayPort 18768 | Should -BeTrue
        Test-OmOurProxy -Proxy 'http://127.0.0.1:18769' -GatewayPort 18768 | Should -BeFalse
    }
}
