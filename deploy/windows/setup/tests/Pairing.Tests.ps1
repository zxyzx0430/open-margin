BeforeAll {
    Import-Module (Join-Path $PSScriptRoot '..\OpenMargin.Setup.psd1') -Force -DisableNameChecking
}

Describe 'Pairing payload' {
    It 'contains url, device token and server id directly' {
        $payload = New-OmPairingPayload -Url 'https://pc.tailnet.ts.net/finance' -Token 'device-token-value' -ServerId '0123456789abcdef'
        $data = $payload | ConvertFrom-Json
        $data.v | Should -Be 1
        $data.kind | Should -Be 'open-margin-pair'
        $data.url | Should -Be 'https://pc.tailnet.ts.net/finance'
        $data.token | Should -Be 'device-token-value'
        $data.server_id | Should -Be '0123456789abcdef'
    }

    It 'validates a well-formed payload' {
        $payload = New-OmPairingPayload -Url 'https://pc.tailnet.ts.net/finance' -Token 'device-token-value' -ServerId '0123456789abcdef'
        (Test-OmPairingPayload -Payload $payload).ok | Should -BeTrue
    }

    It 'rejects http, non-ts.net and wrong-path URLs' {
        Test-OmPairingUrl -Url 'http://pc.tailnet.ts.net/finance' | Should -BeFalse
        Test-OmPairingUrl -Url 'https://example.com/finance' | Should -BeFalse
        Test-OmPairingUrl -Url 'https://pc.tailnet.ts.net/other' | Should -BeFalse
        Test-OmPairingUrl -Url 'https://pc.tailnet.ts.net/finance?x=1' | Should -BeFalse
    }

    It 'rejects payloads missing token or server id' {
        $noToken = '{"v":1,"kind":"open-margin-pair","url":"https://pc.tailnet.ts.net/finance","token":"","server_id":"0123456789abcdef"}'
        (Test-OmPairingPayload -Payload $noToken).code | Should -Be 'PAIRING_TOKEN'
        $noServer = '{"v":1,"kind":"open-margin-pair","url":"https://pc.tailnet.ts.net/finance","token":"abc","server_id":""}'
        (Test-OmPairingPayload -Payload $noServer).code | Should -Be 'PAIRING_SERVER_ID'
    }

    It 'refuses to build a payload without a server id' {
        { New-OmPairingPayload -Url 'https://pc.tailnet.ts.net/finance' -Token 'abc' -ServerId '' } | Should -Throw
    }

    It 'does not write the pairing token to the state file' {
        $stateRoot = Join-Path $TestDrive 'pair-state'
        Initialize-OmPaths -StateRoot $stateRoot -RepoRoot (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
        $null = Set-OmStateValue -Name publicSyncUrl -Value 'https://pc.tailnet.ts.net/finance'
        $raw = Get-Content -Path (Get-OmStatePath) -Raw -Encoding UTF8
        $raw | Should -Not -Match 'device-token-value'
    }
}
