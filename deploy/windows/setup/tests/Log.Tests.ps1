BeforeAll {
    Import-Module (Join-Path $PSScriptRoot '..\OpenMargin.Setup.psd1') -Force -DisableNameChecking
    $script:logRoot = Join-Path $TestDrive 'logs'
    Initialize-OmLog -Directory $script:logRoot
}

Describe 'Secret redaction' {
    It 'masks key/value forms' {
        (Protect-OmText 'FINANCE_DEVICE_TOKEN=super-secret-value') | Should -Match 'FINANCE_DEVICE_TOKEN=\*\*\*'
        (Protect-OmText 'password: hunter2') | Should -Match 'password: \*\*\*'
        (Protect-OmText '{"password":"hunter2"}') | Should -Not -Match 'hunter2'
    }

    It 'masks JSON pairing payload tokens and headers' {
        $payload = '{"v":1,"kind":"open-margin-pair","url":"https://pc.tailnet.ts.net/finance","token":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","server_id":"0123456789abcdef"}'
        $masked = Protect-OmText $payload
        $masked | Should -Not -Match 'aaaaaaaaaaaaaaaa'
        $masked | Should -Match '"token":"\*\*\*"'
        (Protect-OmText 'X-Device-Token: 0123456789abcdef') | Should -Match 'X-Device-Token: \*\*\*'
    }

    It 'masks bearer tokens and long standalone secrets' {
        (Protect-OmText 'Authorization: Bearer abc.def.ghi') | Should -Match 'Bearer \*\*\*'
        (Protect-OmText ('A' * 64)) | Should -Be '***'
    }

    It 'leaves normal URLs and messages intact' {
        $text = 'tailscale funnel --bg --https=443 http://127.0.0.1:18768'
        Protect-OmText $text | Should -Be $text
        $url = 'https://pc.tailnet.ts.net/finance'
        Protect-OmText $url | Should -Be $url
    }
}

Describe 'Log file' {
    It 'writes redacted lines and never stores the secret' {
        Write-OmLog -Level INFO -Message 'device token FINANCE_DEVICE_TOKEN=abcdefghijklmnopqrstuvwxyz0123456789'
        $files = @(Get-ChildItem -Path $script:logRoot -Filter 'setup-*.log' -File)
        $files.Count | Should -BeGreaterThan 0
        $raw = Get-Content -Path $files[0].FullName -Raw -Encoding UTF8
        $raw | Should -Not -Match 'abcdefghijklmnopqrstuvwxyz0123456789'
        $raw | Should -Match '\*\*\*'
    }

    It 'returns a redacted tail of the log' {
        $lines = Get-OmLogTail -Lines 10
        @($lines).Count | Should -BeGreaterThan 0
        ($lines -join "`n") | Should -Not -Match 'abcdefghijklmnopqrstuvwxyz0123456789'
    }
}
