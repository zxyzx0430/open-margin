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

    It 'exports a complete 4-part redacted diagnostics bundle' {
        $stateRoot = Join-Path $TestDrive 'bundle-state'
        $logDir = Join-Path $stateRoot 'logs'
        $jobDir = Join-Path $stateRoot 'jobs'
        New-Item -ItemType Directory -Force -Path $logDir, $jobDir | Out-Null

        @'
{
  "step": "FAILED",
  "publicUrl": "https://pc.tailnet.ts.net/finance",
  "serverId": "srv-01",
  "deviceToken": "super-secret-device-token-1234567890",
  "lastError": { "step": "deploy", "code": "COMPOSE_FAILED", "message": "Authorization: Bearer secret-bearer-999" }
}
'@ | Set-Content -Path (Join-Path $stateRoot 'state.json') -Encoding UTF8

        '2026-11-23 12:00:00 [ERROR] deploy failed password=hunter2' |
            Set-Content -Path (Join-Path $logDir 'setup-20261123.log') -Encoding UTF8
        '2026-11-23 11:59:00 launch started' |
            Set-Content -Path (Join-Path $logDir 'launch-20261123.log') -Encoding UTF8
        '{"ok":false,"code":"COMPOSE_FAILED","token":"should-be-masked-token"}' |
            Set-Content -Path (Join-Path $jobDir 'job1.json') -Encoding UTF8

        $bundle = Export-OmDiagnosticsBundle -StateRoot $stateRoot -SetupPort 18770 -SkipExternalCommands
        $bundle | Should -Match '=== 1\. 环境与安装器状态快照 ==='
        $bundle | Should -Match '=== 2\. 最近后台任务记录 ==='
        $bundle | Should -Match '=== 3\. 本地日志文件 \(已脱敏\) ==='
        $bundle | Should -Match '=== 4\. Docker 与网络入口快照 ==='
        $bundle | Should -Match 'COMPOSE_FAILED'
        $bundle | Should -Match 'launch started'
        $bundle | Should -Not -Match 'super-secret-device-token-1234567890'
        $bundle | Should -Not -Match 'secret-bearer-999'
        $bundle | Should -Not -Match 'hunter2'
        $bundle | Should -Not -Match 'should-be-masked-token'
    }
}
