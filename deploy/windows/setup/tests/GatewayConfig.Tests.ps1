BeforeAll {
    $script:deployRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
    $script:repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
    $script:publicConf = Get-Content -Path (Join-Path $script:deployRoot 'public-sync-gateway.conf') -Raw -Encoding UTF8
    $script:privateConf = Get-Content -Path (Join-Path $script:deployRoot 'private-sync-gateway.conf') -Raw -Encoding UTF8
    $script:compose = Get-Content -Path (Join-Path $script:repoRoot 'compose.yaml') -Raw -Encoding UTF8
}

Describe 'Public sync gateway config' {
    It 'allows exactly the health and sync endpoints' {
        $script:publicConf | Should -Match 'location = /finance/health'
        $script:publicConf | Should -Match 'location = /finance/api/v2/sync/changes'
        $script:publicConf | Should -Match 'location = /finance/api/v2/sync/push'
        $script:publicConf | Should -Match 'location = /finance/api/v1/transactions/batch'
        $script:publicConf | Should -Match 'location = /finance/api/v1/transactions/correct-rounding'
    }

    It 'denies everything else and wrong methods' {
        $script:publicConf | Should -Match 'location / \{\s*return 404;'
        $script:publicConf | Should -Match 'request_method != GET\) \{ return 405; \}'
        $script:publicConf | Should -Match 'request_method != POST\) \{ return 405; \}'
    }

    It 'rejects encoded paths and limits requests' {
        $script:publicConf | Should -Match 'request_uri ~ "%"'
        $script:publicConf | Should -Match 'limit_req_zone'
        $script:publicConf | Should -Match 'limit_conn_zone'
        $script:publicConf | Should -Match 'client_max_body_size 4m'
    }

    It 'does not log request headers or query strings' {
        $logFormatLine = ($script:publicConf -split "`r?`n" | Where-Object { $_ -match '^log_format ' }) -join ''
        $logFormatLine | Should -Not -Match 'http_x_device_token'
        $logFormatLine | Should -Not -Match '\$request_uri'
        $logFormatLine | Should -Match '\$uri'
    }

    It 'has no leftover allowlist entries for Wallos, MCP or OAuth' {
        $script:publicConf | Should -Not -Match 'location = /finance/wallos'
        $script:publicConf | Should -Not -Match 'location = /finance/mcp'
        $script:publicConf | Should -Not -Match 'location = /finance/oauth'
    }
}

Describe 'Compose public profile' {
    It 'declares the public-sync service and profile' {
        $script:compose | Should -Match 'public-sync-gateway:'
        $script:compose | Should -Match 'profiles: \[windows-public-sync\]'
        $script:compose | Should -Match 'deploy/windows/public-sync-gateway.conf'
        $script:compose | Should -Match '127\.0\.0\.1:\$\{GATEWAY_PORT:-18768\}:80'
    }

    It 'keeps the private profile service' {
        $script:compose | Should -Match 'private-sync-gateway:'
        $script:compose | Should -Match 'profiles: \[windows-private-sync\]'
        $script:compose | Should -Match 'deploy/windows/private-sync-gateway.conf'
    }
}
